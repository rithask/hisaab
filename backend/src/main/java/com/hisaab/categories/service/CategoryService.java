package com.hisaab.categories.service;

import com.hisaab.auth.util.AuthLog;
import com.hisaab.categories.dto.CategoryRequest;
import com.hisaab.categories.dto.CategoryResponse;
import com.hisaab.categories.dto.RuleRequest;
import com.hisaab.categories.dto.RuleResponse;
import com.hisaab.categories.model.Category;
import com.hisaab.categories.model.CategoryRule;
import com.hisaab.categories.model.RuleMatch;
import com.hisaab.categories.model.RuleSource;
import com.hisaab.categories.repository.CategoryRepository;
import com.hisaab.categories.repository.CategoryRuleRepository;
import com.hisaab.common.exception.BadRequestException;
import com.hisaab.common.util.Audit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

import static com.hisaab.common.exception.NotFound.notFound;

/** My categories, and the rules that put transactions in them. */
@Service
public class CategoryService {
    private final CategoryRepository categories;
    private final CategoryRuleRepository rules;
    private final CategorizationService categorizer;

    public CategoryService(CategoryRepository categories, CategoryRuleRepository rules, CategorizationService categorizer) {
        this.categories = categories;
        this.rules = rules;
        this.categorizer = categorizer;
    }

    public List<CategoryResponse> categories() {
        return categories.findAllByOrderByKindAscNameAsc().stream().map(CategoryResponse::of).toList();
    }

    public CategoryResponse createCategory(CategoryRequest in) {
        return CategoryResponse.of(categories.save(apply(new Category(), in)));
    }

    public CategoryResponse updateCategory(Long id, CategoryRequest in) {
        return CategoryResponse.of(categories.save(apply(categories.findById(id).orElseThrow(notFound("Category")), in)));
    }

    public void deleteCategory(Long id) {
        categories.findById(id).ifPresent(c -> Audit.log("Category deleted id={} name=\"{}\"", id, AuthLog.clean(c.getName(), 60)));
        categories.deleteById(id);
    }

    private static Category apply(Category category, CategoryRequest in) {
        if (in.name() == null || in.name().isBlank()) throw new BadRequestException("Name is required");
        if (in.kind() == null) throw new BadRequestException("Kind is required");
        category.setName(in.name().trim());
        category.setKind(in.kind());
        if (in.color() != null && in.color().matches("#[0-9a-fA-F]{6}")) category.setColor(in.color());
        return category;
    }

    @Transactional(readOnly = true)
    public List<RuleResponse> rules() {
        return rules.findAllByOrderBySourceAscPatternAsc().stream().map(RuleResponse::of).toList();
    }

    @Transactional
    public RuleResponse createRule(RuleRequest in) {
        CategoryRule rule = new CategoryRule();
        rule.setSource(RuleSource.MANUAL);
        CategoryRule saved = rules.save(apply(rule, in));
        categorizer.ruleSet(saved);
        return RuleResponse.of(saved);
    }

    @Transactional
    public RuleResponse updateRule(Long id, RuleRequest in) {
        CategoryRule rule = rules.findById(id).orElseThrow(notFound("Rule"));
        categorizer.ruleDeleted(rule);
        CategoryRule saved = rules.save(apply(rule, in));
        categorizer.ruleSet(saved);
        return RuleResponse.of(saved);
    }

    @Transactional
    public void deleteRule(Long id) {
        rules.findById(id).ifPresent(rule -> {
            Audit.log("Category rule deleted id={}", id);
            categorizer.ruleDeleted(rule);
            rules.delete(rule);
        });
    }

    private CategoryRule apply(CategoryRule rule, RuleRequest in) {
        if (in.pattern() == null || in.pattern().isBlank()) throw new BadRequestException("Pattern is required");
        rule.setMatchType(in.matchType() == null ? RuleMatch.CONTAINS : in.matchType());
        rule.setPattern(in.pattern().trim().toLowerCase(Locale.ROOT));
        rule.setCategory(categories.findById(in.categoryId()).orElseThrow(() -> new BadRequestException("Unknown category")));
        rule.setPriority(in.priority() != null ? in.priority()
                : rule.getMatchType() == RuleMatch.MERCHANT ? 1000 : 500 + rule.getPattern().length());
        return rule;
    }
}
