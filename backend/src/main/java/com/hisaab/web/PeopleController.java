package com.hisaab.web;

import com.hisaab.domain.Enums.SettlementDirection;
import com.hisaab.domain.Enums.SettlementStatus;
import com.hisaab.domain.Person;
import com.hisaab.domain.Settlement;
import com.hisaab.repo.PersonRepo;
import com.hisaab.repo.SettlementRepo;
import com.hisaab.repo.SplitShareRepo;
import com.hisaab.repo.TxnRepo;
import com.hisaab.service.SplitService;
import com.hisaab.web.Dtos.PersonDto;
import com.hisaab.web.Dtos.SettlementDto;
import com.hisaab.web.Dtos.TxnDto;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api")
public class PeopleController {
    private final PersonRepo people;
    private final SplitShareRepo shares;
    private final SettlementRepo settlements;
    private final SplitService splits;
    private final TxnRepo txns;

    public PeopleController(PersonRepo people, SplitShareRepo shares, SettlementRepo settlements, SplitService splits,
                            TxnRepo txns) {
        this.people = people;
        this.shares = shares;
        this.settlements = settlements;
        this.splits = splits;
        this.txns = txns;
    }

    PersonDto dto(Person p) {
        SplitService.Balance b = splits.balance(p);
        return new PersonDto(p.getId(), p.getName(), p.getUpiName(), p.getPhone(), p.getNotes(), b.owed(), b.paid(),
                b.paidForMe(), b.paidBack(), b.outstanding());
    }

    Person person(Long id) {
        return people.findById(id).orElseThrow(() -> new NoSuchElementException("Person not found"));
    }

    @GetMapping("/people")
    public List<PersonDto> list() {
        return people.findAllByOrderByNameAsc().stream().map(this::dto).toList();
    }

    public record PersonInput(String name, String upiName, String phone, String notes) {}

    @PostMapping("/people")
    @Transactional
    public PersonDto create(@RequestBody PersonInput in) {
        Person p = people.save(apply(new Person(), in));
        splits.rescan(); // a payment from them may already be here
        return dto(p);
    }

    @PutMapping("/people/{id}")
    @Transactional
    public PersonDto update(@PathVariable Long id, @RequestBody PersonInput in) {
        Person p = people.save(apply(person(id), in));
        splits.rescan(); // a new UPI name can match a payment that's already here
        return dto(p);
    }

    /**
     * Recent money that could be this person settling up, for picking it by hand: money in when they owe me,
     * money out when I owe them.
     */
    @GetMapping("/people/{id}/open-payments")
    @Transactional(readOnly = true)
    public List<TxnDto> openPayments(@PathVariable Long id, @RequestParam SettlementDirection direction) {
        Person p = person(id);
        var dir = direction == SettlementDirection.OUT ? com.hisaab.domain.Enums.Direction.DEBIT : com.hisaab.domain.Enums.Direction.CREDIT;
        BigDecimal due = splits.outstanding(p).abs();
        // their name first, then the amounts nearest to what's open, then the newest
        return txns.findOpenSince(Instant.now().minus(java.time.Duration.ofDays(60)), dir).stream()
                .sorted(java.util.Comparator.comparing((com.hisaab.domain.Txn t) -> !SplitService.named(p, t.getCounterparty()))
                        .thenComparing(t -> t.getAmount().subtract(due).abs())
                        .thenComparing(com.hisaab.domain.Txn::getOccurredAt, java.util.Comparator.reverseOrder()))
                .limit(30).map(t -> TxnDto.of(t, null)).toList();
    }

    @DeleteMapping("/people/{id}")
    public void delete(@PathVariable Long id) {
        Person p = person(id);
        // what they paid for me is part of my spending: don't let it vanish with them
        if (txns.existsByPaidBy(p)) {
            throw new IllegalStateException(p.getName() + " paid for things you recorded. Delete those first, or keep " + p.getName() + ".");
        }
        people.delete(p);
    }

