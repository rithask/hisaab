package com.hisaab.investments.dto;

import java.util.List;

public record HoldingImportResponse(int imported, int removed, List<String> skipped) {}
