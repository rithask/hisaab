package com.hisaab.categories.controller;

import com.hisaab.categories.dto.CategoryRequest;
import com.hisaab.categories.dto.CategoryResponse;
import com.hisaab.categories.dto.RuleRequest;
import com.hisaab.categories.dto.RuleResponse;
import com.hisaab.categories.service.CategoryService;
import com.hisaab.transactions.service.TransactionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CategoryController {
    private final CategoryService categories;
    private final TransactionService transactions;

    public CategoryController(CategoryService categories, TransactionService transactions) {
        this.categories = categories;
        this.transactions = transactions;
    }

    @GetMapping("/categories")
    public List<CategoryResponse> categories() {
        return categories.categories();
    }

    @PostMapping("/categories")
    public CategoryResponse createCategory(@RequestBody CategoryRequest in) {
        return categories.createCategory(in);
    }

    @PutMapping("/categories/{id}")
    public CategoryResponse updateCategory(@PathVariable Long id, @RequestBody CategoryRequest in) {
        return categories.updateCategory(id, in);
    }

    @DeleteMapping("/categories/{id}")
    public void deleteCategory(@PathVariable Long id) {
        categories.deleteCategory(id);
    }

    @GetMapping("/tags")
    public List<String> tags() {
        return transactions.tagNames();
    }

    @GetMapping("/rules")
    public List<RuleResponse> rules() {
        return categories.rules();
    }

    @PostMapping("/rules")
    public RuleResponse createRule(@RequestBody RuleRequest in) {
        return categories.createRule(in);
    }

    @PutMapping("/rules/{id}")
    public RuleResponse updateRule(@PathVariable Long id, @RequestBody RuleRequest in) {
        return categories.updateRule(id, in);
    }

    @DeleteMapping("/rules/{id}")
    public void deleteRule(@PathVariable Long id) {
        categories.deleteRule(id);
    }
}
