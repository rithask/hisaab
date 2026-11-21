package com.hisaab.budgets;

import com.hisaab.common.Audit;
import com.hisaab.common.Money;
import com.hisaab.settings.FeatureService.Feature;
import com.hisaab.settings.RequiresFeature;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/** Budgets: a monthly limit per category (optional feature). */
@RestController
@RequestMapping("/api")
public class BudgetController {
    private final BudgetService budgets;

    public BudgetController(BudgetService budgets) {
        this.budgets = budgets;
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
}
