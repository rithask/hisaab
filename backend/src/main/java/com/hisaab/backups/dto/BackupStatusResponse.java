package com.hisaab.backups.dto;

import java.time.Instant;
import java.util.List;

/** @param stale the last good backup is older than two days (or there's none while the feature is on) */
public record BackupStatusResponse(boolean enabled, String dir, int keep, Instant lastOk, String lastError, Instant lastErrorAt,
                                   boolean stale, List<BackupResponse> backups) {}
