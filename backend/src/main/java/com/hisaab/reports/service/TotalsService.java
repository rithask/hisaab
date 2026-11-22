package com.hisaab.reports.service;

import com.hisaab.accounts.dto.AccountResponse;
import com.hisaab.investments.dto.HoldingResponse;
import com.hisaab.investments.dto.PortfolioResponse;
import com.hisaab.people.dto.PersonBalanceResponse;
import com.hisaab.reports.dto.TotalsResponse;
import com.hisaab.common.model.AccountType;
import com.hisaab.accounts.service.AccountService;
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

    @Transactional(readOnly = true)
    public TotalsResponse now() {
        return of(accounts.list(false));
    }

    public TotalsResponse of(List<AccountResponse> list) {
        BigDecimal cash = BigDecimal.ZERO, owed = BigDecimal.ZERO, limit = BigDecimal.ZERO;
        for (AccountResponse a : list) {
            if (a.type() == AccountType.CREDIT_CARD) {
                owed = owed.add(a.balance());
                if (a.card() != null && a.card().creditLimit() != null) limit = limit.add(a.card().creditLimit());
            } else {
                cash = cash.add(a.balance());
            }
        }
        PortfolioResponse pf = PortfolioResponse.of(holdings.findAll().stream().map(HoldingResponse::of).toList());
        List<BigDecimal> balances = splits.balances(people.findAll()).values().stream().map(PersonBalanceResponse::outstanding).toList();
        BigDecimal receivable = balances.stream().filter(v -> v.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal payable = balances.stream().filter(v -> v.signum() < 0).reduce(BigDecimal.ZERO, BigDecimal::add); // negative
        BigDecimal net = cash.add(pf.currentValue()).add(receivable).add(payable).subtract(owed);
        return new TotalsResponse(cash, owed, limit, pf.currentValue(), pf.invested(), receivable, net);
    }
}
