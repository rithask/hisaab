package com.hisaab.service;

import com.hisaab.domain.Category;
import com.hisaab.domain.CategoryRule;
import com.hisaab.domain.CategoryVote;
import com.hisaab.domain.Enums.CategoryKind;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.RuleMatch;
import com.hisaab.domain.Enums.RuleSource;
import com.hisaab.domain.Txn;
import com.hisaab.repo.CategoryRuleRepo;
import com.hisaab.repo.CategoryVoteRepo;
import com.hisaab.repo.TxnRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-categorisation, learned from my corrections. In order:
 * <ol>
 *   <li>the payee exactly as learned, then its UPI ID (the same UPI ID gets the same category whatever name the
 *       bank prints);</li>
 *   <li>a payee whose words mostly match a learned one ("swiggy instamart blr" after "swiggy instamart"); never
 *       for payments to people (UPI P2P/P2A), where similar names are different people;</li>
 *   <li>the keyword rules.</li>
 * </ol>
 * Each payee keeps a count per category, so the majority decides and one odd purchase doesn't flip it. When I've
 * used two categories about equally for a payee, the amount picks the one I usually use for amounts like it.
 * A rule only applies when its category kind fits the direction (expenses for debits, income for credits), and a
 * category chosen this way is marked as suggested.
 */
@Service
public class CategorizationService {
    private final CategoryRuleRepo rules;
    private final TxnRepo txns;
    private final CategoryVoteRepo votes;

    public CategorizationService(CategoryRuleRepo rules, TxnRepo txns, CategoryVoteRepo votes) {
        this.rules = rules;
        this.txns = txns;
        this.votes = votes;
    }

    static CategoryKind kindFor(Direction d) {
        return d == Direction.DEBIT ? CategoryKind.EXPENSE : CategoryKind.INCOME;
    }

    @Transactional
    public void categorize(Txn t) {
        if (t.isTransfer() || t.getCategory() != null) return;
        suggest(t).ifPresent(c -> {
            t.setCategory(c);
            t.setCategorySuggested(true);
        });
    }

    /** The category the app would pick for this transaction, if any. */
    Optional<Category> suggest(Txn t) {
        CategoryKind kind = kindFor(t.getDirection());
        if (t.getMerchantKey() != null) {
            Optional<Category> c = choose(fitting(votes.findByKeyTypeAndKey(CategoryVote.MERCHANT, t.getMerchantKey()), kind), t.getAmount());
            if (c.isPresent()) return c;
        }
        String vpa = vpaOf(t);
        if (vpa != null) {
            Optional<Category> c = choose(fitting(votes.findByKeyTypeAndKey(CategoryVote.VPA, vpa), kind), t.getAmount());
            if (c.isPresent()) return c;
        }
        // rules I set by hand for a payee
        if (t.getMerchantKey() != null) {
            Optional<CategoryRule> exact = rules.findByMatchTypeAndPattern(RuleMatch.MERCHANT, t.getMerchantKey())
                    .filter(r -> r.getCategory().getKind() == kind);
            if (exact.isPresent()) {
                exact.get().setHits(exact.get().getHits() + 1);
                return exact.map(CategoryRule::getCategory);
            }
        }
        if (t.getMerchantKey() != null && !toPerson(t)) {
            Optional<Category> c = similar(t.getMerchantKey(), kind, t.getAmount());
            if (c.isPresent()) return c;
        }
        String text = (t.getCounterparty() == null ? "" : t.getCounterparty()) + " "
                + (t.getDescription() == null ? "" : t.getDescription());
        return rules.findByMatchTypeOrderByPriorityDesc(RuleMatch.CONTAINS).stream()
                .filter(r -> r.getCategory().getKind() == kind)
                .filter(r -> Names.containsWord(text, r.getPattern()))
                .findFirst()
                .map(r -> {
                    r.setHits(r.getHits() + 1);
                    return r.getCategory();
                });
    }

