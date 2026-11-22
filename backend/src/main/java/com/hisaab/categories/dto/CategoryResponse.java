package com.hisaab.categories.dto;

import com.hisaab.categories.model.Category;
import com.hisaab.categories.model.CategoryKind;

public record CategoryResponse(Long id, String name, CategoryKind kind, String color) {
    public static CategoryResponse of(Category category) {
        return category == null ? null : new CategoryResponse(category.getId(), category.getName(),
                category.getKind(), category.getColor());
    }
}
