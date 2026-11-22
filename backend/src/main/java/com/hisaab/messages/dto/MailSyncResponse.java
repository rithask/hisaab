package com.hisaab.messages.dto;

public record MailSyncResponse(int fetched, int processed, String error) {}