    private static List<CategoryVote> fitting(List<CategoryVote> vs, CategoryKind kind) {
        return vs.stream().filter(v -> v.getCategory().getKind() == kind && v.getVotes() > 0).toList();
    }

    /** How much a payee's words must overlap a learned one's to share its category. */
    static final double SIMILAR_AT = 0.6;

    /** Votes of learned payees whose words mostly match, weighted by how alike they are. */
    private Optional<Category> similar(String key, CategoryKind kind, BigDecimal amount) {
        Set<String> mine = Names.tokens(key);
        if (mine.isEmpty()) return Optional.empty();
        Map<String, Double> alike = new HashMap<>();
        List<CategoryVote> near = new ArrayList<>();
        for (CategoryVote v : votes.findByKeyType(CategoryVote.MERCHANT)) {
            if (v.getKey().equals(key) || v.getCategory().getKind() != kind || v.getVotes() <= 0) continue;
            double s = alike.computeIfAbsent(v.getKey(), k -> jaccard(mine, Names.tokens(k)));
            if (s >= SIMILAR_AT) near.add(v);
        }
        if (near.isEmpty()) return Optional.empty();
        // the most alike payee decides; ties between equally alike payees add up
        double best = near.stream().mapToDouble(v -> alike.get(v.getKey())).max().orElse(0);
        return choose(near.stream().filter(v -> alike.get(v.getKey()) == best).toList(), amount);
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> both = new HashSet<>(a);
        both.retainAll(b);
        Set<String> any = new HashSet<>(a);
        any.addAll(b);
        return (double) both.size() / any.size();
    }

    /**
     * The majority category. When the top two are close (the leader has less than twice the runner-up's votes)
     * and I've used both for known amounts, the one whose typical amount is nearer this one wins; otherwise the
     * leader, and among equals the one I used most recently.
     */
    static Optional<Category> choose(List<CategoryVote> vs, BigDecimal amount) {
        if (vs.isEmpty()) return Optional.empty();
        Map<Long, Integer> count = new HashMap<>();
        Map<Long, BigDecimal> sum = new HashMap<>();
        Map<Long, Integer> n = new HashMap<>();
        Map<Long, Instant> last = new HashMap<>();
        Map<Long, Category> cat = new HashMap<>();
        for (CategoryVote v : vs) {
            Long id = v.getCategory().getId();
            cat.put(id, v.getCategory());
            count.merge(id, v.getVotes(), Integer::sum);
            sum.merge(id, v.getAmountSum(), BigDecimal::add);
            n.merge(id, v.getAmountN(), Integer::sum);
            last.merge(id, v.getLastAt(), (a, b) -> a.isAfter(b) ? a : b);
        }
        List<Long> ranked = count.keySet().stream()
                .sorted(Comparator.comparing((Long id) -> count.get(id)).reversed().thenComparing(id -> last.get(id), Comparator.reverseOrder()))
                .toList();
        Long top = ranked.get(0);
        if (ranked.size() > 1 && amount != null && amount.signum() > 0) {
            Long second = ranked.get(1);
            if (count.get(top) < 2 * count.get(second) && n.get(top) > 0 && n.get(second) > 0) {
                double a = Math.log(amount.doubleValue());
                double dTop = Math.abs(a - Math.log(mean(sum.get(top), n.get(top))));
                double dSecond = Math.abs(a - Math.log(mean(sum.get(second), n.get(second))));
                if (dSecond < dTop) top = second;
            }
        }
        return Optional.of(cat.get(top));
    }

    private static double mean(BigDecimal sum, int n) {
        return Math.max(0.01, sum.doubleValue() / n);
    }

    private static final Pattern VPA = Pattern.compile("(?i)\\b([a-z0-9._-]{2,}@[a-z]{2,})\\b");
    private static final Pattern TO_PERSON = Pattern.compile("(?i)\\b(?:UPI|IMPS)/(?:P2A|P2P)/");

