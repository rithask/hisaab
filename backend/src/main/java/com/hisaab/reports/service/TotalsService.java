package com.hisaab.reports.service;

import com.hisaab.common.model.AccountType;
import com.hisaab.accounts.service.AccountService;
import com.hisaab.accounts.service.AccountService.AccountView;
import com.hisaab.common.dto.Dtos.HoldingDto;
import com.hisaab.common.dto.Dtos.PortfolioDto;
import com.hisaab.investments.repository.HoldingRepository;
import com.hisaab.people.repository.PersonRepository;
import com.hisaab.people.service.SplitService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/** Today's totals, shared by the dashboard and the daily net-worth snapshot so they always agree. */
@Service
public class TotalsService {
    private final AccountService accounts;
    private final HoldingRepository holdings;
    private final PersonRepository people;
    private final SplitService splits;

    public TotalsService(AccountService accounts, HoldingRepository holdings, PersonRepository people, SplitService splits) {
        this.accounts = accounts;
        this.holdings = holdings;
        this.people = people;
        this.splits = splits;
    }

    public record Totals(BigDecimal cash, BigDecimal cardOutstanding, BigDecimal cardLimit, BigDecimal investments,
                         BigDecimal invested, BigDecimal receivable, BigDecimal netWorth) {}

    @Transactional(readOnly = true)
    public Totals now() {
        return of(accounts.list(false));
    }

    public Totals of(List<AccountView> list) {
        BigDecimal cash = BigDecimal.ZERO, owed = BigDecimal.ZERO, limit = BigDecimal.ZERO;
        for (AccountView a : list) {
            if (a.type() == AccountType.CREDIT_CARD) {
                owed = owed.add(a.balance());
                if (a.card() != null && a.card().creditLimit() != null) limit = limit.add(a.card().creditLimit());
            } else {
                cash = cash.add(a.balance());
            }
        }
        PortfolioDto pf = PortfolioDto.of(holdings.findAll().stream().map(HoldingDto::of).toList());
        List<BigDecimal> balances = splits.balances(people.findAll()).values().stream().map(SplitService.Balance::outstanding).toList();
        BigDecimal receivable = balances.stream().filter(v -> v.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal payable = balances.stream().filter(v -> v.signum() < 0).reduce(BigDecimal.ZERO, BigDecimal::add); // negative
        BigDecimal net = cash.add(pf.currentValue()).add(receivable).add(payable).subtract(owed);
        return new Totals(cash, owed, limit, pf.currentValue(), pf.invested(), receivable, net);
    }
}
