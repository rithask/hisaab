package com.hisaab.categories.dto;

import com.hisaab.categories.model.RuleMatch;

public record RuleRequest(RuleMatch matchType, String pattern, Long categoryId, Integer priority) {}
