package com.hisaab.features;

import com.hisaab.service.AccountService;
import com.hisaab.service.Money;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Upcoming bills, learned rather than entered: a payee becomes a bill when I've paid it at a steady interval
 * (weekly, monthly, quarterly or yearly) with similar amounts. Card dues come straight from the cards' billing
 * cycles. Nothing is stored except payees I said aren't bills.
 */
@Service
public class BillService {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountService accounts;

    public BillService(NamedParameterJdbcTemplate jdbc, AccountService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    /**
     * @param key       merchant key (for "not a bill"), or "card:<accountId>" for a card's due date
     * @param every     human interval: "Every month", "Every week"…
     * @param overdue   expected but not seen yet (within a grace period)
     */
    public record Bill(String key, String name, Long accountId, String account, String category, String categoryColor,
                       BigDecimal amount, boolean amountVaries, LocalDate due, int daysLeft, boolean overdue, String every,
                       int timesSeen, LocalDate lastPaid, boolean card) {}

    /** One payment of a recurring series. */
    public record Payment(long id, String key, String payee, long accountId, String account, String category, String color,
                          BigDecimal amount, LocalDate day) {}

    /** The price of a fixed-price series changed: {@code from} before {@code on}, {@code to} since. */
    public record PriceChange(BigDecimal from, BigDecimal to, LocalDate on) {}

    /**
     * A payee I pay at a steady interval.
     *
     * @param payments every payment in the window (several on one day included)
     * @param days     one payment per day, oldest first: what the pattern is judged on
     * @param amount   the current price (fixed series) or the typical amount (varying series)
     * @param change   the most recent price change, for fixed-price series
     * @param stopped  the next payment is overdue by more than the grace period
     */
    public record Series(String key, String payee, long accountId, String account, String category, String color,
                         String every, long periodDays, List<Payment> payments, List<Payment> days, BigDecimal amount,
                         boolean varies, PriceChange change, LocalDate due, int daysLeft, boolean stopped) {}

    private record Period(String label, int minDays, int maxDays) {}

    private static final List<Period> PERIODS = List.of(
            new Period("Every week", 6, 8), new Period("Every month", 26, 35),
            new Period("Every 3 months", 84, 98), new Period("Every year", 350, 380));

    public List<Bill> upcoming(int withinDays) {
        LocalDate today = LocalDate.now(Money.ZONE);
        return all().stream().filter(b -> b.daysLeft() <= withinDays).toList();
    }

    /** Every recurring payment it has learned, plus card dues, soonest first. */
    public List<Bill> all() {
        LocalDate today = LocalDate.now(Money.ZONE);
        List<Bill> out = new ArrayList<>(learned(today));
        for (var a : accounts.list(false)) {
            var c = a.card();
            if (c == null || c.dueDate() == null || a.balance().signum() <= 0) continue;
            int left = (int) ChronoUnit.DAYS.between(today, c.dueDate());
            out.add(new Bill("card:" + a.id(), a.name() + " bill", a.id(), a.name(), null, null, Money.r2(a.balance()), true,
                    c.dueDate(), left, left < 0, "Card due date", 0, null, true));
        }
        out.sort(Comparator.comparing(Bill::due));
        return out;
    }

    private List<Bill> learned(LocalDate today) {
        List<Bill> out = new ArrayList<>();
        for (Series x : recurring(today, true)) {
            if (x.stopped()) continue;                                              // stopped paying it, apparently
            Payment last = x.days().getLast();
            out.add(new Bill(x.key(), x.payee(), x.accountId(), x.account(), x.category(), x.color(), x.amount(), x.varies(),
                    x.due(), x.daysLeft(), x.daysLeft() < 0, x.every(), x.days().size(), last.day(), false));
        }
        return out;
    }

    /**
     * Every recurring series in the last 400 days. Stopped series are included (marked) for up to one and a half
     * periods after the payment that didn't come; older ones are forgotten.
     *
     * @param skipIgnored leave out payees marked "not a bill"
     */
    public List<Series> recurring(LocalDate today, boolean skipIgnored) {
        var p = new MapSqlParameterSource("since", Timestamp.from(today.minusDays(400).atStartOfDay(Money.ZONE).toInstant()));
        List<Payment> rows = jdbc.query("""
                select t.id, t.merchant_key, t.counterparty, a.id account_id, a.name account, c.name category, c.color, t.amount,
                       (t.occurred_at at time zone current_setting('TimeZone'))::date d
                from txn t join account a on a.id = t.account_id left join category c on c.id = t.category_id
                where t.direction = 'DEBIT' and t.transfer = false and t.merchant_key is not null and t.occurred_at >= :since
                """ + (skipIgnored ? "  and t.merchant_key not in (select merchant_key from bill_ignore)\n" : "") + """
                order by t.occurred_at, t.id
                """, p, (rs, i) -> new Payment(rs.getLong("id"), rs.getString("merchant_key"), rs.getString("counterparty"),
                rs.getLong("account_id"), rs.getString("account"), rs.getString("category"), rs.getString("color"),
                rs.getBigDecimal("amount"), rs.getDate("d").toLocalDate()));

        Map<String, List<Payment>> byPayee = new LinkedHashMap<>();
        for (Payment r : rows) byPayee.computeIfAbsent(r.key(), k -> new ArrayList<>()).add(r);

        List<Series> out = new ArrayList<>();
        for (List<Payment> ps : byPayee.values()) {
            // one payment per day (two coffees on one day are not a pattern)
            List<Payment> days = new ArrayList<>();
            for (Payment x : ps) if (days.isEmpty() || !days.getLast().day().equals(x.day())) days.add(x);
            if (days.size() < 2) continue;

            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < days.size(); i++) gaps.add(ChronoUnit.DAYS.between(days.get(i - 1).day(), days.get(i).day()));
            long median = median(gaps);
            Period period = PERIODS.stream().filter(x -> median >= x.minDays() && median <= x.maxDays()).findFirst().orElse(null);
            if (period == null) continue;
            long irregular = gaps.stream().filter(g -> g < period.minDays() * 0.75 || g > period.maxDays() * 1.25).count();
            if (irregular > (gaps.size() >= 4 ? 1 : 0)) continue;

            // Fixed price (possibly with price changes), or a varying amount within a band (electricity, phone).
            BigDecimal amount;
            boolean varies;
            PriceChange change = null;
            Steps steps = steps(days);
            if (steps != null) {
                amount = Money.r2(days.getLast().amount());
                varies = false;
                change = steps.change();
            } else {
                List<BigDecimal> amounts = days.stream().map(Payment::amount).sorted().toList();
                BigDecimal typical = amounts.get(amounts.size() / 2);
                BigDecimal spread = amounts.getLast().subtract(amounts.getFirst()).divide(typical, 4, RoundingMode.HALF_UP);
                if (spread.compareTo(new BigDecimal("0.35")) > 0) continue;        // amounts all over the place: not a bill
                if (days.size() == 2) continue;                                      // two payments must match exactly
                amount = Money.r2(typical);
                varies = true;
            }

            Payment last = days.getLast();
            LocalDate due = switch (period.label()) {
                case "Every month" -> last.day().plusMonths(1);
                case "Every 3 months" -> last.day().plusMonths(3);
                case "Every year" -> last.day().plusYears(1);
                default -> last.day().plusDays(median);
            };
            int left = (int) ChronoUnit.DAYS.between(today, due);
            long grace = Math.max(3, median / 6);
            if (left < -(grace + median * 3 / 2)) continue;                         // long gone: forget it
            out.add(new Series(last.key(), last.payee(), last.accountId(), last.account(), last.category(), last.color(),
                    period.label(), median, ps, days, amount, varies, change, due, left, left < -grace));
        }
        return out;
    }

