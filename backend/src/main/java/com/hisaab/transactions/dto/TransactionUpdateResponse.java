package com.hisaab.transactions.dto;

public record TransactionUpdateResponse(TransactionResponse txn, int similarCount, int renamedCount, boolean learnedWording) {}
