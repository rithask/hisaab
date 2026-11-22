package com.hisaab.messages.dto;

import com.hisaab.common.model.Direction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record SmsParsedResponse(String bank, String accountType, Direction direction, BigDecimal amount, String currency,
                                String last4, LocalDate date, LocalTime time, String counterparty, String reference,
                                BigDecimal balance) {}
