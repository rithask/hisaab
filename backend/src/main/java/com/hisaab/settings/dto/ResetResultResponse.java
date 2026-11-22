package com.hisaab.settings.dto;

/** @param backup the backup taken just before, or null when I chose to go without */
public record ResetResultResponse(String backup) {}
