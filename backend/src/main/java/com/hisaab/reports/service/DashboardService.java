package com.hisaab.reports.service;

import com.hisaab.accounts.dto.AccountResponse;
import com.hisaab.accounts.repository.AlertRepository;
import com.hisaab.accounts.service.AccountService;
import com.hisaab.common.util.Money;
import com.hisaab.messages.model.EmailStatus;
import com.hisaab.messages.repository.InboxMessageRepository;
import com.hisaab.people.model.SettlementStatus;
import com.hisaab.people.repository.SettlementRepository;
import com.hisaab.reports.dto.DashboardCountsResponse;
import com.hisaab.reports.dto.DashboardResponse;
import com.hisaab.reports.dto.DashboardTotalsResponse;
import com.hisaab.reports.dto.MonthTotalResponse;
import com.hisaab.reports.dto.NetWorthPointResponse;
import com.hisaab.reports.dto.ReportResponse;
import com.hisaab.reports.dto.ReviewResponse;
import com.hisaab.reports.dto.TotalsResponse;
import com.hisaab.statements.model.ImportStatus;
import com.hisaab.statements.repository.StatementFileRepository;
import com.hisaab.statements.repository.StatementImportRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Service;

/** What the home screen and reports ask for, put together from the reporting services. */
@Service
public class DashboardService {
    private final AccountService accounts;
    private final ReportService reports;
    private final AlertRepository alerts;
    private final InboxMessageRepository emails;
    private final SettlementRepository settlements;
    private final StatementImportRepository statementImports;
    private final StatementFileRepository statementFiles;
    private final TotalsService totals;
    private final NetWorthService networth;
    private final ReviewService review;

    public DashboardService(AccountService accounts, ReportService reports, AlertRepository alerts, InboxMessageRepository emails,
                            SettlementRepository settlements, StatementImportRepository statementImports,
                            StatementFileRepository statementFiles, TotalsService totals, NetWorthService networth,
                            ReviewService review) {
        this.networth = networth;
        this.review = review;
        this.totals = totals;
        this.statementImports = statementImports;
        this.statementFiles = statementFiles;
        this.accounts = accounts;
        this.reports = reports;
        this.alerts = alerts;
        this.emails = emails;
        this.settlements = settlements;
    }

    public DashboardResponse dashboard(String month) {
        List<AccountResponse> list = accounts.list(false);
        TotalsResponse now = totals.of(list);
        return new DashboardResponse(new DashboardTotalsResponse(now.cash(), now.cardOutstanding(), now.cardLimit(), now.investments(),
                now.invested(), now.receivable(), now.netWorth()),
                list, report(month), reports.monthly(6),
                new DashboardCountsResponse(alerts.countByDismissedFalse(),
                        emails.countByStatus(EmailStatus.NEEDS_REVIEW) + emails.countByStatus(EmailStatus.ERROR),
                        settlements.countByStatus(SettlementStatus.SUGGESTED),
                        statementImports.countByStatus(ImportStatus.REVIEW),
                        statementFiles.countByImportStatus("NEEDS_PASSWORD")));
    }

    /** Month-by-month income and spending, ending at {@code until} (yyyy-MM, default this month). */
    public List<MonthTotalResponse> monthly(String until,
                                                  int months) {
        YearMonth end = until == null ? YearMonth.now(Money.ZONE) : YearMonth.parse(until);
        return reports.monthly(end, Math.max(1, Math.min(months, 24)));
    }

    /** Income/expense breakdown. {@code month} is yyyy-MM; alternatively pass from/to. */
    public ReportResponse report(String month,
                                      Instant from,
                                      Instant to) {
        if (from != null && to != null) return reports.report(from, to);
        return report(month);
    }

    private ReportResponse report(String month) {
        YearMonth ym = month == null || month.isBlank() ? YearMonth.now(Money.ZONE) : YearMonth.parse(month);
        return reports.report(ym.atDay(1).atStartOfDay(Money.ZONE).toInstant(),
                ym.plusMonths(1).atDay(1).atStartOfDay(Money.ZONE).toInstant());
    }

    // ---- net worth
    public List<NetWorthPointResponse> networth(String range) {
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
    public ReviewResponse review(Integer year) {
        return review.year(year == null ? LocalDate.now(Money.ZONE).getYear() : year);
    }
}
