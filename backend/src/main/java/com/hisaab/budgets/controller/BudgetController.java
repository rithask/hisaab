package com.hisaab.budgets.controller;

import com.hisaab.budgets.dto.BudgetRequest;
import com.hisaab.budgets.dto.BudgetResponse;
import com.hisaab.settings.model.Feature;
import com.hisaab.budgets.service.BudgetService;
import com.hisaab.common.util.Audit;
import com.hisaab.common.util.Money;
import com.hisaab.settings.service.RequiresFeature;
import org.springframework.web.bind.annotation.*;

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
    public List<BudgetResponse> budgets(@RequestParam(required = false) String month) {
        return budgets.month(month == null ? YearMonth.now(Money.ZONE) : YearMonth.parse(month));
    }

    @PutMapping("/budgets/{categoryId}")
    @RequiresFeature(Feature.BUDGETS)
    public void setBudget(@PathVariable long categoryId, @RequestBody BudgetRequest in) {
        budgets.set(categoryId, in.limit());
    }

    @DeleteMapping("/budgets/{categoryId}")
    @RequiresFeature(Feature.BUDGETS)
    public void removeBudget(@PathVariable long categoryId) {
        Audit.log("Budget removed category={}", categoryId);
        budgets.remove(categoryId);
    }
}
