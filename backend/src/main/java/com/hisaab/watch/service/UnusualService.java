package com.hisaab.watch.service;

import com.hisaab.watch.dto.RefundResponse;
import com.hisaab.watch.dto.UnusualItemResponse;
import com.hisaab.watch.dto.UnusualOverviewResponse;
import com.hisaab.watch.dto.UnusualReasonResponse;
import com.hisaab.common.util.Money;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.hisaab.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Unusual activity: recent payments that look wrong or risky, worked out from my own history, plus refunds I'm
 * waiting for. Nothing is blocked or changed; each item is something to glance at and either open or mark fine.
 */
@Service
public class UnusualService {
    /** How far back to look for things to flag. History for "normal" goes back 400 days. */
    static final int RECENT_DAYS = 60;
    static final BigDecimal NEW_PAYEE_BIG = new BigDecimal("10000");
    static final BigDecimal LATE_NIGHT_MIN = new BigDecimal("2000");
    static final BigDecimal MERCHANT_MIN = new BigDecimal("500");

    private final NamedParameterJdbcTemplate jdbc;
    private final WatchDismissals dismissals;

    public UnusualService(NamedParameterJdbcTemplate jdbc, WatchDismissals dismissals) {
        this.jdbc = jdbc;
        this.dismissals = dismissals;
    }

    private record Row(long id, long accountId, String account, BigDecimal amount, Instant at, boolean timeEstimated,
                       String key, String name, String currency, BigDecimal originalAmount) {}

    public UnusualOverviewResponse overview() {
        LocalDate today = LocalDate.now(Money.ZONE);
        Set<String> dismissed = dismissals.all();
        List<UnusualItemResponse> items = new ArrayList<>();
        for (UnusualItemResponse i : scan(today)) if (!dismissed.contains(i.key())) items.add(i);
        items.sort(Comparator.comparing(UnusualItemResponse::at).reversed());
        return new UnusualOverviewResponse(items, refunds(today));
    }

    List<UnusualItemResponse> scan(LocalDate today) {
        Instant since = today.minusDays(400).atStartOfDay(Money.ZONE).toInstant();
        Instant recent = today.minusDays(RECENT_DAYS).atStartOfDay(Money.ZONE).toInstant();
        List<Row> rows = jdbc.query("""
                select t.id, t.account_id, a.name account, t.amount, t.occurred_at, t.time_estimated,
                       coalesce(t.merchant_key, lower(t.counterparty)) k, coalesce(t.counterparty, t.description) name,
                       t.original_currency, t.original_amount
                from txn t join account a on a.id = t.account_id
                where t.direction = 'DEBIT' and t.transfer = false and t.occurred_at >= :since
                order by t.occurred_at, t.id
                """, new MapSqlParameterSource("since", Timestamp.from(since)), (rs, n) -> new Row(rs.getLong("id"),
                rs.getLong("account_id"), rs.getString("account"), rs.getBigDecimal("amount"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getBoolean("time_estimated"), rs.getString("k"), rs.getString("name"), rs.getString("original_currency"),
                rs.getBigDecimal("original_amount")));
        if (rows.isEmpty()) return List.of();
        // Payees seen in the first month of my history aren't "new": that's just where the data starts.
        Instant historyWarm = rows.getFirst().at().plus(Duration.ofDays(30));

        Map<String, List<BigDecimal>> before = new HashMap<>();
        List<UnusualItemResponse> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            List<BigDecimal> prior = row.key() == null ? List.of() : before.getOrDefault(row.key(), List.of());
            if (!row.at().isBefore(recent)) {
                List<UnusualReasonResponse> reasons = new ArrayList<>();
                // Same amount, same payee, same account, within 30 minutes: often a payment that went through twice.
                for (int j = i - 1; j >= 0; j--) {
                    Row o = rows.get(j);
                    long mins = Duration.between(o.at(), row.at()).toMinutes();
                    if (mins > 30) break;
                    if (row.timeEstimated() || o.timeEstimated()) continue;
                    if (o.accountId() == row.accountId() && o.amount().compareTo(row.amount()) == 0 && Objects.equals(o.key(), row.key()) && row.key() != null) {
                        reasons.add(new UnusualReasonResponse("DOUBLE_CHARGE", o.id(), (int) mins, null, null, null, null));
                        break;
                    }
                }
                if (row.currency() != null && !row.currency().equalsIgnoreCase("INR"))
                    reasons.add(new UnusualReasonResponse("FOREIGN", null, null, null, null, row.currency().toUpperCase(Locale.ROOT), row.originalAmount()));
                if (prior.isEmpty() && row.key() != null && row.amount().compareTo(NEW_PAYEE_BIG) >= 0 && row.at().isAfter(historyWarm))
                    reasons.add(new UnusualReasonResponse("NEW_PAYEE_BIG", null, null, null, 0, null, null));
                if (prior.size() >= 5 && row.amount().compareTo(MERCHANT_MIN) >= 0) {
                    List<BigDecimal> sorted = new ArrayList<>(prior);
                    Collections.sort(sorted);
                    BigDecimal median = sorted.get(sorted.size() / 2);
                    if (row.amount().compareTo(median.multiply(BigDecimal.valueOf(3))) > 0 && row.amount().compareTo(sorted.getLast()) > 0)
                        reasons.add(new UnusualReasonResponse("BIG_FOR_MERCHANT", null, null, Money.r2(median), prior.size(), null, null));
                }
                int hour = ZonedDateTime.ofInstant(row.at(), Money.ZONE).getHour();
                if (!row.timeEstimated() && hour < 5 && row.amount().compareTo(LATE_NIGHT_MIN) >= 0)
                    reasons.add(new UnusualReasonResponse("LATE_NIGHT", null, null, null, null, null, null));
                if (!reasons.isEmpty())
                    out.add(new UnusualItemResponse("unusual:" + row.id(), row.id(), row.name(), row.accountId(), row.account(), row.amount(), row.at(), reasons));
            }
            if (row.key() != null) before.computeIfAbsent(row.key(), k -> new ArrayList<>()).add(row.amount());
        }
        return out;
    }

