package com.hisaab.backups.dto;

import java.time.Instant;

public record BackupResponse(String file, long sizeBytes, Instant createdAt) {}
