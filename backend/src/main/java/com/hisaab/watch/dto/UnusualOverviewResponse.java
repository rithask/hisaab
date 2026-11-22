package com.hisaab.watch.dto;

import java.util.List;

public record UnusualOverviewResponse(List<UnusualItemResponse> items, List<RefundResponse> refunds) {}