    // ---- refunds I'm waiting for

    /**
     * Each credit can be the refund for one payment only: payments claim credits oldest first, and a credit already
     * claimed is skipped for the next payment (two returns to the same shop shouldn't both be settled by one refund).
     */
    public List<RefundResponse> refunds(LocalDate today) {
        List<RefundResponse> out = new ArrayList<>();
        Set<Long> claimed = new HashSet<>();
        jdbc.query("""
                select w.txn_id, w.expected_by, t.amount, t.occurred_at, t.merchant_key, a.name account,
                       coalesce(t.counterparty, t.description) name
                from refund_watch w join txn t on t.id = w.txn_id join account a on a.id = t.account_id
                order by t.occurred_at, t.id
                """, rs -> {
            long id = rs.getLong("txn_id");
            BigDecimal amount = rs.getBigDecimal("amount");
            Instant paid = rs.getTimestamp("occurred_at").toInstant();
            LocalDate by = rs.getDate("expected_by").toLocalDate();
            var match = findRefund(id, amount, paid, rs.getString("merchant_key"), claimed);
            if (match != null) claimed.add(match.id());
            String status = match != null ? "ARRIVED" : today.isAfter(by) ? "LATE" : "WAITING";
            out.add(new RefundResponse(id, rs.getString("name"), rs.getString("account"), amount, paid, by, status,
                    match == null ? null : match.id(), match == null ? null : match.amount(), match == null ? null : match.at()));
        });
        out.sort(Comparator.comparing(RefundResponse::paidAt).reversed());               // newest first for the page
        return out;
    }

    public Optional<RefundResponse> refund(long txnId) {
        return refunds(LocalDate.now(Money.ZONE)).stream().filter(r -> r.txnId() == txnId).findFirst();
    }

    private record Match(long id, BigDecimal amount, Instant at) {}

    /**
     * A credit after the payment that looks like its refund: from the same payee for up to the amount paid, or worded
     * as a refund or reversal for the same amount.
     */
    private Match findRefund(long txnId, BigDecimal amount, Instant paid, String merchantKey, Set<Long> claimed) {
        var p = new MapSqlParameterSource("paid", Timestamp.from(paid)).addValue("amount", amount).addValue("key", merchantKey).addValue("id", txnId);
        List<Match> matches = jdbc.query("""
                select t.id, t.amount, t.occurred_at from txn t left join category c on c.id = t.category_id
                where t.direction = 'CREDIT' and t.transfer = false and t.occurred_at >= :paid and t.id <> :id
                  and ((cast(:key as varchar) is not null and t.merchant_key = :key and t.amount <= :amount)
                       or (abs(t.amount - :amount) <= 1 and (c.name = 'Refund & Cashback'
                           or concat_ws(' ', t.counterparty, t.description) ~* '(refund|reversal|reversed|chargeback)')))
                order by t.occurred_at, t.id
                """, p, (rs, n) -> new Match(rs.getLong("id"), rs.getBigDecimal("amount"), rs.getTimestamp("occurred_at").toInstant()));
        return matches.stream().filter(x -> !claimed.contains(x.id())).findFirst().orElse(null);
    }

    public void expectRefund(long txnId, LocalDate expectedBy) {
        LocalDate by = expectedBy != null ? expectedBy : LocalDate.now(Money.ZONE).plusDays(14);
        Integer debit = jdbc.queryForObject("select count(*) from txn where id = :id and direction = 'DEBIT' and account_id is not null", new MapSqlParameterSource("id", txnId), Integer.class);
        if (debit == null || debit == 0) throw new BadRequestException("Only a payment can have a refund");
        jdbc.update("""
                insert into refund_watch (txn_id, expected_by) values (:id, :by)
                on conflict (txn_id) do update set expected_by = excluded.expected_by
                """, new MapSqlParameterSource("id", txnId).addValue("by", by));
    }

    public void stopWaiting(long txnId) {
        jdbc.update("delete from refund_watch where txn_id = :id", new MapSqlParameterSource("id", txnId));
    }
}
