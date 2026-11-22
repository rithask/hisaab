package com.hisaab.categories.model;

import com.hisaab.common.model.Enums.RuleMatch;
import com.hisaab.common.model.Enums.RuleSource;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class CategoryRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private RuleMatch matchType;

    private String pattern;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    private RuleSource source;

    private int priority;

    private int hits;
}
