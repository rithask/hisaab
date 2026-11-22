package com.hisaab.reports.controller;

import com.hisaab.messages.model.EmailStatus;
import com.hisaab.people.model.SettlementStatus;
import com.hisaab.statements.model.ImportStatus;
import com.hisaab.accounts.repository.AlertRepository;
import com.hisaab.accounts.service.AccountService;
import com.hisaab.accounts.service.AccountService.AccountView;
import com.hisaab.common.util.Money;
import com.hisaab.investments.repository.HoldingRepository;
import com.hisaab.messages.repository.RawEmailRepository;
import com.hisaab.people.repository.PersonRepository;
import com.hisaab.people.repository.SettlementRepository;
import com.hisaab.people.service.SplitService;
import com.hisaab.reports.service.NetWorthService;
import com.hisaab.reports.service.ReportService;
import com.hisaab.reports.service.ReviewService;
import com.hisaab.reports.service.TotalsService;
import com.hisaab.statements.repository.StatementFileRepository;
import com.hisaab.statements.repository.StatementImportRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final AccountService accounts;
    private final ReportService reports;
    private final HoldingRepository holdings;
    private final PersonRepository people;
    private final SplitService splits;
    private final AlertRepository alerts;
    private final RawEmailRepository emails;
    private final SettlementRepository settlements;
    private final StatementImportRepository statementImports;
    private final StatementFileRepository statementFiles;
    private final TotalsService totals;
    private final NetWorthService networth;
    private final ReviewService review;

    public DashboardController(AccountService accounts, ReportService reports, HoldingRepository holdings, PersonRepository people,
                               SplitService splits, AlertRepository alerts, RawEmailRepository emails, SettlementRepository settlements,
                               StatementImportRepository statementImports,
                               StatementFileRepository statementFiles, TotalsService totals,
                               NetWorthService networth, ReviewService review) {
        this.networth = networth;
        this.review = review;
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
                        statementImports.countByStatus(ImportStatus.REVIEW),
                        statementFiles.countByImportStatus("NEEDS_PASSWORD")));
    }

    /** Month-by-month income and spending, ending at {@code until} (yyyy-MM, default this month). */
    @GetMapping("/reports/monthly")
    public List<ReportService.MonthTotal> monthly(@RequestParam(required = false) String until,
                                                  @RequestParam(defaultValue = "6") int months) {
        YearMonth end = until == null ? YearMonth.now(Money.ZONE) : YearMonth.parse(until);
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

    // ---- net worth
    @GetMapping("/networth")
    public List<NetWorthService.Point> networth(@RequestParam(defaultValue = "6m") String range) {
        LocalDate today = LocalDate.now(Money.ZONE);
        LocalDate from = switch (range) {
            case "1m" -> today.minusMonths(1);
            case "3m" -> today.minusMonths(3);
            case "1y" -> today.minusYears(1);
            case "all" -> LocalDate.of(2000, 1, 1);
            default -> today.minusMonths(6);
        };
        return networth.series(from);
    }

    // ---- year in review
    @GetMapping("/review")
    public ReviewService.Review review(@RequestParam(required = false) Integer year) {
        return review.year(year == null ? LocalDate.now(Money.ZONE).getYear() : year);
    }
}
