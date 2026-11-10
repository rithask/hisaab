package com.hisaab.service;

import com.hisaab.domain.Enums.SettlementStatus;
import com.hisaab.domain.Enums.SplitMethod;
import com.hisaab.domain.Person;
import com.hisaab.domain.Settlement;
import com.hisaab.domain.SplitShare;
import com.hisaab.domain.Txn;
import com.hisaab.repo.PersonRepo;
import com.hisaab.repo.SettlementRepo;
import com.hisaab.repo.SplitShareRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class SplitService {
    private final SplitShareRepo shares;
    private final SettlementRepo settlements;
    private final PersonRepo people;

    public SplitService(SplitShareRepo shares, SettlementRepo settlements, PersonRepo people) {
        this.shares = shares;
        this.settlements = settlements;
        this.people = people;
    }

    public record Participant(Long personId, BigDecimal value) {}

    /**
     * Replace the split on a transaction I paid for.
     * EQUAL: value ignored; the bill is divided among participants (+ me if includeMe), paise remainder stays with me.
     * EXACT: value = amount that person owes. PERCENT: value = percent of the bill.
     */
    @Transactional
    public List<SplitShare> setSplit(Txn txn, SplitMethod method, boolean includeMe, List<Participant> participants) {
        shares.deleteByTxn(txn);
        shares.flush();
        if (participants == null || participants.isEmpty()) return List.of();
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
                for (Participant p : participants) {
                    out.add(share(txn, p.personId(), method, p.value(), Money.r2(p.value())));
                }
            }
            case PERCENT -> {
                BigDecimal pctSum = participants.stream().map(Participant::value).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (pctSum.compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new IllegalArgumentException("Percentages add up to more than 100%");
                }
                for (Participant p : participants) {
                    BigDecimal amt = total.multiply(p.value()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    out.add(share(txn, p.personId(), method, p.value(), amt));
                }
            }
        }
        BigDecimal sum = out.stream().map(SplitShare::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(total) > 0) {
            throw new IllegalArgumentException("Shares (" + sum + ") exceed the transaction amount (" + total + ")");
        }
        for (SplitShare s : out) {
            if (s.getAmount().signum() <= 0) throw new IllegalArgumentException("Each share must be more than zero");
        }
        return shares.saveAll(out);
    }

    private SplitShare share(Txn txn, Long personId, SplitMethod method, BigDecimal input, BigDecimal amount) {
        Person p = people.findById(personId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown person " + personId));
        SplitShare s = new SplitShare();
        s.setTxn(txn);
        s.setPerson(p);
        s.setMethod(method);
        s.setInput(input);
        s.setAmount(amount);
        return s;
    }

    public BigDecimal outstanding(Person p) {
        return shares.totalOwed(p).subtract(settlements.totalPaid(p));
    }

    /**
     * When money comes in, guess whether it is someone paying me back. Only a suggestion: I confirm it.
     * Name match on the UPI name wins; otherwise an exact match on someone's outstanding amount.
     */
    @Transactional
    public void suggestSettlement(Txn credit) {
        if (credit.isTransfer() || credit.isDebit()) return;
        if (!settlements.findByTxn(credit).isEmpty()) return;
        List<Person> owing = people.findAll().stream().filter(p -> outstanding(p).signum() > 0).toList();
        if (owing.isEmpty()) return;

        List<Person> byName = owing.stream()
                .filter(p -> Names.samePerson(p.getUpiName() != null ? p.getUpiName() : p.getName(), credit.getCounterparty())
                        || Names.samePerson(p.getName(), credit.getCounterparty()))
                .toList();
        List<Person> pick = byName;
        if (pick.isEmpty()) {
            pick = owing.stream().filter(p -> outstanding(p).compareTo(credit.getAmount()) == 0).toList();
        }
        if (pick.size() != 1) return;
        Person p = pick.get(0);
        Settlement s = new Settlement();
        s.setPerson(p);
        s.setTxn(credit);
        s.setAmount(credit.getAmount().min(outstanding(p)));
        s.setSettledAt(credit.getOccurredAt());
        s.setStatus(SettlementStatus.SUGGESTED);
        s.setNote("Auto-matched from " + (credit.getCounterparty() == null ? "incoming payment" : credit.getCounterparty()));
        settlements.save(s);
    }

    @Transactional
    public Settlement record(Person p, BigDecimal amount, Instant at, Txn txn, String note) {
        Settlement s = new Settlement();
        s.setPerson(p);
        s.setAmount(Money.r2(amount));
        s.setSettledAt(at == null ? Instant.now() : at);
        s.setTxn(txn);
        s.setNote(note);
        s.setStatus(SettlementStatus.CONFIRMED);
        return settlements.save(s);
    }
}
