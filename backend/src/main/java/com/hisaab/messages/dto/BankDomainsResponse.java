package com.hisaab.messages.dto;

import java.util.List;

/** A bank's own email domains, built into the app (always trusted, can't be removed). */
public record BankDomainsResponse(String institution, String name, List<String> domains) {}
