package com.hisaab.service;

import com.hisaab.domain.*;
import com.hisaab.domain.Enums.Channel;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.TxnSource;
import com.hisaab.mail.parser.ParsedTransaction;
import com.hisaab.repo.CategoryRepo;
import com.hisaab.repo.TagRepo;
import com.hisaab.repo.TxnRepo;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@Service
public class TransactionService {
    private final TxnRepo txns;
    private final CategoryRepo categories;
    private final TagRepo tags;
    private final CategorizationService categorizer;
    private final TransferService transfers;
    private final BalanceService balances;
    private final SplitService splits;
    private final PriceService prices;
    private final PayeeAliasService aliases;

    public TransactionService(TxnRepo txns, CategoryRepo categories, TagRepo tags, CategorizationService categorizer,
                              TransferService transfers, BalanceService balances, SplitService splits,
                              PriceService prices, PayeeAliasService aliases) {
        this.aliases = aliases;
        this.txns = txns;
        this.categories = categories;
        this.tags = tags;
        this.categorizer = categorizer;
        this.transfers = transfers;
        this.balances = balances;
        this.splits = splits;
        this.prices = prices;
    }

    public Txn get(Long id) {
        return txns.findById(id).orElseThrow(() -> new NoSuchElementException("Transaction " + id + " not found"));
    }

    /** Resolve the moment a transaction happened. Date-only alerts use the email's arrival time. */
    static Instant occurredAt(LocalDate date, LocalTime time, Instant receivedAt) {
        if (date == null) return receivedAt != null ? receivedAt : Instant.now();
        if (time != null) return date.atTime(time).atZone(Money.INDIA).toInstant();
        if (receivedAt != null && receivedAt.atZone(Money.INDIA).toLocalDate().equals(date)) return receivedAt;
        return date.atTime(12, 0).atZone(Money.INDIA).toInstant();
    }

    /**
     * Records a transaction parsed from a bank alert (email or SMS).
     *
     * @return empty when this transaction was already recorded: the same alert forwarded twice, or the same
     * payment already reported through the other channel (in which case missing details are filled in).
     */
    @Transactional
    public Optional<Txn> createFromEmail(ParsedTransaction p, Account account, RawEmail email) {
        Instant at = occurredAt(p.date(), p.time(), email.getReceivedAt());
        boolean timeGuessed = p.time() == null;
        TxnSource source = email.getChannel() == Channel.SMS ? TxnSource.SMS : TxnSource.EMAIL;
        Txn t = new Txn();
        t.setAccount(account);
        t.setDirection(p.direction());
        t.setOccurredAt(at);
        applyAmount(t, p.amount(), p.currency());
        if (p.reference() != null) {
            Optional<Txn> same = txns.findFirstByAccountAndReferenceNoAndAmountAndDirection(account, p.reference(), t.getAmount(), p.direction());
            if (same.isPresent()) {
                // the same payment's alert from the other channel: it's now paired and can't absorb another one
                if (same.get().getSource() != source && same.get().getTwinRawEmail() == null) same.get().setTwinRawEmail(email);
                return Optional.empty();
            }
        }
        Optional<Txn> twin = crossChannelTwin(t, source, p.time() != null, p.reference(), email);
        if (twin.isPresent()) {
            Txn existing = twin.get();
            existing.setTwinRawEmail(email);
            if (existing.getCounterparty() == null && p.counterparty() != null) {
                existing.setCounterparty(aliases.apply(p.counterparty()));
                existing.setMerchantKey(Names.merchantKey(existing.getCounterparty()));
            }
            if (existing.getReferenceNo() == null) existing.setReferenceNo(p.reference());
            if (existing.getReportedBalance() == null) existing.setReportedBalance(p.balance());
            return Optional.empty();
        }
        String payee = aliases.apply(p.counterparty());
        t.setCounterparty(payee);
        t.setMerchantKey(Names.merchantKey(payee));
        t.setDescription(p.description());
        t.setReferenceNo(p.reference());
        t.setReportedBalance(p.balance());
        t.setSource(source);
        t.setTimeEstimated(timeGuessed);
        t.setRawEmail(email);
        txns.save(t);
        afterCreate(t, p.cardPayment());
        return Optional.of(t);
    }

