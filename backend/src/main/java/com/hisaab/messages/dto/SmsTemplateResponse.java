package com.hisaab.messages.dto;

import java.time.Instant;

public record SmsTemplateResponse(Long id, String skeleton, String sample, String institution, String direction, int hits,
                                  Instant lastUsedAt, String channel, String kind, int corrections, boolean paused,
                                  boolean auto) {}