    record Steps(PriceChange change) {}

    /**
     * Fixed-price check: the amounts form flat runs (each within 2%), e.g. 649, 649, 649, 799, 799. The first run must
     * have at least two payments so a price is established before it changes. Returns null when it isn't fixed.
     */
    static Steps steps(List<Payment> days) {
        PriceChange change = null;
        int runStart = 0;
        for (int i = 1; i < days.size(); i++) {
            if (close(days.get(i).amount(), days.get(runStart).amount())) continue;
            if (change == null && i - runStart < 2) return null;                   // no established price before the first change
            change = new PriceChange(Money.r2(days.get(i - 1).amount()), Money.r2(days.get(i).amount()), days.get(i).day());
            runStart = i;
        }
        return new Steps(change);
    }

    private static boolean close(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(b.multiply(new BigDecimal("0.02"))) <= 0;
    }

    public void ignore(String merchantKey) {
        jdbc.update("insert into bill_ignore (merchant_key) values (:k) on conflict do nothing", new MapSqlParameterSource("k", merchantKey));
    }

    public void unignore(String merchantKey) {
        jdbc.update("delete from bill_ignore where merchant_key = :k", new MapSqlParameterSource("k", merchantKey));
    }

    public List<String> ignored() {
        return jdbc.queryForList("select merchant_key from bill_ignore order by merchant_key", new MapSqlParameterSource(), String.class);
    }

    private static long median(List<Long> xs) {
        List<Long> s = new ArrayList<>(xs);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }
}
