package com.hisaab.people.service;

import com.hisaab.common.exception.BadRequestException;
import com.hisaab.common.exception.NotAllowedException;
import com.hisaab.common.model.Direction;
import com.hisaab.common.util.Audit;
import com.hisaab.people.dto.ConfirmSettlementRequest;
import com.hisaab.people.dto.PersonBalanceResponse;
import com.hisaab.people.dto.PersonDetailResponse;
import com.hisaab.people.dto.PersonRequest;
import com.hisaab.people.dto.PersonResponse;
import com.hisaab.people.dto.SettlementRecordedResponse;
import com.hisaab.people.dto.SettlementRequest;
import com.hisaab.people.dto.SettlementResponse;
import com.hisaab.people.dto.ShareLineResponse;
import com.hisaab.people.model.Person;
import com.hisaab.people.model.Settlement;
import com.hisaab.people.model.SettlementDirection;
import com.hisaab.people.model.SettlementStatus;
import com.hisaab.people.repository.PersonRepository;
import com.hisaab.people.repository.SettlementRepository;
import com.hisaab.people.repository.SplitShareRepository;
import com.hisaab.transactions.dto.TransactionResponse;
import com.hisaab.transactions.model.Txn;
import com.hisaab.transactions.repository.TxnRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.hisaab.common.exception.NotFound.notFound;

@Service
public class PeopleService {
    private static final Logger log = LoggerFactory.getLogger(PeopleService.class);

    private final PersonRepository people;
    private final SplitShareRepository shares;
    private final SettlementRepository settlements;
    private final SplitService splits;
    private final TxnRepository txns;

    public PeopleService(PersonRepository people, SplitShareRepository shares, SettlementRepository settlements, SplitService splits,
                         TxnRepository txns) {
        this.people = people;
        this.shares = shares;
        this.settlements = settlements;
        this.splits = splits;
        this.txns = txns;
    }

    private PersonResponse dto(Person p) {
        return dto(p, splits.balance(p));
    }

    private static PersonResponse dto(Person p, PersonBalanceResponse b) {
        return new PersonResponse(p.getId(), p.getName(), p.getUpiName(), p.getPhone(), p.getNotes(), b.owed(), b.paid(),
                b.paidForMe(), b.paidBack(), b.outstanding());
    }

    private Person person(Long id) {
        return people.findById(id).orElseThrow(notFound("Person"));
    }

    public List<PersonResponse> list() {
        List<Person> all = people.findAllByOrderByNameAsc();
        var balances = splits.balances(all);
        return all.stream().map(p -> dto(p, balances.get(p.getId()))).toList();
    }

    @Transactional
    public PersonResponse create(PersonRequest in) {
        Person p = splits.savePerson(apply(new Person(), in));
        return dto(p);
    }

    @Transactional
    public PersonResponse update(Long id, PersonRequest in) {
        Person p = splits.savePerson(apply(person(id), in));
        return dto(p);
    }

    /**
     * Recent money that could be this person settling up, for picking it by hand: money in when they owe me,
     * money out when I owe them.
     */
    @Transactional(readOnly = true)
    public List<TransactionResponse> openPayments(Long id, SettlementDirection direction) {
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

    public void delete(Long id) {
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

    @Transactional(readOnly = true)
    public PersonDetailResponse detail(Long id) {
        Person p = person(id);
        return new PersonDetailResponse(dto(p),
                shares.findByPersonOrderByTxnOccurredAtDesc(p).stream()
                        .map(s -> new ShareLineResponse(s.getId(), s.getAmount(), TransactionResponse.of(s.getTxn(), null))).toList(),
                settlements.findByPersonOrderBySettledAtDesc(p).stream().map(SettlementResponse::of).toList(),
                txns.findByPaidByOrderByOccurredAtDesc(p).stream().map(t -> TransactionResponse.of(t, null)).toList());
    }

    // ---- settlements -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SettlementResponse> suggested() {
        return settlements.findByStatusOrderBySettledAtDesc(SettlementStatus.SUGGESTED).stream()
                .map(SettlementResponse::of).toList();
    }

    @Transactional
    public SettlementRecordedResponse record(SettlementRequest in) {
        var txn = in.txnId() == null ? null : txns.findById(in.txnId()).orElseThrow(notFound("Transaction"));
        var r = splits.record(person(in.personId()), in.direction(), in.amount(), in.settledAt(), txn, in.note());
        return new SettlementRecordedResponse(SettlementResponse.of(r.settlement()), r.rememberedName());
    }

    /** Repayments linked to a payment (not the ones I turned down). */
    @Transactional(readOnly = true)
    public List<SettlementResponse> repayments(Long id) {
        var t = txns.findById(id).orElseThrow(notFound("Transaction"));
        return settlements.findByTxn(t).stream().filter(s -> s.getStatus() != SettlementStatus.REJECTED).map(SettlementResponse::of).toList();
    }

    @Transactional
    public SettlementRecordedResponse confirm(Long id, ConfirmSettlementRequest in) {
        Settlement s = settlements.findById(id).orElseThrow(notFound("Repayment"));
        String remembered = splits.confirm(s, in == null ? null : in.amount());
        return new SettlementRecordedResponse(SettlementResponse.of(s), remembered);
    }

    @Transactional
    public SettlementResponse reject(Long id) {
        Settlement s = settlements.findById(id).orElseThrow(notFound("Repayment"));
        s.setStatus(SettlementStatus.REJECTED);
        log.info("Repayment suggestion {} turned down", id);
        return SettlementResponse.of(s);
    }

    @Transactional
    public void deleteSettlement(Long id) {
        splits.removeSettlement(id);
    }
}
