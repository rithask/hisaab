package com.hisaab.people.service;

import com.hisaab.people.dto.PersonBalanceResponse;
import com.hisaab.people.dto.SplitParticipantRequest;
import com.hisaab.people.model.SettlementDirection;
import com.hisaab.people.model.SettlementStatus;
import com.hisaab.people.model.SplitMethod;
import com.hisaab.common.util.Audit;
import com.hisaab.common.util.Money;
import com.hisaab.common.util.Names;
import com.hisaab.people.model.Person;
import com.hisaab.people.model.Settlement;
import com.hisaab.people.model.SplitShare;
import com.hisaab.people.repository.PersonRepository;
import com.hisaab.people.repository.SettlementRepository;
import com.hisaab.people.repository.SplitShareRepository;
import com.hisaab.transactions.model.Transaction;
import com.hisaab.transactions.repository.TransactionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hisaab.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SplitService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SplitService.class);
    private final SplitShareRepository shares;
    private final SettlementRepository settlements;
    private final PersonRepository people;
    private final TransactionRepository txns;
    private final ApplicationEventPublisher events;

    public SplitService(SplitShareRepository shares, SettlementRepository settlements, PersonRepository people, TransactionRepository txns,
                        ApplicationEventPublisher events) {
        this.shares = shares;
        this.settlements = settlements;
        this.people = people;
        this.txns = txns;
        this.events = events;
    }

    /**
     * Replace the split on a transaction I paid for.
     * EQUAL: value ignored; the bill is divided among participants (+ me if includeMe), paise remainder stays with me.
     * EXACT: value = amount that person owes. PERCENT: value = percent of the bill.
     */
    @Transactional
    public List<SplitShare> setSplit(Transaction txn, SplitMethod method, boolean includeMe, List<SplitParticipantRequest> participants) {
        // a bill I split isn't me paying someone back: drop a guess that it was
        settlements.findByTxn(txn).stream().filter(s -> s.getStatus() == SettlementStatus.SUGGESTED).forEach(settlements::delete);
        shares.deleteByTxn(txn);
        shares.flush();
        if (participants == null || participants.isEmpty()) {
            events.publishEvent(new BalancesChangedEvent("split removed"));
            return List.of();
        }
        BigDecimal total = txn.getAmount();
        List<SplitShare> out = new ArrayList<>();

        switch (method) {
            case EQUAL -> {
                int n = participants.size() + (includeMe ? 1 : 0);
                BigDecimal each = total.divide(BigDecimal.valueOf(n), 2, RoundingMode.DOWN);
                BigDecimal remainder = total.subtract(each.multiply(BigDecimal.valueOf(n)));
                for (int i = 0; i < participants.size(); i++) {
                    BigDecimal amt = (!includeMe && i == 0) ? each.add(remainder) : each;
                    out.add(share(txn, participants.get(i).personId(), method, null, amt));
                }
            }
            case EXACT -> {
                for (SplitParticipantRequest p : participants) {
                    out.add(share(txn, p.personId(), method, p.value(), Money.r2(p.value())));
                }
            }
            case PERCENT -> {
                BigDecimal pctSum = participants.stream().map(SplitParticipantRequest::value).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (pctSum.compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new BadRequestException("Percentages add up to more than 100%");
                }
                for (SplitParticipantRequest p : participants) {
                    BigDecimal amt = total.multiply(p.value()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    out.add(share(txn, p.personId(), method, p.value(), amt));
                }
            }
        }
        BigDecimal sum = out.stream().map(SplitShare::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(total) > 0) {
            throw new BadRequestException("Shares (" + sum + ") exceed the transaction amount (" + total + ")");
        }
        for (SplitShare s : out) {
            if (s.getAmount().signum() <= 0) throw new BadRequestException("Each share must be more than zero");
        }
        List<SplitShare> saved = shares.saveAll(out);
        events.publishEvent(new BalancesChangedEvent("split changed")); // a repayment may have arrived before I split it
        return saved;
    }

    private SplitShare share(Transaction txn, Long personId, SplitMethod method, BigDecimal input, BigDecimal amount) {
        Person person = people.findById(personId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown person " + personId));
        SplitShare s = new SplitShare();
        s.setTxn(txn);
        s.setPerson(person);
        s.setMethod(method);
        s.setInput(input);
        s.setAmount(amount);
        return s;
    }

    public PersonBalanceResponse balance(Person person) {
        BigDecimal owed = shares.totalOwed(person), paid = settlements.totalPaid(person);
        BigDecimal forMe = txns.totalPaidBy(person), back = settlements.totalPaidBack(person);
        return new PersonBalanceResponse(owed, paid, forMe, back, owed.subtract(forMe).subtract(paid).add(back));
    }

    /** {@link #balance} for everyone, with four grouped queries instead of four per person; keyed by person id. */
    public Map<Long, PersonBalanceResponse> balances(List<Person> people) {
        var owed = byPerson(shares.totalOwedByPerson());
        var forMe = byPerson(txns.totalPaidByPerson());
        var paid = new HashMap<Long, BigDecimal>();
        var back = new HashMap<Long, BigDecimal>();
        for (Object[] row : settlements.totalsByPerson(SettlementStatus.CONFIRMED)) {
            (row[1] == SettlementDirection.IN ? paid : back).put((Long) row[0], (BigDecimal) row[2]);
        }
        Map<Long, PersonBalanceResponse> out = new HashMap<>();
        for (Person p : people) {
            BigDecimal o = owed.getOrDefault(p.getId(), BigDecimal.ZERO), pd = paid.getOrDefault(p.getId(), BigDecimal.ZERO);
            BigDecimal f = forMe.getOrDefault(p.getId(), BigDecimal.ZERO), b = back.getOrDefault(p.getId(), BigDecimal.ZERO);
            out.put(p.getId(), new PersonBalanceResponse(o, pd, f, b, o.subtract(f).subtract(pd).add(b)));
        }
        return out;
    }

    private static Map<Long, BigDecimal> byPerson(List<Object[]> rows) {
        Map<Long, BigDecimal> m = new HashMap<>();
        for (Object[] row : rows) m.put((Long) row[0], (BigDecimal) row[1]);
        return m;
    }

    /** What they owe me: positive, they owe me; negative, I owe them. */
    public BigDecimal outstanding(Person p) {
        return balance(p).outstanding();
    }

    /**
     * What's still open with someone once the repayments waiting for my confirmation go through: a suggestion
     * shouldn't be made twice for the same money.
     */
    private BigDecimal open(Person p) {
        return outstanding(p)
                .subtract(settlements.total(p, SettlementStatus.SUGGESTED, SettlementDirection.IN))
                .add(settlements.total(p, SettlementStatus.SUGGESTED, SettlementDirection.OUT));
    }

    /** The money's sender or recipient is this person, by their UPI name or their name. */
    public static boolean named(Person p, String counterparty) {
        return Names.samePerson(p.getUpiName() != null ? p.getUpiName() : p.getName(), counterparty)
                || Names.samePerson(p.getName(), counterparty);
    }

    /**
     * Guess whether money is someone settling up. Only a suggestion: I confirm it.
     * Money in: someone who owes me paying me back. Money out: me paying back someone I owe.
     * A name match (UPI name or name) wins, whatever the amount; otherwise the amount must be exactly what's open
     * with one person. Money already linked, or turned down before, is left alone.
     */
    @Transactional
    public void suggestSettlement(Transaction transaction) {
        if (transaction.isTransfer() || transaction.isPaidByPerson() || transaction.getAccount() == null) return;
        if (!settlements.findByTxn(transaction).isEmpty()) return;
        if (transaction.getId() != null && shares.totalForTxn(transaction).signum() > 0) return; // a bill I split isn't me paying someone back
        boolean in = !transaction.isDebit();
        // what's open, from my side of this money: what they owe me (in) or what I owe them (out)
        Map<Person, BigDecimal> due = new LinkedHashMap<>();
        for (Person p : people.findAll()) {
            BigDecimal v = in ? open(p) : open(p).negate();
            if (v.signum() > 0) due.put(p, v);
        }
        if (due.isEmpty()) return;

        List<Person> pick = due.keySet().stream().filter(p -> named(p, transaction.getCounterparty())).toList();
        if (pick.isEmpty()) pick = due.keySet().stream().filter(p -> due.get(p).compareTo(transaction.getAmount()) == 0).toList();
        if (pick.size() != 1) return;
        Person p = pick.get(0);
        Settlement s = new Settlement();
        s.setPerson(p);
        s.setTxn(transaction);
        s.setAmount(transaction.getAmount().min(due.get(p)));
        s.setSettledAt(transaction.getOccurredAt());
        s.setStatus(SettlementStatus.SUGGESTED);
        s.setDirection(in ? SettlementDirection.IN : SettlementDirection.OUT);
        s.setNote("Auto-matched from " + (transaction.getCounterparty() == null ? (in ? "incoming payment" : "payment") : transaction.getCounterparty()));
        settlements.save(s);
        log.info("Repayment suggested: transaction {} as {} person {} (by {})", transaction.getId(), in ? "paid back by" : "paying back",
                p.getId(), named(p, transaction.getCounterparty()) ? "name" : "exact amount");
    }

    /** How far back to look again for money that settles up, when something about a balance changes. */
    static final Duration LOOK_BACK = Duration.ofDays(30);

    /**
     * Look again at recent money nobody has been linked to: after a split, a "they paid" entry or a person's
     * UPI name changes, a payment that arrived earlier may now be someone settling up.
     */
    @Transactional
    public void rescan() {
        for (Transaction t : txns.findUnsettledSince(Instant.now().minus(LOOK_BACK))) suggestSettlement(t);
    }

    @EventListener
    void onBalancesChanged(BalancesChangedEvent balancesChangedEvent) {
        rescan();
    }

    /** Add or change a person; a payment from them (or to their new UPI name) may already be here. */
    @Transactional
    public Person savePerson(Person p) {
        Person saved = people.save(p);
        events.publishEvent(new BalancesChangedEvent("person saved"));
        return saved;
    }

    /** Remove a repayment; the money it was linked to is free to match something else. */
    @Transactional
    public void removeSettlement(Long id) {
        settlements.deleteById(id);
        Audit.log("Repayment removed id={}", id);
        settlements.flush();
        events.publishEvent(new BalancesChangedEvent("repayment removed"));
    }

    /**
     * Record a repayment I made or got, optionally linked to the money in or out that carried it. Linked money
     * stops counting as income (or spending), and suggestions on it are replaced. If the sender's name differs
     * from the person's and no UPI name is saved yet, it's remembered, so the next one matches by name.
     *
     * @return the UPI name remembered for the person, if one was
     */
    public record Recorded(Settlement settlement, String rememberedName) {}

    @Transactional
    public Recorded record(Person p, SettlementDirection direction, BigDecimal amount, Instant at, Transaction txn, String note) {
        direction = direction == null ? SettlementDirection.IN : direction;
        if (amount == null || amount.signum() <= 0) throw new BadRequestException("Amount must be positive");
        if (txn != null) {
            if (txn.isTransfer() || txn.isPaidByPerson() || txn.getAccount() == null) {
                throw new BadRequestException("Only money in or out of your accounts can be a repayment");
            }
            if (txn.isDebit() != (direction == SettlementDirection.OUT)) {
                throw new BadRequestException(txn.isDebit() ? "Money you paid out can only be you paying someone back"
                        : "Money you received can only be someone paying you back");
            }
            // a suggestion or a "no" on this money gives way to what I chose
            settlements.findByTxn(txn).stream().filter(x -> x.getStatus() != SettlementStatus.CONFIRMED).forEach(settlements::delete);
            settlements.flush();
            BigDecimal linked = settlements.findByTxn(txn).stream().map(Settlement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (linked.add(amount).compareTo(txn.getAmount()) > 0) {
                throw new BadRequestException("That's more than the " + txn.getAmount().toPlainString() + " this payment was");
            }
            if (at == null) at = txn.getOccurredAt();
        }
        Settlement s = new Settlement();
        s.setDirection(direction == null ? SettlementDirection.IN : direction);
        s.setPerson(p);
        s.setAmount(Money.r2(amount));
        s.setSettledAt(at == null ? Instant.now() : at);
        s.setTxn(txn);
        s.setNote(note);
        s.setStatus(SettlementStatus.CONFIRMED);
        Settlement saved = settlements.save(s);
        log.info("Repayment recorded: person {} {} {}", p.getId(), s.getDirection() == SettlementDirection.OUT ? "paid back by me" : "paid me back",
                txn == null ? "(cash or another account)" : "with transaction " + txn.getId());
        return new Recorded(saved, txn == null ? null : remember(p, txn));
    }

    /** I confirmed a suggested repayment: as for {@link #record}, remember the name it came from. */
    @Transactional
    public String confirm(Settlement s, BigDecimal amount) {
        if (amount != null && amount.signum() > 0) s.setAmount(Money.r2(amount));
        s.setStatus(SettlementStatus.CONFIRMED);
        log.info("Repayment confirmed: suggestion {} for person {}", s.getId(), s.getPerson().getId());
        return s.getTxn() == null ? null : remember(s.getPerson(), s.getTxn());
    }

    private static String remember(Person p, Transaction t) {
        String name = t.getCounterparty();
        if (p.getUpiName() != null || name == null || name.isBlank() || named(p, name)) return null;
        p.setUpiName(name.length() > 200 ? name.substring(0, 200) : name.trim());
        log.info("Person {}'s UPI name remembered from transaction {}, so their payments match by name", p.getId(), t.getId());
        return p.getUpiName();
    }
}
