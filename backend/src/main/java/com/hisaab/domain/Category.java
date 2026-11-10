package com.hisaab.domain;

import com.hisaab.domain.Enums.CategoryKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class Category {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private CategoryKind kind;

    private String color = "#64748b";

    private boolean system;
}
