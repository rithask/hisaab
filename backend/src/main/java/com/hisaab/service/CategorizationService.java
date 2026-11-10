package com.hisaab.service;

import com.hisaab.domain.Category;
import com.hisaab.domain.CategoryRule;
import com.hisaab.domain.Enums.CategoryKind;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.RuleMatch;
import com.hisaab.domain.Enums.RuleSource;
import com.hisaab.domain.Txn;
import com.hisaab.repo.CategoryRuleRepo;
import com.hisaab.repo.TxnRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Auto-categorisation. Learned merchant rules (from my corrections) win over keyword rules.
 * A rule only applies when its category kind fits the direction (expenses for debits, income for credits).
 */
@Service
public class CategorizationService {
    private final CategoryRuleRepo rules;
    private final TxnRepo txns;

    public CategorizationService(CategoryRuleRepo rules, TxnRepo txns) {
        this.rules = rules;
        this.txns = txns;
    }

    static CategoryKind kindFor(Direction d) {
        return d == Direction.DEBIT ? CategoryKind.EXPENSE : CategoryKind.INCOME;
    }

    @Transactional
    public void categorize(Txn t) {
        if (t.isTransfer() || t.getCategory() != null) return;
        findRule(t).ifPresent(r -> {
            t.setCategory(r.getCategory());
            r.setHits(r.getHits() + 1);
        });
    }

    Optional<CategoryRule> findRule(Txn t) {
        CategoryKind kind = kindFor(t.getDirection());
        if (t.getMerchantKey() != null) {
            Optional<CategoryRule> exact = rules.findByMatchTypeAndPattern(RuleMatch.MERCHANT, t.getMerchantKey())
                    .filter(r -> r.getCategory().getKind() == kind);
            if (exact.isPresent()) return exact;
        }
        String text = (t.getCounterparty() == null ? "" : t.getCounterparty()) + " "
                + (t.getDescription() == null ? "" : t.getDescription());
        return rules.findByMatchTypeOrderByPriorityDesc(RuleMatch.CONTAINS).stream()
                .filter(r -> r.getCategory().getKind() == kind)
                .filter(r -> Names.containsWord(text, r.getPattern()))
                .findFirst();
    }

    /** Remember that this merchant belongs to this category. Returns how many other transactions look similar. */
    @Transactional
    public int learn(Txn t, Category category) {
        if (t.getMerchantKey() == null || category == null) return 0;
        CategoryRule rule = rules.findByMatchTypeAndPattern(RuleMatch.MERCHANT, t.getMerchantKey())
                .orElseGet(() -> {
                    CategoryRule r = new CategoryRule();
                    r.setMatchType(RuleMatch.MERCHANT);
                    r.setPattern(t.getMerchantKey());
                    r.setPriority(1000);
                    return r;
                });
        rule.setCategory(category);
        rule.setSource(RuleSource.LEARNED);
        rules.save(rule);
        return similar(t, category).size();
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
        list.forEach(o -> o.setCategory(t.getCategory()));
        return list.size();
    }
}
