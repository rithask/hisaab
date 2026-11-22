package com.hisaab.transactions.dto;

import com.hisaab.people.dto.ShareResponse;
import java.math.BigDecimal;
import java.util.List;

public record SplitResponse(BigDecimal total, BigDecimal othersOwe, BigDecimal myShare, List<ShareResponse> shares) {}
