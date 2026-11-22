package com.hisaab.people.dto;

import com.hisaab.transactions.dto.TransactionResponse;
import java.util.List;

/** @param paidForMe things they paid for, with my share as the amount */
public record PersonDetailResponse(PersonResponse person, List<ShareLineResponse> shares, List<SettlementResponse> settlements,
                                   List<TransactionResponse> paidForMe) {}
