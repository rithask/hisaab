package com.hisaab.mail.parser;

import com.hisaab.domain.Enums.AccountType;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.Institution;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * What a bank parser extracted from an alert email.
 *
 * @param time        null when the email only carries a date (the email's received time is used instead)
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

    public ParsedTransaction withDirection(Direction d) {
        return new ParsedTransaction(institution, accountType, d, amount, currency, last4, date, time, counterparty,
                reference, balance, cardPayment, description);
    }

    public ParsedTransaction withInstitution(Institution i) {
        return new ParsedTransaction(i, accountType, direction, amount, currency, last4, date, time, counterparty,
                reference, balance, cardPayment, description);
    }
}
