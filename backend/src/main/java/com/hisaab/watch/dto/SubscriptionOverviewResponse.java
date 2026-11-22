package com.hisaab.watch.dto;

import java.math.BigDecimal;
import java.util.List;

public record SubscriptionOverviewResponse(List<SubscriptionResponse> items, List<SubscriptionAlertResponse> alerts, BigDecimal monthly, BigDecimal yearly,
                                           List<HiddenSubscriptionResponse> hidden) {}
