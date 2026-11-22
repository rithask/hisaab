package com.hisaab.auth.dto;

public record PendingLinkResponse(String email, String name, String provider, String username, boolean passwordRequired) {}
