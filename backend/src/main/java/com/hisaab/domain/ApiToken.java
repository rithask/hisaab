package com.hisaab.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A token an app or shortcut uses to reach the API. Each has exactly one {@link Job} and the limits I chose
 * (categories, accounts, money in); only a hash of the token is stored. A revoked token is kept, so transactions
 * it added still say who added them.
 */
@Entity
@Getter
@Setter
public class ApiToken {
    public enum Job { SEND_SMS, READ_TRANSACTIONS, ADD_TRANSACTIONS, READ_BALANCES, READ_TOTALS }

    /** In a category limit: uncategorised transactions. */
    public static final String UNCATEGORISED = "none";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private Job job;

    private String tokenHash;

    private String hint;

    /** null = all; otherwise comma-separated ids (and {@link #UNCATEGORISED}); "" = nothing left. */
    @Column(columnDefinition = "text")
    private String categoryIds;

    /** null = all; otherwise comma-separated ids; "" = nothing left. */
    @Column(columnDefinition = "text")
    private String accountIds;

    private boolean moneyIn;

    private boolean includeNotes = true;

    @Column(updatable = false)
    private Instant createdAt = Instant.now();

    private Instant lastUsedAt;

    private Instant revokedAt;

    public boolean isActive() {
        return revokedAt == null;
    }

    /** The categories it's limited to, or null for all. */
    public Set<String> categoryLimit() {
        return split(categoryIds);
    }

    /** The accounts it's limited to, or null for all. */
    public Set<Long> accountLimit() {
        Set<String> s = split(accountIds);
        return s == null ? null : s.stream().map(Long::valueOf).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean allowsAccount(Long id) {
        Set<Long> s = accountLimit();
        return s == null || s.contains(id);
    }

    /** @param id the category, or null for uncategorised */
    public boolean allowsCategory(Long id) {
        Set<String> s = categoryLimit();
        return s == null || s.contains(id == null ? UNCATEGORISED : id.toString());
    }

    public static String join(Set<?> values) {
        return values == null ? null : values.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static Set<String> split(String v) {
        if (v == null) return null;
        return Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