    /** The UPI ID in the payee or the bank's description, lower-cased. */
    static String vpaOf(Txn t) {
        for (String s : new String[]{t.getCounterparty(), t.getDescription()}) {
            if (s == null) continue;
            Matcher m = VPA.matcher(s);
            if (m.find()) return m.group(1).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /** A UPI or IMPS payment to a person (P2P/P2A), not a shop (P2M). */
    static boolean toPerson(Txn t) {
        return t.getDescription() != null && TO_PERSON.matcher(t.getDescription()).find();
    }

    /**
     * Remember that this payee belongs to this category: a vote for it (and for its UPI ID) with the amount.
     * Returns how many other transactions look similar.
     */
    @Transactional
    public int learn(Txn t, Category category) {
        if (category == null) return 0;
        String vpa = vpaOf(t);
        if (vpa != null) vote(CategoryVote.VPA, vpa, category, t.getAmount());
        if (t.getMerchantKey() == null) return 0;
        vote(CategoryVote.MERCHANT, t.getMerchantKey(), category, t.getAmount());
        // The learned rule stays as the visible record (Settings → Categories), showing the current choice.
        CategoryRule rule = rules.findByMatchTypeAndPattern(RuleMatch.MERCHANT, t.getMerchantKey())
                .orElseGet(() -> {
                    CategoryRule r = new CategoryRule();
                    r.setMatchType(RuleMatch.MERCHANT);
                    r.setPattern(t.getMerchantKey());
                    r.setPriority(1000);
                    return r;
                });
        if (rule.getSource() != RuleSource.MANUAL) {
            rule.setCategory(choose(fitting(votes.findByKeyTypeAndKey(CategoryVote.MERCHANT, t.getMerchantKey()), category.getKind()), t.getAmount()).orElse(category));
            rule.setSource(RuleSource.LEARNED);
            rules.save(rule);
        }
        return similar(t, category).size();
    }

    private void vote(String type, String key, Category c, BigDecimal amount) {
        CategoryVote v = votes.findByKeyTypeAndKey(type, key).stream()
                .filter(x -> x.getCategory().getId().equals(c.getId())).findFirst()
                .orElseGet(() -> {
                    CategoryVote n = new CategoryVote();
                    n.setKeyType(type);
                    n.setKey(key);
                    n.setCategory(c);
                    return n;
                });
        v.setVotes(v.getVotes() + 1);
        if (amount != null && amount.signum() > 0) {
            v.setAmountSum(v.getAmountSum().add(amount));
            v.setAmountN(v.getAmountN() + 1);
        }
        v.setLastAt(Instant.now());
        votes.save(v);
    }

    /** I edited a payee rule by hand: that category is now the only one for the payee. */
    @Transactional
    public void ruleSet(CategoryRule r) {
        if (r.getMatchType() != RuleMatch.MERCHANT) return;
        votes.deleteAll(votes.findByKeyTypeAndKey(CategoryVote.MERCHANT, r.getPattern()));
        CategoryVote v = new CategoryVote();
        v.setKeyType(CategoryVote.MERCHANT);
        v.setKey(r.getPattern());
        v.setCategory(r.getCategory());
        v.setVotes(1);
        votes.save(v);
    }

    /** I deleted a payee rule: forget what was learned for that payee. */
    @Transactional
    public void ruleDeleted(CategoryRule r) {
        if (r.getMatchType() == RuleMatch.MERCHANT) votes.deleteAll(votes.findByKeyTypeAndKey(CategoryVote.MERCHANT, r.getPattern()));
    }

    List<Txn> similar(Txn t, Category category) {
        if (t.getMerchantKey() == null) return List.of();
        return txns.findSimilar(t.getMerchantKey(), t.getId(), category.getId()).stream()
                .filter(o -> o.getDirection() == t.getDirection())
                .toList();
    }

    @Transactional
    public int applyToSimilar(Txn t) {
        if (t.getCategory() == null) return 0;
        List<Txn> list = similar(t, t.getCategory());
        list.forEach(o -> {
            o.setCategory(t.getCategory());
            o.setCategorySuggested(false);
        });
        return list.size();
    }

}
