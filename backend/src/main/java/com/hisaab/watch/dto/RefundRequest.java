package com.hisaab.watch.dto;

import java.time.LocalDate;

public record RefundRequest(long txnId, LocalDate expectedBy) {}
