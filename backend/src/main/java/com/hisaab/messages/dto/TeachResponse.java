package com.hisaab.messages.dto;

public record TeachResponse(InboxMessageResponse message, Long txnId, boolean learnedFormat, boolean learnedAccount, int alsoFixed, int conflicts,
                            boolean pausedFormat, boolean mergedFormat, String category, boolean categoryByWording) {}
