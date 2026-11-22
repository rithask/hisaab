package com.hisaab.messages.parser;

import com.hisaab.common.model.Enums.AccountType;
import com.hisaab.common.model.Enums.Direction;
import com.hisaab.common.model.Enums.Institution;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * What a bank parser extracted from an alert email.
 *
 * @param date        null when the alert has none or it can't be read (the email's received time is used)
 * @param time        null when the email only carries a date, or has no readable date
 * @param balance     available balance reported in the email, if present
 * @param cardPayment true when this is a payment towards a credit card bill (a transfer, not income)
 */
public record ParsedTransaction(
        Institution institution,
        AccountType accountType,
        Direction direction,
        BigDecimal amount,
        String currency,
        String last4,
        LocalDate date,
        LocalTime time,
        String counterparty,
        String reference,
        BigDecimal balance,
        boolean cardPayment,
        String description) {

    /** A time without its date means nothing (the email's received time is used, and marked as a guess). */
    public ParsedTransaction {
        if (date == null) time = null;
    }

    public ParsedTransaction withDirection(Direction d) {
        return new ParsedTransaction(institution, accountType, d, amount, currency, last4, date, time, counterparty,
                reference, balance, cardPayment, description);
    }

    public ParsedTransaction withInstitution(Institution i) {
        return new ParsedTransaction(i, accountType, direction, amount, currency, last4, date, time, counterparty,
                reference, balance, cardPayment, description);
    }
}
