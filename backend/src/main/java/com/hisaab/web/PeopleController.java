package com.hisaab.web;

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
        BigDecimal owed = shares.totalOwed(p);
        BigDecimal paid = settlements.totalPaid(p);
        return new PersonDto(p.getId(), p.getName(), p.getUpiName(), p.getPhone(), p.getNotes(), owed, paid,
                owed.subtract(paid));
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
    public PersonDto create(@RequestBody PersonInput in) {
        return dto(people.save(apply(new Person(), in)));
    }

    @PutMapping("/people/{id}")
    public PersonDto update(@PathVariable Long id, @RequestBody PersonInput in) {
        return dto(people.save(apply(person(id), in)));
    }

    @DeleteMapping("/people/{id}")
    public void delete(@PathVariable Long id) {
        people.delete(person(id));
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

    public record PersonDetail(PersonDto person, List<ShareLine> shares, List<SettlementDto> settlements) {}

    @GetMapping("/people/{id}")
    @Transactional(readOnly = true)
    public PersonDetail detail(@PathVariable Long id) {
        Person p = person(id);
        return new PersonDetail(dto(p),
                shares.findByPersonOrderByTxnOccurredAtDesc(p).stream()
                        .map(s -> new ShareLine(s.getId(), s.getAmount(), TxnDto.of(s.getTxn(), null))).toList(),
                settlements.findByPersonOrderBySettledAtDesc(p).stream().map(SettlementDto::of).toList());
    }

    // ---- settlements -------------------------------------------------------------------------------------

    @GetMapping("/settlements/suggested")
    @Transactional(readOnly = true)
    public List<SettlementDto> suggested() {
        return settlements.findByStatusOrderBySettledAtDesc(SettlementStatus.SUGGESTED).stream()
                .map(SettlementDto::of).toList();
    }

    public record SettlementInput(Long personId, BigDecimal amount, Instant settledAt, Long txnId, String note) {}

    @PostMapping("/settlements")
    @Transactional
    public SettlementDto record(@RequestBody SettlementInput in) {
        if (in.amount() == null || in.amount().signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
        return SettlementDto.of(splits.record(person(in.personId()), in.amount(), in.settledAt(),
                in.txnId() == null ? null : txns.findById(in.txnId()).orElse(null), in.note()));
    }

    public record ConfirmInput(BigDecimal amount) {}

    @PostMapping("/settlements/{id}/confirm")
    @Transactional
    public SettlementDto confirm(@PathVariable Long id, @RequestBody(required = false) ConfirmInput in) {
        Settlement s = settlements.findById(id).orElseThrow();
        if (in != null && in.amount() != null && in.amount().signum() > 0) s.setAmount(in.amount());
        s.setStatus(SettlementStatus.CONFIRMED);
        return SettlementDto.of(s);
    }

    @PostMapping("/settlements/{id}/reject")
    @Transactional
    public SettlementDto reject(@PathVariable Long id) {
        Settlement s = settlements.findById(id).orElseThrow();
        s.setStatus(SettlementStatus.REJECTED);
        return SettlementDto.of(s);
    }

    @DeleteMapping("/settlements/{id}")
    public void deleteSettlement(@PathVariable Long id) {
        settlements.deleteById(id);
    }
}