    /**
     * The same payment's alert from the other channel (its email for an SMS, or the reverse): same account,
     * direction and amount, close in time (20 minutes, or the whole day when the alert has only a date). A
     * transaction that already has its other alert is taken, so two equal payments close together stay two.
     * Different reference numbers mean different payments; one with the same reference is preferred, then the
     * closest in time.
     */
    private Optional<Txn> crossChannelTwin(Txn t, TxnSource source, boolean timed, String reference, RawEmail email) {
        TxnSource other = source == TxnSource.SMS ? TxnSource.EMAIL : TxnSource.SMS;
        java.time.Duration window = timed ? java.time.Duration.ofMinutes(20) : java.time.Duration.ofHours(24);
        List<Txn> candidates = txns.findCrossChannelTwins(t.getAccount(), t.getDirection(), t.getAmount(), other,
                t.getOccurredAt().minus(window), t.getOccurredAt().plus(window)).stream()
                .filter(x -> x.getTwinRawEmail() == null || x.getTwinRawEmail().getId().equals(email.getId()))
                .filter(x -> reference == null || x.getReferenceNo() == null || sameRef(reference, x.getReferenceNo()))
                .toList();
        Comparator<Txn> byGap = Comparator.comparing(x -> java.time.Duration.between(x.getOccurredAt(), t.getOccurredAt()).abs());
        Comparator<Txn> sameRefFirst = Comparator.comparing(x -> !(reference != null && x.getReferenceNo() != null));
        return candidates.stream().min(sameRefFirst.thenComparing(byGap));
    }

    /** Forget which transaction this alert was matched to (before it's read again, e.g. after a correction). */
    @Transactional
    public void unpair(RawEmail email) {
        for (Txn t : txns.findByTwinRawEmail(email)) t.setTwinRawEmail(null);
    }

    /** Banks print the same reference with or without leading zeros or a prefix; compare the digits' tail. */
    static boolean sameRef(String a, String b) {
        String x = a.replaceAll("\\D", ""), y = b.replaceAll("\\D", "");
        if (x.length() < 6 || y.length() < 6) return a.equalsIgnoreCase(b);
        return x.endsWith(y) || y.endsWith(x);
    }

    /** A line from a statement I chose to add. Time of day is guessed (noon) when the statement only has a date. */
    @Transactional
    public Txn createFromStatement(Account account, Direction direction, BigDecimal amount, LocalDate date, LocalTime time,
                                   String counterparty, String reference, String description, boolean cardPayment) {
        Txn t = new Txn();
        t.setAccount(account);
        t.setDirection(direction);
        t.setAmount(Money.r2(amount));
        t.setOccurredAt((time == null ? date.atTime(12, 0) : date.atTime(time)).atZone(Money.INDIA).toInstant());
        t.setTimeEstimated(time == null);
        String payee = aliases.apply(counterparty);
        t.setCounterparty(payee);
        t.setMerchantKey(Names.merchantKey(payee));
        t.setReferenceNo(reference);
        t.setDescription(description == null || description.length() <= 500 ? description : description.substring(0, 500));
        t.setSource(TxnSource.STATEMENT);
        txns.save(t);
        transfers.detect(t, cardPayment);
        if (!t.isTransfer()) categorizer.categorize(t);
        txns.flush();
        if (!t.isTransfer()) splits.suggestSettlement(t);
        return t;
    }

    /** Transactions recorded from one stored alert. */
    public List<Txn> forMessage(RawEmail message) {
        return txns.findByRawEmail(message);
    }

    private void applyAmount(Txn t, BigDecimal amount, String currency) {
        if (currency == null || currency.equals("INR")) {
            t.setAmount(Money.r2(amount));
            return;
        }
        t.setOriginalAmount(Money.r2(amount));
        t.setOriginalCurrency(currency);
        t.setAmountEstimated(true);
        BigDecimal rate = prices.inrRate(currency).orElse(null);
        // Without a rate keep the foreign figure and flag it; I can correct it from the statement later.
        t.setAmount(Money.r2(rate == null ? amount : amount.multiply(rate)));
    }

    private void afterCreate(Txn t, boolean cardPaymentHint) {
        transfers.detect(t, cardPaymentHint);
        categorizer.categorize(t);
        txns.flush();
        balances.verifyReportedBalance(t);
        splits.suggestSettlement(t);
    }

    public record ManualTxn(Long accountId, Direction direction, BigDecimal amount, String currency, Instant occurredAt,
                            String counterparty, String description, Long categoryId, List<String> tags, String notes,
                            Boolean transfer) {}

