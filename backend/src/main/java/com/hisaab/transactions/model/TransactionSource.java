package com.hisaab.transactions.model;

/** PERSON: a friend paid, and this is my share of it (no account of mine; see Transaction#paidBy). */
public enum TransactionSource { EMAIL, SMS, STATEMENT, MANUAL, ADJUSTMENT, PERSON }
