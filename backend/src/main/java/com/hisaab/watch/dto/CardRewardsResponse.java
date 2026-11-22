package com.hisaab.watch.dto;

import java.math.BigDecimal;
import java.util.List;

public record CardRewardsResponse(long accountId, String account, String last4, boolean statementCycles, RewardProgramResponse program,
                                  List<RewardCycleResponse> cycles, BigDecimal yearEarned, BigDecimal yearValue, BigDecimal yearReceived,
                                  RewardPointsResponse points) {}
