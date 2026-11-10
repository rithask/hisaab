package com.hisaab.service;

import com.hisaab.domain.Account;
import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.Institution;
import com.hisaab.repo.AccountRepo;
import com.hisaab.repo.TxnRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AccountService {
    private final AccountRepo accounts;
    private final TxnRepo txns;
    private final BalanceService balances;

    public AccountService(AccountRepo accounts, TxnRepo txns, BalanceService balances) {
        this.accounts = accounts;
        this.txns = txns;
        this.balances = balances;
    }

    public record AccountInput(String name, AccountType type, Institution institution, String last4,
                               BigDecimal openingBalance, Instant openingDate, BigDecimal creditLimit,
                               Integer statementDay, Integer dueDay, Boolean archived) {}

    public record CardInfo(BigDecimal creditLimit, BigDecimal outstanding, BigDecimal available,
                           BigDecimal utilizationPct, LocalDate lastStatement, LocalDate nextStatement,
                           LocalDate dueDate, long daysToDue, BigDecimal spentThisCycle,
                           BigDecimal paidSinceStatement) {}

    public record AccountView(Long id, String name, AccountType type, Institution institution, String last4,
                              BigDecimal openingBalance, Instant openingDate, boolean archived,
                              /* bank/cash balance, or card outstanding */ BigDecimal balance,
                              Integer statementDay, Integer dueDay, CardInfo card) {}

    public Account get(Long id) {
        return accounts.findById(id).orElseThrow(() -> new NoSuchElementException("Account " + id + " not found"));
    }

    public List<AccountView> list(boolean includeArchived) {
        List<Account> list = includeArchived ? accounts.findAllByOrderByArchivedAscTypeAscNameAsc()
                : accounts.findByArchivedFalseOrderByTypeAscNameAsc();
        return list.stream().map(this::view).toList();
    }

    public AccountView view(Account a) {
        BigDecimal balance = balances.current(a);
        CardInfo card = null;
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
                days = java.time.temporal.ChronoUnit.DAYS.between(today, due);
                Instant cycleStart = max(c.cycleStart().atStartOfDay(Money.INDIA).toInstant(), a.getOpeningDate());
                Instant statementEnd = max(c.lastStatement().plusDays(1).atStartOfDay(Money.INDIA).toInstant(), a.getOpeningDate());
                spent = txns.sumSince(a, Direction.DEBIT, cycleStart);
                paid = txns.sumSince(a, Direction.CREDIT, statementEnd);
            }
            card = new CardInfo(limit, balance, available, util, last, next, due, days, spent, paid);
        }
        return new AccountView(a.getId(), a.getName(), a.getType(), a.getInstitution(), a.getLast4(),
                a.getOpeningBalance(), a.getOpeningDate(), a.isArchived(), balance,
                a.getStatementDay(), a.getDueDay(), card);
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    @Transactional
    public Account save(Account a, AccountInput in) {
        if (in.name() == null || in.name().isBlank()) throw new IllegalArgumentException("Name is required");
        if (in.type() == null) throw new IllegalArgumentException("Type is required");
        if (in.last4() != null && !in.last4().isBlank() && !in.last4().matches("\\d{4}")) {
            throw new IllegalArgumentException("Last 4 digits must be exactly 4 digits");
        }
        if (in.statementDay() != null && (in.statementDay() < 1 || in.statementDay() > 31)) {
            throw new IllegalArgumentException("Statement day must be 1-31");
        }
        if (in.dueDay() != null && (in.dueDay() < 1 || in.dueDay() > 31)) {
            throw new IllegalArgumentException("Due day must be 1-31");
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
