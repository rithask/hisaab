package com.hisaab.categories.controller;

import com.hisaab.auth.util.AuthLog;
import com.hisaab.categories.model.Category;
import com.hisaab.categories.model.CategoryRule;
import com.hisaab.categories.repository.CategoryRepository;
import com.hisaab.categories.repository.CategoryRuleRepository;
import com.hisaab.categories.service.CategorizationService;
import com.hisaab.common.dto.Dtos.CategoryDto;
import com.hisaab.common.dto.Dtos.RuleDto;
import com.hisaab.common.model.Enums.CategoryKind;
import com.hisaab.common.model.Enums.RuleMatch;
import com.hisaab.common.model.Enums.RuleSource;
import com.hisaab.common.util.Audit;
import com.hisaab.transactions.model.Tag;
import com.hisaab.transactions.repository.TagRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

import static com.hisaab.common.exception.NotFound.notFound;

@RestController
@RequestMapping("/api")
public class CategoryController {
    private final CategoryRepository categories;
    private final CategoryRuleRepository rules;
    private final TagRepository tags;
    private final CategorizationService categorizer;

    public CategoryController(CategoryRepository categories, CategoryRuleRepository rules, TagRepository tags,
                              CategorizationService categorizer) {
        this.categorizer = categorizer;
        this.categories = categories;
        this.rules = rules;
        this.tags = tags;
    }

    @GetMapping("/categories")
    public List<CategoryDto> categories() {
        return categories.findAllByOrderByKindAscNameAsc().stream().map(CategoryDto::of).toList();
    }

    public record CategoryInput(String name, CategoryKind kind, String color) {}

    @PostMapping("/categories")
    public CategoryDto createCategory(@RequestBody CategoryInput in) {
        return CategoryDto.of(categories.save(apply(new Category(), in)));
    }

    @PutMapping("/categories/{id}")
    public CategoryDto updateCategory(@PathVariable Long id, @RequestBody CategoryInput in) {
        return CategoryDto.of(categories.save(apply(categories.findById(id).orElseThrow(), in)));
    }

    @DeleteMapping("/categories/{id}")
    public void deleteCategory(@PathVariable Long id) {
        categories.findById(id).ifPresent(c -> Audit.log("Category deleted id={} name=\"{}\"", id, AuthLog.clean(c.getName(), 60)));
        categories.deleteById(id);
    }

    private static Category apply(Category c, CategoryInput in) {
        if (in.name() == null || in.name().isBlank()) throw new IllegalArgumentException("Name is required");
        if (in.kind() == null) throw new IllegalArgumentException("Kind is required");
        c.setName(in.name().trim());
        c.setKind(in.kind());
        if (in.color() != null && in.color().matches("#[0-9a-fA-F]{6}")) c.setColor(in.color());
        return c;
    }

    @GetMapping("/tags")
    public List<String> tags() {
        return tags.findAllByOrderByNameAsc().stream().map(Tag::getName).toList();
    }

    @GetMapping("/rules")
    @Transactional(readOnly = true)
    public List<RuleDto> rules() {
        return rules.findAllByOrderBySourceAscPatternAsc().stream().map(RuleDto::of).toList();
    }

    public record RuleInput(RuleMatch matchType, String pattern, Long categoryId, Integer priority) {}

    @PostMapping("/rules")
    @Transactional
    public RuleDto createRule(@RequestBody RuleInput in) {
        CategoryRule r = new CategoryRule();
        r.setSource(RuleSource.MANUAL);
        CategoryRule saved = rules.save(apply(r, in));
        categorizer.ruleSet(saved);
        return RuleDto.of(saved);
    }

    @PutMapping("/rules/{id}")
    @Transactional
    public RuleDto updateRule(@PathVariable Long id, @RequestBody RuleInput in) {
        CategoryRule r = rules.findById(id).orElseThrow(notFound("Rule"));
        categorizer.ruleDeleted(r);
        CategoryRule saved = rules.save(apply(r, in));
        categorizer.ruleSet(saved);
        return RuleDto.of(saved);
    }

    @DeleteMapping("/rules/{id}")
    @Transactional
    public void deleteRule(@PathVariable Long id) {
        rules.findById(id).ifPresent(r -> {
            Audit.log("Category rule deleted id={}", id);
            categorizer.ruleDeleted(r);
            rules.delete(r);
        });
    }

    private CategoryRule apply(CategoryRule r, RuleInput in) {
        if (in.pattern() == null || in.pattern().isBlank()) throw new IllegalArgumentException("Pattern is required");
        r.setMatchType(in.matchType() == null ? RuleMatch.CONTAINS : in.matchType());
        r.setPattern(in.pattern().trim().toLowerCase(Locale.ROOT));
        r.setCategory(categories.findById(in.categoryId()).orElseThrow(() -> new IllegalArgumentException("Unknown category")));
        r.setPriority(in.priority() != null ? in.priority() : r.getMatchType() == RuleMatch.MERCHANT ? 1000 : 500 + r.getPattern().length());
        return r;
    }
}
