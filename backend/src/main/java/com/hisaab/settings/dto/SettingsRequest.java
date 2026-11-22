package com.hisaab.settings.dto;

import java.util.Map;

public record SettingsRequest(String ownerName, Boolean showInvestmentsOnHome, Map<String, Boolean> features) {}
