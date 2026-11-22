package com.hisaab.categories.dto;

import com.hisaab.categories.model.Category;
import com.hisaab.categories.model.CategoryKind;

public record CategoryResponse(Long id, String name, CategoryKind kind, String color) {
    public static CategoryResponse of(Category c) {
        return c == null ? null : new CategoryResponse(c.getId(), c.getName(), c.getKind(), c.getColor());
    }
}
