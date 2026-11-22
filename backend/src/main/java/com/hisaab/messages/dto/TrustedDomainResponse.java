package com.hisaab.messages.dto;

import java.time.Instant;

/** @param since when I trusted it */
public record TrustedDomainResponse(String domain, Instant since) {}
