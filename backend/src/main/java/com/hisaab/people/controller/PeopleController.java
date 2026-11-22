package com.hisaab.people.controller;

import com.hisaab.people.dto.ConfirmSettlementRequest;
import com.hisaab.people.dto.PersonBalanceResponse;
import com.hisaab.people.dto.PersonDetailResponse;
import com.hisaab.people.dto.PersonRequest;
import com.hisaab.people.dto.PersonResponse;
import com.hisaab.people.dto.SettlementRecordedResponse;
import com.hisaab.people.dto.SettlementRequest;
import com.hisaab.people.dto.SettlementResponse;
import com.hisaab.people.dto.ShareLineResponse;
import com.hisaab.transactions.dto.TransactionResponse;
import com.hisaab.common.model.Direction;
import com.hisaab.people.model.SettlementDirection;
import com.hisaab.people.model.SettlementStatus;
import com.hisaab.common.util.Audit;
import com.hisaab.people.model.Person;
import com.hisaab.people.model.Settlement;
import com.hisaab.people.repository.PersonRepository;
import com.hisaab.people.repository.SettlementRepository;
import com.hisaab.people.repository.SplitShareRepository;
import com.hisaab.people.service.SplitService;
import com.hisaab.transactions.model.Txn;
import com.hisaab.transactions.repository.TxnRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.hisaab.common.exception.BadRequestException;
import com.hisaab.common.exception.NotAllowedException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static com.hisaab.common.exception.NotFound.notFound;

@RestController
@RequestMapping("/api")
public class PeopleController {
    private final PersonRepository people;
    private final SplitShareRepository shares;
    private final SettlementRepository settlements;
    private final SplitService splits;
    private final TxnRepository txns;

    public PeopleController(PersonRepository people, SplitShareRepository shares, SettlementRepository settlements, SplitService splits,
                            TxnRepository txns) {
        this.people = people;
        this.shares = shares;
        this.settlements = settlements;
        this.splits = splits;
        this.txns = txns;
    }

    PersonResponse dto(Person p) {
        return dto(p, splits.balance(p));
    }

    private static PersonResponse dto(Person p, PersonBalanceResponse b) {
        return new PersonResponse(p.getId(), p.getName(), p.getUpiName(), p.getPhone(), p.getNotes(), b.owed(), b.paid(),
                b.paidForMe(), b.paidBack(), b.outstanding());
    }

    Person person(Long id) {
        return people.findById(id).orElseThrow(notFound("Person"));
    }

    @GetMapping("/people")
    public List<PersonResponse> list() {
        List<Person> all = people.findAllByOrderByNameAsc();
        var balances = splits.balances(all);
        return all.stream().map(p -> dto(p, balances.get(p.getId()))).toList();
    }

    @PostMapping("/people")
    @Transactional
    public PersonResponse create(@RequestBody PersonRequest in) {
        Person p = splits.savePerson(apply(new Person(), in));
        return dto(p);
    }

    @PutMapping("/people/{id}")
    @Transactional
    public PersonResponse update(@PathVariable Long id, @RequestBody PersonRequest in) {
        Person p = splits.savePerson(apply(person(id), in));
        return dto(p);
    }

    /**
     * Recent money that could be this person settling up, for picking it by hand: money in when they owe me,
     * money out when I owe them.
     */
    @GetMapping("/people/{id}/open-payments")
    @Transactional(readOnly = true)
    public List<TransactionResponse> openPayments(@PathVariable Long id, @RequestParam SettlementDirection direction) {
        Person p = person(id);
        var dir = direction == SettlementDirection.OUT ? Direction.DEBIT : Direction.CREDIT;
        BigDecimal due = splits.outstanding(p).abs();
        // their name first, then the amounts nearest to what's open, then the newest
        return txns.findOpenSince(Instant.now().minus(Duration.ofDays(60)), dir).stream()
                .sorted(Comparator.comparing((Txn t) -> !SplitService.named(p, t.getCounterparty()))
                        .thenComparing(t -> t.getAmount().subtract(due).abs())
                        .thenComparing(Txn::getOccurredAt, Comparator.reverseOrder()))
                .limit(30).map(t -> TransactionResponse.of(t, null)).toList();
    }

