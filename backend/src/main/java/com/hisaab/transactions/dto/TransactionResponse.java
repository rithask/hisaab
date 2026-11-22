package com.hisaab.transactions.dto;

import com.hisaab.categories.dto.CategoryResponse;
import com.hisaab.common.dto.AccountRefResponse;
import com.hisaab.common.dto.PersonRefResponse;
import com.hisaab.common.model.Direction;
import com.hisaab.transactions.model.Tag;
import com.hisaab.transactions.model.Transaction;
import com.hisaab.transactions.model.TransactionSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TransactionResponse(Long id, AccountRefResponse account, Direction direction, BigDecimal amount,
                                  BigDecimal originalAmount, String originalCurrency, boolean amountEstimated,
                                  Instant occurredAt, String counterparty, String description, String referenceNo,
                                  BigDecimal reportedBalance, CategoryResponse category, List<String> tags, String notes,
                                  TransactionSource source, Long rawEmailId, boolean transfer, Long linkedTxnId,
                                  AccountRefResponse linkedAccount, BigDecimal sharedAmount, boolean categorySuggested,
                                  /* name of the API token that added it, when an app did */ String addedBy,
                                  /* someone else paid (account is null): who, and what they paid altogether */
                                  PersonRefResponse paidBy, BigDecimal paidTotal,
                                  /* what's mine of it after splits and repayments; 0 for a loan or a repayment */
                                  BigDecimal myAmount,
                                  /* everyone's share of a payment that isn't mine at all (money I lent) */
                                  List<PersonRefResponse> lentTo,
                                  /* confirmed repayments on it: who paid me back (money in), whom I paid back (money out) */
                                  List<PersonRefResponse> paidBackBy, List<PersonRefResponse> paidBackTo) {
    public static TransactionResponse of(Transaction t, BigDecimal shared) {
        return of(t, shared, null, List.of(), List.of(), List.of());
    }

    public static TransactionResponse of(Transaction t, BigDecimal shared, BigDecimal myAmount, List<PersonRefResponse> lentTo,
                     List<PersonRefResponse> paidBackBy, List<PersonRefResponse> paidBackTo) {
        return new TransactionResponse(t.getId(), AccountRefResponse.of(t.getAccount()), t.getDirection(), t.getAmount(),
                t.getOriginalAmount(), t.getOriginalCurrency(), t.isAmountEstimated(), t.getOccurredAt(),
                t.getCounterparty(), t.getDescription(), t.getReferenceNo(), t.getReportedBalance(),
                CategoryResponse.of(t.getCategory()),
                t.getTags().stream().map(Tag::getName).sorted().toList(), t.getNotes(), t.getSource(),
                t.getRawEmail() == null ? null : t.getRawEmail().getId(), t.isTransfer(),
                t.getLinkedTxn() == null ? null : t.getLinkedTxn().getId(),
                t.getLinkedTxn() == null ? null : AccountRefResponse.of(t.getLinkedTxn().getAccount()), shared,
                t.isCategorySuggested() && t.getCategory() != null,
                t.getApiToken() == null ? null : t.getApiToken().getName(),
                t.getPaidBy() == null ? null : new PersonRefResponse(t.getPaidBy().getId(), t.getPaidBy().getName()), t.getPaidTotal(),
                myAmount, lentTo, paidBackBy, paidBackTo);
    }
}
