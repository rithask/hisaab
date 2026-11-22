package com.hisaab.settings.dto;

import java.util.Map;

public record SettingsResponse(String ownerName, MailStatusResponse mail, boolean showInvestmentsOnHome, Map<String, Boolean> features) {}
