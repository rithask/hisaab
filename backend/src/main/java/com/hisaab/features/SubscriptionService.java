package com.hisaab.features;

import com.hisaab.features.BillService.Payment;
import com.hisaab.features.BillService.PriceChange;
import com.hisaab.features.BillService.Series;
import com.hisaab.service.Money;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Subscription watch: fixed-price payments that repeat (learned, like bills), and what changed about them:
 * a price went up, a charge came twice, a new one started (often a free trial turning paid), or one stopped.
 */
@Service
public class SubscriptionService {
    private final BillService bills;
    private final WatchDismissals dismissals;
    private final NamedParameterJdbcTemplate jdbc;

    /** Regular payments that aren't subscriptions by nature. They can still be hidden or shown by hand. */
    private static final Pattern NOT_SUBSCRIPTIONS = Pattern.compile("(?i)rent|invest|loan|emi|salary|transfer");

    public SubscriptionService(BillService bills, WatchDismissals dismissals, NamedParameterJdbcTemplate jdbc) {
        this.bills = bills;
        this.dismissals = dismissals;
        this.jdbc = jdbc;
    }

    /**
     * @param status  ACTIVE, NEW (started in the last ~2 months) or STOPPED (the last payment didn't come)
     * @param monthly the cost per month (a yearly plan counts a twelfth)
     */
    public record Subscription(String key, String name, Long accountId, String account, String category, String categoryColor,
                               BigDecimal amount, String every, BigDecimal monthly, BigDecimal yearly, LocalDate since,
                               LocalDate lastPaid, LocalDate nextDue, int timesPaid, String status, PriceChange priceChange,
                               BigDecimal paidThisYear) {}

    /**
     * Something about a subscription worth a look. The app words it; the fields depend on the kind:
     * PRICE_UP / PRICE_DOWN: {@code previous} → {@code amount} since {@code day}, {@code perYear} = the yearly difference.
     * DOUBLE: {@code amount} charged on {@code otherDay} (from {@code otherAccount}) and again on {@code day} (from {@code account}).
     * NEW: started {@code otherDay}, {@code amount} {@code every}. STOPPED: expected on {@code day}, last paid {@code otherDay}.
     */
    public record Alert(String key, String kind, String subscriptionKey, String name, BigDecimal amount, BigDecimal previous,
                        BigDecimal perYear, String every, LocalDate day, LocalDate otherDay, String account, String otherAccount,
                        List<Long> txnIds) {}

    public record Overview(List<Subscription> items, List<Alert> alerts, BigDecimal monthly, BigDecimal yearly,
                           List<Hidden> hidden) {}

    public record Hidden(String key, String name) {}

    public Overview overview() {
        LocalDate today = LocalDate.now(Money.ZONE);
        Set<String> hiddenKeys = hiddenKeys();
        Set<String> dismissed = dismissals.all();
        List<Subscription> items = new ArrayList<>();
        List<Alert> alerts = new ArrayList<>();
        List<Hidden> hidden = new ArrayList<>();
        LocalDate historyStart = jdbc.queryForObject(
                "select min((occurred_at at time zone current_setting('TimeZone'))::date) from txn", new MapSqlParameterSource(), LocalDate.class);

        for (Series s : bills.recurring(today, false)) {
            if (s.varies()) continue;                                      // electricity, phone: bills, not subscriptions
            if (hiddenKeys.contains(s.key())) {
                hidden.add(new Hidden(s.key(), s.payee()));
                continue;
            }
            if (!hiddenKeys.contains("+" + s.key()) && s.category() != null && NOT_SUBSCRIPTIONS.matcher(s.category()).find()) continue;

            Payment first = s.days().getFirst(), last = s.days().getLast();
            // "New" means it started recently, not that it's among the first payments the app ever saw: a payee whose
            // first payment falls in the first month of my history was most likely paying before the data starts.
            boolean isNew = !s.stopped() && historyStart != null
                    && ChronoUnit.DAYS.between(first.day(), today) <= Math.max(45, s.periodDays() * 2 + 5)
                    && first.day().isAfter(historyStart.plusDays(30));
            String status = s.stopped() ? "STOPPED" : isNew ? "NEW" : "ACTIVE";
            BigDecimal monthly = perMonth(s.amount(), s.every());
            BigDecimal paidThisYear = s.payments().stream().filter(p -> p.day().getYear() == today.getYear())
                    .map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            items.add(new Subscription(s.key(), s.payee(), s.accountId(), s.account(), s.category(), s.color(), s.amount(),
                    s.every(), monthly, Money.r2(monthly.multiply(BigDecimal.valueOf(12))), first.day(), last.day(),
                    s.due(), s.days().size(), status, s.change(), Money.r2(paidThisYear)));

            alerts.addAll(alertsFor(s, status, today));
        }
        alerts.removeIf(a -> dismissed.contains(a.key()));
        alerts.sort(Comparator.comparing(Alert::day).reversed());
        items.sort(Comparator.comparing((Subscription x) -> x.status().equals("STOPPED")).thenComparing(Subscription::monthly, Comparator.reverseOrder()));
        hidden.sort(Comparator.comparing(Hidden::name, String.CASE_INSENSITIVE_ORDER));

        BigDecimal monthly = items.stream().filter(x -> !x.status().equals("STOPPED")).map(Subscription::monthly).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Overview(items, alerts, Money.r2(monthly), Money.r2(monthly.multiply(BigDecimal.valueOf(12))), hidden);
    }

