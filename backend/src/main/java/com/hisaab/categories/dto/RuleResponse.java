package com.hisaab.categories.dto;

import com.hisaab.categories.model.CategoryRule;
import com.hisaab.categories.model.RuleMatch;
import com.hisaab.categories.model.RuleSource;

public record RuleResponse(Long id, RuleMatch matchType, String pattern, CategoryResponse category, RuleSource source,
                           int priority, int hits) {
    public static RuleResponse of(CategoryRule r) {
        return new RuleResponse(r.getId(), r.getMatchType(), r.getPattern(), CategoryResponse.of(r.getCategory()),
                r.getSource(), r.getPriority(), r.getHits());
    }
}
