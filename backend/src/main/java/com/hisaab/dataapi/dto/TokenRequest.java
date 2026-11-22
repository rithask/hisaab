package com.hisaab.dataapi.dto;

import com.hisaab.dataapi.model.TokenJob;
import java.util.List;

/**
 * What I chose for a token. Limits that don't apply to its job are ignored.
 *
 * @param categories category ids (and "none" for uncategorised) it may see or use; null for all
 * @param accounts   account ids it may see or add to; null for all
 * @param moneyIn    also money coming in (otherwise money out only)
 */
public record TokenRequest(String name, TokenJob job, List<String> categories, List<Long> accounts, Boolean moneyIn, Boolean includeNotes) {}