    @DeleteMapping("/people/{id}")
    public void delete(@PathVariable Long id) {
        Person p = person(id);
        // what they paid for me is part of my spending: don't let it vanish with them
        if (txns.existsByPaidBy(p)) {
            throw new NotAllowedException(p.getName() + " paid for things you recorded. Delete those first, or keep " + p.getName() + ".");
        }
        people.delete(p);
        Audit.log("Person deleted id={} with their split history", id);
    }

    private static Person apply(Person p, PersonRequest in) {
        if (in.name() == null || in.name().isBlank()) throw new BadRequestException("Name is required");
        p.setName(in.name().trim());
        p.setUpiName(in.upiName() == null || in.upiName().isBlank() ? null : in.upiName().trim());
        p.setPhone(in.phone());
        p.setNotes(in.notes());
        return p;
    }

    @GetMapping("/people/{id}")
    @Transactional(readOnly = true)
    public PersonDetailResponse detail(@PathVariable Long id) {
        Person p = person(id);
        return new PersonDetailResponse(dto(p),
                shares.findByPersonOrderByTxnOccurredAtDesc(p).stream()
                        .map(s -> new ShareLineResponse(s.getId(), s.getAmount(), TransactionResponse.of(s.getTxn(), null))).toList(),
                settlements.findByPersonOrderBySettledAtDesc(p).stream().map(SettlementResponse::of).toList(),
                txns.findByPaidByOrderByOccurredAtDesc(p).stream().map(t -> TransactionResponse.of(t, null)).toList());
    }

    // ---- settlements -------------------------------------------------------------------------------------

    @GetMapping("/settlements/suggested")
    @Transactional(readOnly = true)
    public List<SettlementResponse> suggested() {
        return settlements.findByStatusOrderBySettledAtDesc(SettlementStatus.SUGGESTED).stream()
                .map(SettlementResponse::of).toList();
    }

    @PostMapping("/settlements")
    @Transactional
    public SettlementRecordedResponse record(@RequestBody SettlementRequest in) {
        var txn = in.txnId() == null ? null : txns.findById(in.txnId()).orElseThrow(notFound("Transaction"));
        var r = splits.record(person(in.personId()), in.direction(), in.amount(), in.settledAt(), txn, in.note());
        return new SettlementRecordedResponse(SettlementResponse.of(r.settlement()), r.rememberedName());
    }

    /** Repayments linked to a payment (not the ones I turned down). */
    @GetMapping("/transactions/{id}/repayments")
    @Transactional(readOnly = true)
    public List<SettlementResponse> repayments(@PathVariable Long id) {
        var t = txns.findById(id).orElseThrow(notFound("Transaction"));
        return settlements.findByTxn(t).stream().filter(s -> s.getStatus() != SettlementStatus.REJECTED).map(SettlementResponse::of).toList();
    }

    @PostMapping("/settlements/{id}/confirm")
    @Transactional
    public SettlementRecordedResponse confirm(@PathVariable Long id, @RequestBody(required = false) ConfirmSettlementRequest in) {
        Settlement s = settlements.findById(id).orElseThrow();
        String remembered = splits.confirm(s, in == null ? null : in.amount());
        return new SettlementRecordedResponse(SettlementResponse.of(s), remembered);
    }

    @PostMapping("/settlements/{id}/reject")
    @Transactional
    public SettlementResponse reject(@PathVariable Long id) {
        Settlement s = settlements.findById(id).orElseThrow();
        s.setStatus(SettlementStatus.REJECTED);
        org.slf4j.LoggerFactory.getLogger(SplitService.class).info("Repayment suggestion {} turned down", id);
        return SettlementResponse.of(s);
    }

    @DeleteMapping("/settlements/{id}")
    @Transactional
    public void deleteSettlement(@PathVariable Long id) {
        splits.removeSettlement(id);
    }
}
