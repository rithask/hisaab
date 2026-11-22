package com.hisaab.transactions.service;

import com.hisaab.transactions.dto.NewTransactionRequest;
import com.hisaab.transactions.dto.PaidByRequest;
import com.hisaab.transactions.dto.TransactionUpdateRequest;
import com.hisaab.common.model.Direction;
import com.hisaab.messages.model.Channel;
import com.hisaab.people.model.SettlementStatus;
import com.hisaab.transactions.model.TransactionSource;
import com.hisaab.accounts.model.Account;
import com.hisaab.accounts.service.BalanceService;
import com.hisaab.categories.model.Category;
import com.hisaab.categories.repository.CategoryRepository;
import com.hisaab.categories.service.CategorizationService;
import com.hisaab.categories.service.PayeeAliasService;
import com.hisaab.common.util.Audit;
import com.hisaab.common.util.Money;
import com.hisaab.common.util.Names;
import com.hisaab.investments.service.PriceService;
import com.hisaab.messages.model.InboxMessage;
import com.hisaab.messages.parser.ParsedTransaction;
import com.hisaab.people.model.Person;
import com.hisaab.people.model.Settlement;
import com.hisaab.people.model.SplitShare;
import com.hisaab.people.repository.PersonRepository;
import com.hisaab.people.service.BalancesChangedEvent;
import com.hisaab.people.service.SplitService;
import com.hisaab.transactions.model.Tag;
import com.hisaab.transactions.model.Transaction;
import com.hisaab.transactions.repository.TagRepository;
import com.hisaab.transactions.repository.TransactionRepository;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hisaab.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

import static com.hisaab.common.exception.NotFound.notFound;

