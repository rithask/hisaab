package com.hisaab.settings.model;

/** A part of the app's data a reset can clear. */
public enum ResetPart {
    /** Accounts, transactions, people, statements, investments, budgets, messages… */
    DATA,
    /** Rules, payee names, SMS formats and everything else learned from corrections. */
    LEARNED,
    /** My categories; the built-in ones come back. */
    CATEGORIES,
    /** Feature switches, my name, Home options. */
    SETTINGS,
    /** API tokens and the Pocket ID link. */
    CONNECTIONS
}
