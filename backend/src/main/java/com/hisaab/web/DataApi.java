package com.hisaab.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * The Data API's replies ({@code /api/v1}). They're a contract with other apps, separate from the screens' DTOs:
 * snake_case names, every key always present ({@code null} when empty), ids as strings, money as
 * {@code {value, currency}} with the value as a decimal string, times with their offset. Changing a shape here
 * is a major release (docs/versioning.md).
 */
public final class DataApi {
    private DataApi() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Amount(String value, String currency) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CategoryRef(String id, String name) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AccountRef(String id, String name, String type, String last4) {}

    /**
     * @param amount          the whole payment, in rupees (as on the bank's statement, before any split)
     * @param originalAmount  what was charged in a foreign currency, when it was; otherwise null
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Transaction(String id, String direction, String occurredAt, Amount amount, Amount originalAmount,
                              String payee, CategoryRef category, AccountRef account, String notes, List<String> tags) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page<T>(List<T> data, boolean hasMore, String nextCursor) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ListOf<T>(List<T> data) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Category(String id, String name, String kind) {}

    /** @param key the category or account id, or the month (2026-11) or day (2026-11-12); null for uncategorised */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Group(String key, String name, Amount total, long count) {}

    /** @param to the last day included */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(String from, String to, String direction, String groupBy, Amount total, long count, List<Group> groups) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Card(Amount limit, Amount available, String dueDate, Long daysToDue) {}

    /** @param balance for a bank or cash account what's in it; for a card what's owed on it */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Balance(AccountRef account, Amount balance, Card card) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Balances(List<Balance> data, String asOf) {}

    /**
     * A transaction an app adds. Only {@code amount} and {@code account} are required.
     *
     * @param amount      positive, in rupees, at most two decimals; a JSON string ("60.00") or number
     * @param direction   debit (default) or credit
     * @param category    a category id or name; left out, the app picks one from the payee as usual
     * @param occurredAt  RFC 3339, e.g. 2026-11-13T17:05:00+05:30; left out, now
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NewTransaction(java.math.BigDecimal amount, String direction, String account, String payee,
                                 String category, String occurredAt, String notes) {}

    /**
     * A bank SMS a phone forwards.
     *
     * @param sender      the sender id (e.g. AX-HDFCBK), if known; it helps recognise the bank
     * @param receivedAt  RFC 3339; left out, now
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NewSms(String text, String sender, String receivedAt) {}

    /**
     * @param status      parsed, duplicate, needs_review, ignored or error
     * @param message     one line saying what happened, e.g. for a phone notification
     * @param transaction what was recorded, if anything
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SmsReceived(String status, String message, Transaction transaction) {}
}
