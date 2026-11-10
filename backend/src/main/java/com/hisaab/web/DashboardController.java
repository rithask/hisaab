package com.hisaab.web;

import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.EmailStatus;
import com.hisaab.domain.Enums.SettlementStatus;
import com.hisaab.repo.*;
import com.hisaab.service.AccountService;
import com.hisaab.service.AccountService.AccountView;
import com.hisaab.service.Money;
import com.hisaab.service.ReportService;
import com.hisaab.service.SplitService;
import com.hisaab.web.Dtos.HoldingDto;
import com.hisaab.web.Dtos.PortfolioDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final AccountService accounts;
    private final ReportService reports;
    private final HoldingRepo holdings;
    private final PersonRepo people;
    private final SplitService splits;
    private final AlertRepo alerts;
    private final RawEmailRepo emails;
    private final SettlementRepo settlements;
    private final com.hisaab.repo.StatementImportRepo statementImports;
    private final com.hisaab.repo.StatementFileRepo statementFiles;
    private final TotalsService totals;

    public DashboardController(AccountService accounts, ReportService reports, HoldingRepo holdings, PersonRepo people,
                               SplitService splits, AlertRepo alerts, RawEmailRepo emails, SettlementRepo settlements,
                               com.hisaab.repo.StatementImportRepo statementImports,
                               com.hisaab.repo.StatementFileRepo statementFiles, TotalsService totals) {
        this.totals = totals;
        this.statementImports = statementImports;
        this.statementFiles = statementFiles;
        this.accounts = accounts;
        this.reports = reports;
        this.holdings = holdings;
        this.people = people;
        this.splits = splits;
        this.alerts = alerts;
        this.emails = emails;
        this.settlements = settlements;
    }

    public record Totals(BigDecimal cash, BigDecimal cardOutstanding, BigDecimal cardLimit, BigDecimal investments,
                         BigDecimal invested, BigDecimal receivable, BigDecimal netWorth) {}

    /** @param statementsToReview statements read and waiting for my review; @param statementsLocked ones that need a password */
    public record Counts(long alerts, long emailsToReview, long suggestedSettlements, long statementsToReview, long statementsLocked) {}

    public record Dashboard(Totals totals, List<AccountView> accounts, ReportService.Report month,
                            List<ReportService.MonthTotal> trend, Counts counts) {}

    @GetMapping("/dashboard")
    public Dashboard dashboard(@RequestParam(required = false) String month) {
        List<AccountView> list = accounts.list(false);
        TotalsService.Totals t = totals.of(list);
        return new Dashboard(new Totals(t.cash(), t.cardOutstanding(), t.cardLimit(), t.investments(), t.invested(), t.receivable(), t.netWorth()),
                list, report(month), reports.monthly(6),
                new Counts(alerts.countByDismissedFalse(),
                        emails.countByStatus(EmailStatus.NEEDS_REVIEW) + emails.countByStatus(EmailStatus.ERROR),
                        settlements.countByStatus(SettlementStatus.SUGGESTED),
                        statementImports.countByStatus(com.hisaab.domain.Enums.ImportStatus.REVIEW),
                        statementFiles.countByImportStatus("NEEDS_PASSWORD")));
    }

    /** Month-by-month income and spending, ending at {@code until} (yyyy-MM, default this month). */
    @GetMapping("/reports/monthly")
    public List<ReportService.MonthTotal> monthly(@RequestParam(required = false) String until,
                                                  @RequestParam(defaultValue = "6") int months) {
        java.time.YearMonth end = until == null ? java.time.YearMonth.now(com.hisaab.service.Money.ZONE) : java.time.YearMonth.parse(until);
        return reports.monthly(end, Math.max(1, Math.min(months, 24)));
    }

    /** Income/expense breakdown. {@code month} is yyyy-MM; alternatively pass from/to. */
    @GetMapping("/reports")
    public ReportService.Report report(@RequestParam(required = false) String month,
                                      @RequestParam(required = false) Instant from,
                                      @RequestParam(required = false) Instant to) {
        if (from != null && to != null) return reports.report(from, to);
        return report(month);
    }

    private ReportService.Report report(String month) {
        YearMonth ym = month == null || month.isBlank() ? YearMonth.now(Money.ZONE) : YearMonth.parse(month);
        return reports.report(ym.atDay(1).atStartOfDay(Money.ZONE).toInstant(),
                ym.plusMonths(1).atDay(1).atStartOfDay(Money.ZONE).toInstant());
    }
}
