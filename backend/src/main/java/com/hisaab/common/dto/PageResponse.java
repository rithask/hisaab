package com.hisaab.common.dto;

import java.util.List;

/** One page of a longer list. */
public record PageResponse<T>(List<T> items, int page, int size, long total, boolean hasMore) {}