    @Transactional
    public Txn createManual(ManualTxn req, Account account) {
        if (req.amount() == null || req.amount().signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
        Txn t = new Txn();
        t.setAccount(account);
        t.setDirection(req.direction());
        t.setOccurredAt(req.occurredAt() == null ? Instant.now() : req.occurredAt());
        applyAmount(t, req.amount(), req.currency());
        t.setCounterparty(blankToNull(req.counterparty()));
        t.setMerchantKey(Names.merchantKey(req.counterparty()));
        t.setDescription(blankToNull(req.description()));
        t.setNotes(blankToNull(req.notes()));
        t.setSource(TxnSource.MANUAL);
        t.setTransfer(Boolean.TRUE.equals(req.transfer()));
        if (req.categoryId() != null) t.setCategory(categories.getReferenceById(req.categoryId()));
        t.setTags(resolveTags(req.tags()));
        txns.save(t);
        if (t.getCategory() == null) categorizer.categorize(t);
        if (!t.isTransfer()) splits.suggestSettlement(t);
        return t;
    }

    public record TxnUpdate(Long categoryId, Boolean clearCategory, List<String> tags, String notes, String counterparty,
                            Boolean transfer, BigDecimal amount, Instant occurredAt, Boolean learn) {}

    /** @param renamedCount other transactions renamed because a payee rename was learned */
    public record UpdateResult(Txn txn, int similarCount, int renamedCount) {}

    @Transactional
    public UpdateResult update(Long id, TxnUpdate u) {
        Txn t = get(id);
        int similar = 0;
        int renamed = 0;
        if (u.counterparty() != null) {
            String old = t.getCounterparty();
            t.setCounterparty(blankToNull(u.counterparty()));
            t.setMerchantKey(Names.merchantKey(u.counterparty()));
            // A rename is a correction: remember it and fix the same payee everywhere else.
            if (old != null && t.getCounterparty() != null && !old.equals(t.getCounterparty())) {
                renamed = aliases.learn(t, old, t.getCounterparty());
                if (t.getCategory() == null && !t.isTransfer()) categorizer.categorize(t);
            }
        }
        if (u.amount() != null) {
            if (u.amount().signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
            t.setAmount(Money.r2(u.amount()));
            t.setAmountEstimated(false);
        }
        if (u.occurredAt() != null) t.setOccurredAt(u.occurredAt());
        if (u.notes() != null) t.setNotes(blankToNull(u.notes()));
        if (u.tags() != null) t.setTags(resolveTags(u.tags()));
        if (u.transfer() != null) {
            if (!u.transfer() && t.getLinkedTxn() != null) transfers.unlink(t);
            t.setTransfer(u.transfer());
            if (u.transfer()) t.setCategory(null);
        }
        if (Boolean.TRUE.equals(u.clearCategory())) {
            t.setCategory(null);
        } else if (u.categoryId() != null) {
            Category c = categories.findById(u.categoryId())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown category"));
            t.setCategory(c);
            t.setTransfer(false);
            if (!Boolean.FALSE.equals(u.learn())) similar = categorizer.learn(t, c);
        }
        return new UpdateResult(t, similar, renamed);
    }

    @Transactional
    public void delete(Long id) {
        Txn t = get(id);
        if (t.getLinkedTxn() != null) transfers.unlink(t);
        txns.delete(t);
    }

    Set<Tag> resolveTags(List<String> names) {
        Set<Tag> out = new LinkedHashSet<>();
        if (names == null) return out;
        for (String raw : names) {
            String n = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (n.isEmpty()) continue;
            out.add(tags.findByNameIgnoreCase(n).orElseGet(() -> {
                Tag tag = new Tag();
                tag.setName(n);
                return tags.save(tag);
            }));
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public record Filter(Long accountId, Long categoryId, Boolean uncategorized, String tag, String q,
                         Instant from, Instant to, boolean includeTransfers, Direction direction,
                         BigDecimal minAmount, BigDecimal maxAmount) {
        public Filter(Long accountId, Long categoryId, Boolean uncategorized, String tag, String q,
                      Instant from, Instant to, boolean includeTransfers, Direction direction) {
            this(accountId, categoryId, uncategorized, tag, q, from, to, includeTransfers, direction, null, null);
        }
    }

    public Page<Txn> search(Filter f, Pageable pageable) {
        Specification<Txn> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (f.accountId() != null) ps.add(cb.equal(root.get("account").get("id"), f.accountId()));
            if (Boolean.TRUE.equals(f.uncategorized())) {
                ps.add(cb.isNull(root.get("category")));
                ps.add(cb.isFalse(root.get("transfer")));
            } else if (f.categoryId() != null) {
                ps.add(cb.equal(root.get("category").get("id"), f.categoryId()));
            }
            if (!f.includeTransfers()) ps.add(cb.isFalse(root.get("transfer")));
            if (f.direction() != null) ps.add(cb.equal(root.get("direction"), f.direction()));
            if (f.from() != null) ps.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), f.from()));
            if (f.to() != null) ps.add(cb.lessThan(root.get("occurredAt"), f.to()));
            if (f.tag() != null && !f.tag().isBlank()) {
                var tagJoin = root.join("tags", JoinType.INNER);
                ps.add(cb.equal(cb.lower(tagJoin.get("name")), f.tag().toLowerCase(Locale.ROOT)));
            }
            if (f.minAmount() != null) ps.add(cb.greaterThanOrEqualTo(root.get("amount"), f.minAmount()));
            if (f.maxAmount() != null) ps.add(cb.lessThanOrEqualTo(root.get("amount"), f.maxAmount()));
            if (f.q() != null && !f.q().isBlank()) {
                String like = "%" + f.q().toLowerCase(Locale.ROOT) + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(cb.coalesce(root.get("counterparty"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("notes"), "")), like),
                        cb.like(cb.coalesce(root.get("referenceNo"), ""), like)));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return txns.findAll(spec, pageable);
    }
}
