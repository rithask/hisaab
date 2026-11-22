package com.hisaab.settings.dto;

/** What each part would remove, so the sheet can say it before I agree. */
public record ResetPreviewResponse(long accounts, long transactions, long people, long statements, long holdings, long messages,
                                   long learned, long categories, long settings, long tokens, boolean pocketIdLinked,
                                   boolean backupsOn) {}
