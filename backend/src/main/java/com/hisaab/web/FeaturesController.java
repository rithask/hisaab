package com.hisaab.web;

import com.hisaab.features.*;
import com.hisaab.features.FeatureService.Feature;
import com.hisaab.service.Money;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** Optional features: budgets, upcoming bills, net worth history, year in review and backups. */
@RestController
@RequestMapping("/api")
public class FeaturesController {
    private final FeatureService features;
    private final BudgetService budgets;
    private final BillService bills;
    private final NetWorthService networth;
    private final ReviewService review;
    private final BackupService backups;

    public FeaturesController(FeatureService features, BudgetService budgets, BillService bills, NetWorthService networth,
                              ReviewService review, BackupService backups) {
        this.features = features;
        this.budgets = budgets;
        this.bills = bills;
        this.networth = networth;
        this.review = review;
        this.backups = backups;
    }

    private void require(Feature f) {
        features.require(f);
    }

    // ---- budgets
    @GetMapping("/budgets")
    public List<BudgetService.Budget> budgets(@RequestParam(required = false) String month) {
        require(Feature.BUDGETS);
        return budgets.month(month == null ? YearMonth.now(Money.ZONE) : YearMonth.parse(month));
    }

    public record BudgetIn(BigDecimal limit) {}

    @PutMapping("/budgets/{categoryId}")
    public void setBudget(@PathVariable long categoryId, @RequestBody BudgetIn in) {
        require(Feature.BUDGETS);
        budgets.set(categoryId, in.limit());
    }

    @DeleteMapping("/budgets/{categoryId}")
    public void removeBudget(@PathVariable long categoryId) {
        budgets.remove(categoryId);
    }

    // ---- bills
    @GetMapping("/bills")
    public List<BillService.Bill> bills(@RequestParam(required = false) Integer within) {
        require(Feature.BILLS);
        return within == null ? bills.all() : bills.upcoming(within);
    }

    public record BillKey(String key) {}

    @PostMapping("/bills/ignore")
    public void notABill(@RequestBody BillKey in) {
        bills.ignore(in.key());
    }

    @PostMapping("/bills/unignore")
    public void isABill(@RequestBody BillKey in) {
        bills.unignore(in.key());
    }

    // ---- net worth
    @GetMapping("/networth")
    public List<NetWorthService.Point> networth(@RequestParam(defaultValue = "6m") String range) {
        require(Feature.NETWORTH);
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
        require(Feature.REVIEW);
        return review.year(year == null ? LocalDate.now(Money.ZONE).getYear() : year);
    }

    // ---- backups
    @GetMapping("/backups")
    public BackupService.Status backupStatus() throws IOException {
        return backups.status();
    }

    @PostMapping("/backups")
    public BackupService.Backup backupNow() throws IOException {
        return backups.run();
    }

    @GetMapping("/backups/{file}")
    public ResponseEntity<Resource> download(@PathVariable String file) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .body(new FileSystemResource(backups.file(file)));
    }
}
