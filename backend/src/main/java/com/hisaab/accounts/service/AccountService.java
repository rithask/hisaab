package com.hisaab.accounts.service;

import com.hisaab.accounts.dto.AccountRequest;
import com.hisaab.accounts.dto.AccountResponse;
import com.hisaab.accounts.dto.CardInfoResponse;
import com.hisaab.common.model.AccountType;
import com.hisaab.common.model.Direction;
import com.hisaab.common.model.Institution;
import com.hisaab.accounts.model.Account;
import com.hisaab.accounts.repository.AccountRepository;
import com.hisaab.auth.util.AuthLog;
import com.hisaab.common.util.Audit;
import com.hisaab.accounts.util.CardCycle;
import com.hisaab.common.util.Money;
import com.hisaab.transactions.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hisaab.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.hisaab.common.exception.NotFound.notFound;

@Service
public class AccountService {
    private final AccountRepository accounts;
    private final TransactionRepository txns;
    private final BalanceService balances;

    public AccountService(AccountRepository accounts, TransactionRepository txns, BalanceService balances) {
        this.accounts = accounts;
        this.txns = txns;
        this.balances = balances;
    }

    public void delete(Long id) {
        Account account = get(id);
        accounts.delete(account);
        // deleting an account deletes its transactions too
        Audit.log("Account deleted id={} name=\"{}\" with its transactions", id, AuthLog.clean(account.getName(), 60));
    }

    public Account get(Long id) {
        return accounts.findById(id).orElseThrow(notFound("Account " + id));
    }

    public List<AccountResponse> list(boolean includeArchived) {
        List<Account> list = includeArchived ? accounts.findAllByOrderByArchivedAscTypeAscNameAsc()
                : accounts.findByArchivedFalseOrderByTypeAscNameAsc();
        var current = balances.current(list);
        return list.stream().map(a -> view(a, current.get(a.getId()))).toList();
    }

    public AccountResponse view(Account a) {
        return view(a, balances.current(a));
    }

    private AccountResponse view(Account a, BigDecimal balance) {
        CardInfoResponse card = null;
        if (a.isCard()) {
            BigDecimal limit = a.getCreditLimit();
            BigDecimal available = limit == null ? null : limit.subtract(balance);
            BigDecimal util = limit == null || limit.signum() == 0 ? null
                    : balance.multiply(BigDecimal.valueOf(100)).divide(limit, 1, RoundingMode.HALF_UP);
            LocalDate last = null, next = null, due = null;
            long days = 0;
            BigDecimal spent = null, paid = null;
            if (a.getStatementDay() != null && a.getDueDay() != null) {
                LocalDate today = LocalDate.now(Money.INDIA);
                CardCycle c = CardCycle.of(a.getStatementDay(), a.getDueDay(), today);
                last = c.lastStatement();
                next = c.nextStatement();
                due = c.dueDate();
                days = ChronoUnit.DAYS.between(today, due);
                Instant cycleStart = max(c.cycleStart().atStartOfDay(Money.INDIA).toInstant(), a.getOpeningDate());
                Instant statementEnd = max(c.lastStatement().plusDays(1).atStartOfDay(Money.INDIA).toInstant(), a.getOpeningDate());
                spent = txns.sumSince(a, Direction.DEBIT, cycleStart);
                paid = txns.sumSince(a, Direction.CREDIT, statementEnd);
            }
            card = new CardInfoResponse(limit, balance, available, util, last, next, due, days, spent, paid);
        }
        return new AccountResponse(a.getId(), a.getName(), a.getType(), a.getInstitution(), a.getLast4(),
                a.getOpeningBalance(), a.getOpeningDate(), a.isArchived(), balance,
                a.getStatementDay(), a.getDueDay(), card);
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    @Transactional
    public Account save(Account a, AccountRequest in) {
        if (in.name() == null || in.name().isBlank()) throw new BadRequestException("Name is required");
        if (in.type() == null) throw new BadRequestException("Type is required");
        if (in.last4() != null && !in.last4().isBlank() && !in.last4().matches("\\d{4}")) {
            throw new BadRequestException("Last 4 digits must be exactly 4 digits");
        }
        if (in.statementDay() != null && (in.statementDay() < 1 || in.statementDay() > 31)) {
            throw new BadRequestException("Statement day must be 1-31");
        }
        if (in.dueDay() != null && (in.dueDay() < 1 || in.dueDay() > 31)) {
            throw new BadRequestException("Due day must be 1-31");
        }
        a.setName(in.name().trim());
        a.setType(in.type());
        a.setInstitution(in.institution() == null ? Institution.OTHER : in.institution());
        a.setLast4(in.last4() == null || in.last4().isBlank() ? null : in.last4());
        a.setOpeningBalance(Money.r2(Money.nz(in.openingBalance())));
        a.setOpeningDate(in.openingDate() != null ? in.openingDate()
                : a.getOpeningDate() != null ? a.getOpeningDate() : Instant.now());
        a.setCreditLimit(in.type() == AccountType.CREDIT_CARD ? Money.r2(in.creditLimit()) : null);
        a.setStatementDay(in.type() == AccountType.CREDIT_CARD ? in.statementDay() : null);
        a.setDueDay(in.type() == AccountType.CREDIT_CARD ? in.dueDay() : null);
        if (in.archived() != null) a.setArchived(in.archived());
        return accounts.save(a);
    }
}
