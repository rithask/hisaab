package com.hisaab.people.dto;

import java.math.BigDecimal;

public record SplitParticipantRequest(Long personId, BigDecimal value) {}
