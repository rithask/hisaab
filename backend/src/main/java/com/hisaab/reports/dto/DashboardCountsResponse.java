package com.hisaab.reports.dto;

/** @param statementsToReview statements read and waiting for my review; @param statementsLocked ones that need a password */
public record DashboardCountsResponse(long alerts, long emailsToReview, long suggestedSettlements, long statementsToReview, long statementsLocked) {}
