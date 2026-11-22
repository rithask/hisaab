package com.hisaab.settings.dto;

public record MailStatusResponse(boolean configured, String lastSync, String lastError) {}
