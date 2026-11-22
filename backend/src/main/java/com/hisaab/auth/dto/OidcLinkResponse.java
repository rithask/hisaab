package com.hisaab.auth.dto;

import java.time.Instant;

public record OidcLinkResponse(boolean linked, String email, Instant linkedAt) {}
