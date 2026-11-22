package com.hisaab.watch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record UnusualItemResponse(String key, long txnId, String name, Long accountId, String account, BigDecimal amount, Instant at,
                                  List<UnusualReasonResponse> reasons) {}
