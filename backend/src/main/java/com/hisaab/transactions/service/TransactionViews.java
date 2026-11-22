package com.hisaab.transactions.service;

import com.hisaab.common.model.Direction;
import com.hisaab.people.model.SettlementDirection;
import com.hisaab.people.model.SettlementStatus;
import com.hisaab.common.dto.Dtos.PersonRef;
import com.hisaab.common.dto.Dtos.TxnDto;
import com.hisaab.people.model.Person;
import com.hisaab.people.model.Settlement;
import com.hisaab.people.model.SplitShare;
import com.hisaab.people.repository.SettlementRepository;
import com.hisaab.people.repository.SplitShareRepository;
import com.hisaab.reports.service.ReportService;
import com.hisaab.transactions.model.Txn;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Read model: transactions as the app shows them, with what's mine of each and who it was lent to or paid back by.
 * A page of rows is built with one query per related table (shares, repayments; the rest is batch-fetched), never
 * one query per row.
 */
@Component
public class TransactionViews {
    private final SplitShareRepository shares;
    private final SettlementRepository settlements;

    public TransactionViews(SplitShareRepository shares, SettlementRepository settlements) {
        this.shares = shares;
        this.settlements = settlements;
    }

    public TxnDto one(Txn t) {
        return page(List.of(t)).getFirst();
    }

    public List<TxnDto> page(List<Txn> txns) {
        if (txns.isEmpty()) return List.of();
        Map<Long, List<SplitShare>> split = shares.findByTxnInOrderById(txns).stream()
                .collect(Collectors.groupingBy(s -> s.getTxn().getId()));
        Map<Long, List<Settlement>> repaid = settlements.findByTxnInOrderById(txns).stream()
                .filter(s -> s.getStatus() == SettlementStatus.CONFIRMED)
                .collect(Collectors.groupingBy(s -> s.getTxn().getId()));
        return txns.stream().map(t -> view(t, split.getOrDefault(t.getId(), List.of()), repaid.getOrDefault(t.getId(), List.of()))).toList();
    }

    /**
     * One row: a payment that's all someone else's is money I lent ("Lent to Fabis"); a confirmed repayment says who
     * paid me back or whom I paid back.
     */
    static TxnDto view(Txn t, List<SplitShare> split, List<Settlement> repaid) {
        BigDecimal shared = split.stream().map(SplitShare::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal in = sum(repaid, SettlementDirection.IN), out = sum(repaid, SettlementDirection.OUT);
        BigDecimal mine = ReportService.myAmount(t.getDirection(), t.getAmount(), shared, in, out);
        List<PersonRef> lentTo = t.getDirection() == Direction.DEBIT && mine.signum() == 0 && out.signum() == 0
                ? people(split.stream().map(SplitShare::getPerson)) : List.of();
        return TxnDto.of(t, shared.signum() == 0 ? null : shared, mine, lentTo,
                people(repaid.stream().filter(s -> s.getDirection() == SettlementDirection.IN).map(Settlement::getPerson)),
                people(repaid.stream().filter(s -> s.getDirection() == SettlementDirection.OUT).map(Settlement::getPerson)));
    }

    private static BigDecimal sum(List<Settlement> list, SettlementDirection d) {
        return list.stream().filter(s -> s.getDirection() == d).map(Settlement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<PersonRef> people(Stream<Person> s) {
        return s.distinct().map(p -> new PersonRef(p.getId(), p.getName())).toList();
    }
}