    private List<Alert> alertsFor(Series s, String status, LocalDate today) {
        List<Alert> out = new ArrayList<>();
        Payment last = s.days().getLast();
        PriceChange c = s.change();
        // A price change is news for about two billing periods.
        if (c != null && ChronoUnit.DAYS.between(c.on(), today) <= Math.max(60, s.periodDays() * 2)) {
            boolean up = c.to().compareTo(c.from()) > 0;
            BigDecimal perYear = Money.r2(perMonth(c.to().subtract(c.from()).abs(), s.every()).multiply(BigDecimal.valueOf(12)));
            out.add(new Alert("sub:price:" + s.key() + ":" + c.on(), up ? "PRICE_UP" : "PRICE_DOWN", s.key(), s.payee(), c.to(),
                    c.from(), perYear, s.every(), c.on(), null, s.account(), null, List.of(idOn(s, c.on()))));
        }
        // Charged twice within half a period (same day included), in the last 90 days, for about the same amount.
        List<Payment> ps = s.payments();
        for (int i = 1; i < ps.size(); i++) {
            Payment a = ps.get(i - 1), b = ps.get(i);
            if (b.day().isBefore(today.minusDays(90))) continue;
            long gap = ChronoUnit.DAYS.between(a.day(), b.day());
            if (gap * 2 >= s.periodDays()) continue;
            if (a.amount().subtract(b.amount()).abs().compareTo(b.amount().multiply(new BigDecimal("0.05"))) > 0) continue;
            out.add(new Alert("sub:double:" + s.key() + ":" + b.id(), "DOUBLE", s.key(), s.payee(), b.amount(), a.amount(), null,
                    s.every(), b.day(), a.day(), b.account(), a.account(), List.of(a.id(), b.id())));
        }
        if (status.equals("NEW"))
            out.add(new Alert("sub:new:" + s.key(), "NEW", s.key(), s.payee(), s.amount(), null, null, s.every(), last.day(),
                    s.days().getFirst().day(), s.account(), null, List.of(last.id())));
        if (status.equals("STOPPED"))
            out.add(new Alert("sub:stopped:" + s.key() + ":" + last.day(), "STOPPED", s.key(), s.payee(), s.amount(), null, null,
                    s.every(), s.due(), last.day(), s.account(), null, List.of(last.id())));
        return out;
    }

    private static long idOn(Series s, LocalDate day) {
        return s.days().stream().filter(p -> p.day().equals(day)).findFirst().orElse(s.days().getLast()).id();
    }

    static BigDecimal perMonth(BigDecimal amount, String every) {
        BigDecimal m = switch (every) {
            case "Every week" -> amount.multiply(BigDecimal.valueOf(52)).divide(BigDecimal.valueOf(12), 4, RoundingMode.HALF_UP);
            case "Every 3 months" -> amount.divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
            case "Every year" -> amount.divide(BigDecimal.valueOf(12), 4, RoundingMode.HALF_UP);
            default -> amount;
        };
        return Money.r2(m);
    }

    /** "key" hides a subscription; "+key" shows a payment that's normally left out (rent, investments…). */
    private Set<String> hiddenKeys() {
        return new HashSet<>(jdbc.queryForList("select merchant_key from subscription_ignore", new MapSqlParameterSource(), String.class));
    }

    public void hide(String key) {
        jdbc.update("delete from subscription_ignore where merchant_key = :p", new MapSqlParameterSource("p", "+" + key));
        jdbc.update("insert into subscription_ignore (merchant_key) values (:k) on conflict do nothing", new MapSqlParameterSource("k", key));
    }

    public void unhide(String key) {
        jdbc.update("delete from subscription_ignore where merchant_key = :k", new MapSqlParameterSource("k", key));
    }
}
