package com.hisaab.domain;

/** Holder for the small enums used across the domain. */
public final class Enums {
    private Enums() {}

    public enum AccountType { BANK, CREDIT_CARD, CASH }

    public enum Institution { HDFC, AXIS, SBI, CANARA, OTHER }

    public enum Direction { DEBIT, CREDIT }

    /** PERSON: a friend paid, and this is my share of it (no account of mine; see Txn#paidBy). */
    public enum TxnSource { EMAIL, SMS, STATEMENT, MANUAL, ADJUSTMENT, PERSON }

    /** How a bank alert reached the app. */
    public enum Channel { EMAIL, SMS }

    public enum CategoryKind { EXPENSE, INCOME }

    public enum RuleMatch { MERCHANT, CONTAINS }

    public enum RuleSource { SYSTEM, MANUAL, LEARNED }

    public enum EmailStatus { PARSED, IGNORED, NEEDS_REVIEW, STATEMENT, DUPLICATE, ERROR }

    public enum SplitMethod { EQUAL, EXACT, PERCENT }

    public enum SettlementStatus { SUGGESTED, CONFIRMED, REJECTED }

    /** Which way a repayment went: IN, they paid me back; OUT, I paid them back. */
    public enum SettlementDirection { IN, OUT }

    public enum AssetType { STOCK, MF }

    public enum AlertType { BALANCE_MISMATCH }

    public enum ImportStatus { REVIEW, IMPORTED, CANCELLED }

    /** How a statement line relates to what's already recorded. */
    public enum LineKind { MATCH, POSSIBLE, NEW }

    /** What to do with a statement line: fill in the matched transaction, add it as new, or leave it out. */
    public enum LineDecision { MERGE, ADD, SKIP }
}