    private static Person apply(Person p, PersonInput in) {
        if (in.name() == null || in.name().isBlank()) throw new IllegalArgumentException("Name is required");
        p.setName(in.name().trim());
        p.setUpiName(in.upiName() == null || in.upiName().isBlank() ? null : in.upiName().trim());
        p.setPhone(in.phone());
        p.setNotes(in.notes());
        return p;
    }

    public record ShareLine(Long shareId, BigDecimal amount, TxnDto txn) {}

    /** @param paidForMe things they paid for, with my share as the amount */
    public record PersonDetail(PersonDto person, List<ShareLine> shares, List<SettlementDto> settlements,
                               List<TxnDto> paidForMe) {}

    @GetMapping("/people/{id}")
    @Transactional(readOnly = true)
    public PersonDetail detail(@PathVariable Long id) {
        Person p = person(id);
        return new PersonDetail(dto(p),
                shares.findByPersonOrderByTxnOccurredAtDesc(p).stream()
                        .map(s -> new ShareLine(s.getId(), s.getAmount(), TxnDto.of(s.getTxn(), null))).toList(),
                settlements.findByPersonOrderBySettledAtDesc(p).stream().map(SettlementDto::of).toList(),
                txns.findByPaidByOrderByOccurredAtDesc(p).stream().map(t -> TxnDto.of(t, null)).toList());
    }

    // ---- settlements -------------------------------------------------------------------------------------

    @GetMapping("/settlements/suggested")
    @Transactional(readOnly = true)
    public List<SettlementDto> suggested() {
        return settlements.findByStatusOrderBySettledAtDesc(SettlementStatus.SUGGESTED).stream()
                .map(SettlementDto::of).toList();
    }

    /** @param direction IN (default): they paid me back; OUT: I paid them back */
    public record SettlementInput(Long personId, SettlementDirection direction, BigDecimal amount, Instant settledAt,
                                  Long txnId, String note) {}

    /** @param rememberedName the sender's name, saved as the person's UPI name so the next one matches */
    public record Recorded(SettlementDto settlement, String rememberedName) {}

    @PostMapping("/settlements")
    @Transactional
    public Recorded record(@RequestBody SettlementInput in) {
        var txn = in.txnId() == null ? null : txns.findById(in.txnId()).orElseThrow(() -> new NoSuchElementException("Transaction not found"));
        var r = splits.record(person(in.personId()), in.direction(), in.amount(), in.settledAt(), txn, in.note());
        return new Recorded(SettlementDto.of(r.settlement()), r.rememberedName());
    }

    /** Repayments linked to a payment (not the ones I turned down). */
    @GetMapping("/transactions/{id}/repayments")
    @Transactional(readOnly = true)
    public List<SettlementDto> repayments(@PathVariable Long id) {
        var t = txns.findById(id).orElseThrow(() -> new NoSuchElementException("Transaction not found"));
        return settlements.findByTxn(t).stream().filter(s -> s.getStatus() != SettlementStatus.REJECTED).map(SettlementDto::of).toList();
    }

    public record ConfirmInput(BigDecimal amount) {}

    @PostMapping("/settlements/{id}/confirm")
    @Transactional
    public Recorded confirm(@PathVariable Long id, @RequestBody(required = false) ConfirmInput in) {
        Settlement s = settlements.findById(id).orElseThrow();
        String remembered = splits.confirm(s, in == null ? null : in.amount());
        return new Recorded(SettlementDto.of(s), remembered);
    }

    @PostMapping("/settlements/{id}/reject")
    @Transactional
    public SettlementDto reject(@PathVariable Long id) {
        Settlement s = settlements.findById(id).orElseThrow();
        s.setStatus(SettlementStatus.REJECTED);
        return SettlementDto.of(s);
    }

    @DeleteMapping("/settlements/{id}")
    @Transactional
    public void deleteSettlement(@PathVariable Long id) {
        settlements.deleteById(id);
        settlements.flush();
        splits.rescan();
    }
}
