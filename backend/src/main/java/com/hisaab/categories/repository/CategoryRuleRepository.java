package com.hisaab.categories.repository;

import com.hisaab.categories.model.RuleMatch;
import com.hisaab.categories.model.CategoryRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRuleRepository extends JpaRepository<CategoryRule, Long> {
    Optional<CategoryRule> findByMatchTypeAndPattern(RuleMatch matchType, String pattern);

    List<CategoryRule> findByMatchTypeOrderByPriorityDesc(RuleMatch matchType);

    List<CategoryRule> findAllByOrderBySourceAscPatternAsc();
}
