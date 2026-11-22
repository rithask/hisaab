package com.hisaab.reports.controller;

import com.hisaab.reports.service.DashboardService;
import com.hisaab.reports.dto.DashboardResponse;
import com.hisaab.reports.dto.MonthTotalResponse;
import com.hisaab.reports.dto.NetWorthPointResponse;
import com.hisaab.reports.dto.ReportResponse;
import com.hisaab.reports.dto.ReviewResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final DashboardService reports;

    public DashboardController(DashboardService reports) {
        this.reports = reports;
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard(@RequestParam(required = false) String month) {
        return reports.dashboard(month);
    }

    /** Month-by-month income and spending, ending at {@code until} (yyyy-MM, default this month). */
    @GetMapping("/reports/monthly")
    public List<MonthTotalResponse> monthly(@RequestParam(required = false) String until,
                                                  @RequestParam(defaultValue = "6") int months) {
        return reports.monthly(until, months);
    }

    /** Income/expense breakdown. {@code month} is yyyy-MM; alternatively pass from/to. */
    @GetMapping("/reports")
    public ReportResponse report(@RequestParam(required = false) String month,
                                      @RequestParam(required = false) Instant from,
                                      @RequestParam(required = false) Instant to) {
        return reports.report(month, from, to);
    }

    // ---- net worth
    @GetMapping("/networth")
    public List<NetWorthPointResponse> networth(@RequestParam(defaultValue = "6m") String range) {
        return reports.networth(range);
    }

    // ---- year in review
    @GetMapping("/review")
    public ReviewResponse review(@RequestParam(required = false) Integer year) {
        return reports.review(year);
    }
}
