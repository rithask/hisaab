package com.hisaab.dataapi.dto;

import com.hisaab.dataapi.model.TokenJob;
import java.time.Instant;
import java.util.List;

public record TokenResponse(Long id, String name, TokenJob job, String hint, List<String> categories, List<Long> accounts,
                            boolean moneyIn, boolean includeNotes, Instant createdAt, Instant lastUsedAt) {}
