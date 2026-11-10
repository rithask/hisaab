package com.hisaab.repo;

import com.hisaab.domain.CategoryRule;
import com.hisaab.domain.Enums.RuleMatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRuleRepo extends JpaRepository<CategoryRule, Long> {
    Optional<CategoryRule> findByMatchTypeAndPattern(RuleMatch matchType, String pattern);

    List<CategoryRule> findByMatchTypeOrderByPriorityDesc(RuleMatch matchType);

    List<CategoryRule> findAllByOrderBySourceAscPatternAsc();
}
