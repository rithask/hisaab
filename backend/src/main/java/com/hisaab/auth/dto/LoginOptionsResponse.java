package com.hisaab.auth.dto;

/** What the sign-in screen should offer. */
public record LoginOptionsResponse(boolean passwordLogin, OidcOptionResponse oidc, boolean autoRedirect) {}
