package com.hisaab.transactions.model;

/** PERSON: a friend paid, and this is my share of it (no account of mine; see Txn#paidBy). */
public enum TxnSource { EMAIL, SMS, STATEMENT, MANUAL, ADJUSTMENT, PERSON }
