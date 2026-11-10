package com.hisaab.web;

import com.hisaab.domain.Enums.AccountType;
import com.hisaab.repo.HoldingRepo;
import com.hisaab.repo.PersonRepo;
import com.hisaab.service.AccountService;
import com.hisaab.service.AccountService.AccountView;
import com.hisaab.service.SplitService;
import com.hisaab.web.Dtos.HoldingDto;
import com.hisaab.web.Dtos.PortfolioDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/** Today's totals, shared by the dashboard and the daily net-worth snapshot so they always agree. */
@Service
public class TotalsService {
    private final AccountService accounts;
    private final HoldingRepo holdings;
    private final PersonRepo people;
    private final SplitService splits;

    public TotalsService(AccountService accounts, HoldingRepo holdings, PersonRepo people, SplitService splits) {
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

    Totals of(List<AccountView> list) {
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
        BigDecimal receivable = people.findAll().stream().map(splits::outstanding)
                .filter(v -> v.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = cash.add(pf.currentValue()).add(receivable).subtract(owed);
        return new Totals(cash, owed, limit, pf.currentValue(), pf.invested(), receivable, net);
    }
}
