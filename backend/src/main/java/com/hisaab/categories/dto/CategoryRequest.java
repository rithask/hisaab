package com.hisaab.categories.dto;

import com.hisaab.categories.model.CategoryKind;

public record CategoryRequest(String name, CategoryKind kind, String color) {}
