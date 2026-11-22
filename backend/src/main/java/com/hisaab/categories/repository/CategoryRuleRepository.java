package com.hisaab.categories.repository;

import com.hisaab.categories.model.CategoryRule;
import com.hisaab.common.model.Enums.RuleMatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRuleRepository extends JpaRepository<CategoryRule, Long> {
    Optional<CategoryRule> findByMatchTypeAndPattern(RuleMatch matchType, String pattern);

    List<CategoryRule> findByMatchTypeOrderByPriorityDesc(RuleMatch matchType);

    List<CategoryRule> findAllByOrderBySourceAscPatternAsc();
}
