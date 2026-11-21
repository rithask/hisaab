package com.hisaab.web;

import com.hisaab.config.Audit;
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
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** Optional features: budgets, upcoming bills, net worth history, year in review and backups. */
@RestController
@RequestMapping("/api")
public class FeaturesController {
    private final BudgetService budgets;
    private final BillService bills;
    private final NetWorthService networth;
    private final ReviewService review;
    private final BackupService backups;

    public FeaturesController(BudgetService budgets, BillService bills, NetWorthService networth,
                              ReviewService review, BackupService backups) {
        this.budgets = budgets;
        this.bills = bills;
        this.networth = networth;
        this.review = review;
        this.backups = backups;
    }

    // ---- budgets
    @GetMapping("/budgets")
    @RequiresFeature(Feature.BUDGETS)
    public List<BudgetService.Budget> budgets(@RequestParam(required = false) String month) {
        return budgets.month(month == null ? YearMonth.now(Money.ZONE) : YearMonth.parse(month));
    }

    public record BudgetIn(BigDecimal limit) {}

    @PutMapping("/budgets/{categoryId}")
    @RequiresFeature(Feature.BUDGETS)
    public void setBudget(@PathVariable long categoryId, @RequestBody BudgetIn in) {
        budgets.set(categoryId, in.limit());
    }

    @DeleteMapping("/budgets/{categoryId}")
    @RequiresFeature(Feature.BUDGETS)
    public void removeBudget(@PathVariable long categoryId) {
        Audit.log("Budget removed category={}", categoryId);
        budgets.remove(categoryId);
    }

    // ---- bills
    @GetMapping("/bills")
    public List<BillService.Bill> bills(@RequestParam(required = false) Integer within) {
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

    // ---- backups
    @GetMapping("/backups")
    @RequiresFeature(Feature.BACKUPS)
    public BackupService.Status backupStatus() throws IOException {
        return backups.status();
    }

    @PostMapping("/backups")
    @RequiresFeature(Feature.BACKUPS)
    public BackupService.Backup backupNow() throws IOException {
        Audit.log("Backup started by hand");
        return backups.run();
    }

    @GetMapping("/backups/{file}")
    @RequiresFeature(Feature.BACKUPS)
    public ResponseEntity<Resource> download(@PathVariable String file, jakarta.servlet.http.HttpServletRequest req) {
        Path p = backups.file(file);
        // a full copy of the database leaving the server
        Audit.log("Backup downloaded file={} ip={}", p.getFileName(), req.getRemoteAddr());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .body(new FileSystemResource(p));
    }
}
