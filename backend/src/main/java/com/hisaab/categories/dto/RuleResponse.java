package com.hisaab.categories.dto;

import com.hisaab.categories.model.CategoryRule;
import com.hisaab.categories.model.RuleMatch;
import com.hisaab.categories.model.RuleSource;

public record RuleResponse(Long id, RuleMatch matchType, String pattern, CategoryResponse category, RuleSource source,
                           int priority, int hits) {
    public static RuleResponse of(CategoryRule rule) {
        return new RuleResponse(rule.getId(), rule.getMatchType(), rule.getPattern(),
                CategoryResponse.of(rule.getCategory()),
                rule.getSource(), rule.getPriority(), rule.getHits());
    }
}