@Service
public class TransactionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TransactionService.class);
    private final TransactionRepository txns;
    private final CategoryRepository categories;
    private final TagRepository tags;
    private final CategorizationService categorizer;
    private final TransferService transfers;
    private final BalanceService balances;
    private final SplitService splits;
    private final PriceService prices;
    private final PayeeAliasService aliases;
    private final PersonRepository people;

    private final ApplicationEventPublisher events;

    public TransactionService(TransactionRepository txns, CategoryRepository categories, TagRepository tags, CategorizationService categorizer,
                              TransferService transfers, BalanceService balances, SplitService splits,
                              PriceService prices, PayeeAliasService aliases, PersonRepository people,
                              ApplicationEventPublisher events) {
        this.events = events;
        this.aliases = aliases;
        this.people = people;
        this.txns = txns;
        this.categories = categories;
        this.tags = tags;
        this.categorizer = categorizer;
        this.transfers = transfers;
        this.balances = balances;
        this.splits = splits;
        this.prices = prices;
    }

    /** Every tag in use, by name. */
    public List<String> tagNames() {
        return tags.findAllByOrderByNameAsc().stream().map(Tag::getName).toList();
    }

    public Transaction get(Long id) {
        return txns.findById(id).orElseThrow(notFound("Transaction " + id));
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
    public Optional<Transaction> createFromEmail(ParsedTransaction p, Account account, InboxMessage email) {
        Instant at = occurredAt(p.date(), p.time(), email.getReceivedAt());
        boolean timeGuessed = p.time() == null;
        TransactionSource source = email.getChannel() == Channel.SMS ? TransactionSource.SMS : TransactionSource.EMAIL;
        Transaction t = new Transaction();
        t.setAccount(account);
        t.setDirection(p.direction());
        t.setOccurredAt(at);
        applyAmount(t, p.amount(), p.currency());
        if (p.reference() != null) {
            Optional<Transaction> same = txns.findFirstByAccountAndReferenceNoAndAmountAndDirection(account, p.reference(), t.getAmount(), p.direction());
            if (same.isPresent()) {
                // the same payment's alert from the other channel: it's now paired and can't absorb another one
                if (same.get().getSource() != source && same.get().getTwinRawEmail() == null) same.get().setTwinRawEmail(email);
                log.info("Alert {} not recorded: transaction {} has the same reference", email.getId(), same.get().getId());
                return Optional.empty();
            }
        }
        Optional<Transaction> twin = crossChannelTwin(t, source, p.time() != null, p.reference(), email);
        if (twin.isPresent()) {
            Transaction existing = twin.get();
            existing.setTwinRawEmail(email);
            if (existing.getCounterparty() == null && p.counterparty() != null) {
                existing.setCounterparty(aliases.apply(p.counterparty()));
                existing.setMerchantKey(Names.merchantKey(existing.getCounterparty()));
            }
            if (existing.getReferenceNo() == null) existing.setReferenceNo(p.reference());
            if (existing.getReportedBalance() == null) existing.setReportedBalance(p.balance());
            log.info("Alert {} not recorded: it's transaction {}'s alert from the other channel ({})", email.getId(), existing.getId(), existing.getSource());
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
    private Optional<Transaction> crossChannelTwin(Transaction t, TransactionSource source, boolean timed, String reference, InboxMessage email) {
        TransactionSource other = source == TransactionSource.SMS ? TransactionSource.EMAIL : TransactionSource.SMS;
        Duration window = timed ? Duration.ofMinutes(20) : Duration.ofHours(24);
        List<Transaction> candidates = txns.findCrossChannelTwins(t.getAccount(), t.getDirection(), t.getAmount(), other,
                t.getOccurredAt().minus(window), t.getOccurredAt().plus(window)).stream()
                .filter(x -> x.getTwinRawEmail() == null || x.getTwinRawEmail().getId().equals(email.getId()))
                .filter(x -> reference == null || x.getReferenceNo() == null || sameRef(reference, x.getReferenceNo()))
                .toList();
        Comparator<Transaction> byGap = Comparator.comparing(x -> Duration.between(x.getOccurredAt(), t.getOccurredAt()).abs());
        Comparator<Transaction> sameRefFirst = Comparator.comparing(x -> !(reference != null && x.getReferenceNo() != null));
        return candidates.stream().min(sameRefFirst.thenComparing(byGap));
    }

    /**
     * A transaction recorded from an alert that was read wrong, fixed with what the alert really said. What I added
     * to it (category, notes, tags, split) stays; the category is cleared only if the direction changed so it no
     * longer fits, and transfer matching runs again when the direction or account changed.
     */
    @Transactional
    public Transaction correct(Transaction t, ParsedTransaction p, Account account) {
        boolean moved = t.getDirection() != p.direction() || !t.getAccount().getId().equals(account.getId());
        if (moved && t.getLinkedTxn() != null) transfers.unlink(t);
        t.setAccount(account);
        t.setDirection(p.direction());
        applyAmount(t, p.amount(), p.currency());
        if (p.counterparty() != null) {
            t.setCounterparty(aliases.apply(p.counterparty()));
            t.setMerchantKey(Names.merchantKey(t.getCounterparty()));
        }
        if (p.reference() != null) t.setReferenceNo(p.reference());
        t.setReportedBalance(p.balance());
        if (t.getCategory() != null && t.getCategory().getKind() != CategorizationService.kindFor(t.getDirection())) t.setCategory(null);
        if (moved) transfers.detect(t, p.cardPayment());
        if (t.getCategory() == null && !t.isTransfer()) categorizer.categorize(t);
        txns.flush();
        balances.verifyReportedBalance(t);
        return t;
    }

    /** Transactions this alert was matched to as the other channel's copy (normally one). */
    public List<Transaction> pairedWith(InboxMessage email) {
        return txns.findByTwinRawEmail(email);
    }

    /** Forget which transaction this alert was matched to (before it's read again, e.g. after a correction). */
    @Transactional
    public void unpair(InboxMessage email) {
        for (Transaction t : txns.findByTwinRawEmail(email)) t.setTwinRawEmail(null);
    }

    /** Banks print the same reference with or without leading zeros or a prefix; compare the digits' tail. */
    public static boolean sameRef(String a, String b) {
        String x = a.replaceAll("\\D", ""), y = b.replaceAll("\\D", "");
        if (x.length() < 6 || y.length() < 6) return a.equalsIgnoreCase(b);
        return x.endsWith(y) || y.endsWith(x);
    }

    /** A line from a statement I chose to add. Time of day is guessed (noon) when the statement only has a date. */
    @Transactional
    public Transaction createFromStatement(Account account, Direction direction, BigDecimal amount, LocalDate date, LocalTime time,
                                           String counterparty, String reference, String description, boolean cardPayment) {
        Transaction t = new Transaction();
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
        t.setSource(TransactionSource.STATEMENT);
        txns.save(t);
        transfers.detect(t, cardPayment);
        if (!t.isTransfer()) categorizer.categorize(t);
        txns.flush();
        if (!t.isTransfer()) splits.suggestSettlement(t);
        return t;
    }

    /** Transactions recorded from one stored alert. */
    public List<Transaction> forMessage(InboxMessage message) {
        return txns.findByRawEmail(message);
    }

    private void applyAmount(Transaction t, BigDecimal amount, String currency) {
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

    /**
     * What follows saving a new transaction, as direct calls rather than events on purpose: the order matters (a
     * transfer is never a repayment, so transfers are found first) and each source does a different subset (a
     * statement line has no reported balance; a manual entry has no card-payment hint).
     */
    private void afterCreate(Transaction t, boolean cardPaymentHint) {
        transfers.detect(t, cardPaymentHint);
        categorizer.categorize(t);
        txns.flush();
        balances.verifyReportedBalance(t);
        splits.suggestSettlement(t);
    }

    @Transactional
    public Transaction createManual(NewTransactionRequest req, Account account) {
        if (req.amount() == null || req.amount().signum() <= 0) throw new BadRequestException("Amount must be positive");
        Transaction t = new Transaction();
        t.setAccount(account);
        t.setDirection(req.direction());
        t.setOccurredAt(req.occurredAt() == null ? Instant.now() : req.occurredAt());
        applyAmount(t, req.amount(), req.currency());
        t.setCounterparty(blankToNull(req.counterparty()));
        t.setMerchantKey(Names.merchantKey(req.counterparty()));
        t.setDescription(blankToNull(req.description()));
        t.setNotes(blankToNull(req.notes()));
        t.setSource(TransactionSource.MANUAL);
        t.setTransfer(Boolean.TRUE.equals(req.transfer()));
        if (req.categoryId() != null) t.setCategory(categories.getReferenceById(req.categoryId()));
        t.setTags(resolveTags(req.tags()));
        txns.save(t);
        if (t.getCategory() == null) categorizer.categorize(t);
        if (!t.isTransfer()) splits.suggestSettlement(t);
        return t;
    }

    /**
     * Records my share of something a friend paid for. It's not a bank transaction: it has no account, nothing
     * is learned from it (category, payee) and nothing is matched to it (duplicates, transfers, repayments).
     */
    @Transactional
    public Transaction createPaidBy(PaidByRequest in) {
        Transaction t = new Transaction();
        t.setDirection(Direction.DEBIT);
        t.setSource(TransactionSource.PERSON);
        applyPaidBy(t, in);
        if (in.tags() != null) t.setTags(resolveTags(in.tags()));
        txns.save(t);
        events.publishEvent(new BalancesChangedEvent("they paid for me")); // they may already have sent the difference
        return t;
    }

    @Transactional
    public Transaction updatePaidBy(Long id, PaidByRequest in) {
        Transaction t = get(id);
        if (!t.isPaidByPerson()) throw new BadRequestException("This wasn't paid by someone else");
        applyPaidBy(t, in);
        if (in.tags() != null) t.setTags(resolveTags(in.tags()));
        events.publishEvent(new BalancesChangedEvent("they paid for me"));
        return t;
    }

    private void applyPaidBy(Transaction t, PaidByRequest in) {
        Person who = in.personId() == null ? null : people.findById(in.personId()).orElse(null);
        if (who == null) throw new BadRequestException("Choose who paid");
        if (in.total() == null || in.total().signum() <= 0) throw new BadRequestException("Enter what they paid");
        if (in.share() == null || in.share().signum() <= 0) throw new BadRequestException("Your share must be more than zero");
        BigDecimal total = Money.r2(in.total()), share = Money.r2(in.share());
        if (share.compareTo(total) > 0) throw new BadRequestException("Your share can't be more than they paid");
        t.setPaidBy(who);
        t.setPaidTotal(total);
        t.setAmount(share);
        t.setAccount(null);
        t.setOccurredAt(in.occurredAt() == null ? Instant.now() : in.occurredAt());
        t.setCounterparty(blankToNull(in.where()));
        t.setMerchantKey(null); // never matched to bank payments, so never learned from
        t.setNotes(blankToNull(in.notes()));
        if (in.categoryId() == null) t.setCategory(null);
        else t.setCategory(categories.findById(in.categoryId()).orElseThrow(() -> new IllegalArgumentException("Unknown category")));
        t.setCategorySuggested(false);
    }

    /**
     * @param renamedCount   other transactions renamed because a payee rename was learned
     * @param learnedWording the category was remembered for messages worded like this one (it has no payee)
     */
    public record UpdateResult(Transaction txn, int similarCount, int renamedCount, boolean learnedWording) {}

    @Transactional
    public UpdateResult update(Long id, TransactionUpdateRequest u) {
        Transaction t = get(id);
        if (t.isPaidByPerson()) return new UpdateResult(updateOwn(t, u), 0, 0, false);
        int similar = 0;
        int renamed = 0;
        boolean learnedWording = false;
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
            if (u.amount().signum() <= 0) throw new BadRequestException("Amount must be positive");
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
            t.setCategorySuggested(false);
        } else if (u.categoryId() != null) {
            Category c = categories.findById(u.categoryId())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown category"));
            t.setCategory(c);
            t.setCategorySuggested(false);
            t.setTransfer(false);
            if (!Boolean.FALSE.equals(u.learn())) {
                similar = categorizer.learn(t, c);
                learnedWording = categorizer.learnsByWording(t);
            }
        }
        return new UpdateResult(t, similar, renamed, learnedWording);
    }

    /** Edits to my share of something a friend paid for: plain changes, nothing learned or matched. */
    private Transaction updateOwn(Transaction t, TransactionUpdateRequest u) {
        if (Boolean.TRUE.equals(u.transfer())) throw new BadRequestException("Someone else paid for this, so it isn't a transfer");
        if (u.counterparty() != null) t.setCounterparty(blankToNull(u.counterparty()));
        if (u.amount() != null) {
            if (u.amount().signum() <= 0) throw new BadRequestException("Amount must be positive");
            if (u.amount().compareTo(t.getPaidTotal()) > 0) throw new BadRequestException("Your share can't be more than they paid");
            t.setAmount(Money.r2(u.amount()));
        }
        if (u.occurredAt() != null) t.setOccurredAt(u.occurredAt());
        if (u.notes() != null) t.setNotes(blankToNull(u.notes()));
        if (u.tags() != null) t.setTags(resolveTags(u.tags()));
        if (Boolean.TRUE.equals(u.clearCategory())) t.setCategory(null);
        else if (u.categoryId() != null) {
            t.setCategory(categories.findById(u.categoryId()).orElseThrow(() -> new IllegalArgumentException("Unknown category")));
        }
        t.setCategorySuggested(false);
        return t;
    }

    @Transactional
    public void delete(Long id) {
        Transaction t = get(id);
        Audit.log("Transaction deleted id={} source={}", id, t.getSource());
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

    public Page<Transaction> search(Filter f, Pageable pageable) {
        Specification<Transaction> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (f.accountId() != null) ps.add(cb.equal(root.get("account").get("id"), f.accountId()));
            if (Boolean.TRUE.equals(f.uncategorized())) {
                ps.add(cb.isNull(root.get("category")));
                ps.add(cb.isFalse(root.get("transfer")));
                // Nothing of it is mine (money I lent, a repayment either way): it needs no category.
                var shared = query.subquery(BigDecimal.class);
                var share = shared.from(SplitShare.class);
                shared.select(cb.coalesce(cb.sum(share.<BigDecimal>get("amount")), BigDecimal.ZERO)).where(cb.equal(share.get("txn"), root));
                var repaid = query.subquery(BigDecimal.class);
                var st = repaid.from(Settlement.class);
                repaid.select(cb.coalesce(cb.sum(st.<BigDecimal>get("amount")), BigDecimal.ZERO))
                        .where(cb.equal(st.get("txn"), root), cb.equal(st.get("status"), SettlementStatus.CONFIRMED));
                ps.add(cb.gt(cb.diff(cb.diff(root.<BigDecimal>get("amount"), shared), repaid), BigDecimal.ZERO));
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
                        cb.like(cb.coalesce(root.get("referenceNo"), ""), like),
                        cb.like(cb.lower(cb.coalesce(root.join("paidBy", JoinType.LEFT).get("name"), "")), like)));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return txns.findAll(spec, pageable);
    }
}
