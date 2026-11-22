package com.hisaab.watch.service;

import com.hisaab.watch.dto.HiddenSubscriptionResponse;
import com.hisaab.watch.dto.PriceChangeResponse;
import com.hisaab.watch.dto.SubscriptionAlertResponse;
import com.hisaab.watch.dto.SubscriptionOverviewResponse;
import com.hisaab.watch.dto.SubscriptionResponse;
import com.hisaab.common.util.Money;
import com.hisaab.watch.service.BillService.Payment;
import com.hisaab.watch.service.BillService.Series;
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

    public SubscriptionOverviewResponse overview() {
        LocalDate today = LocalDate.now(Money.ZONE);
        Set<String> hiddenKeys = hiddenKeys();
        Set<String> dismissed = dismissals.all();
        List<SubscriptionResponse> items = new ArrayList<>();
        List<SubscriptionAlertResponse> alerts = new ArrayList<>();
        List<HiddenSubscriptionResponse> hidden = new ArrayList<>();
        LocalDate historyStart = jdbc.queryForObject(
                "select min((occurred_at at time zone current_setting('TimeZone'))::date) from txn", new MapSqlParameterSource(), LocalDate.class);

        for (Series series : bills.recurring(today, false)) {
            if (series.varies()) continue;                                      // electricity, phone: bills, not subscriptions
            if (hiddenKeys.contains(series.key())) {
                hidden.add(new HiddenSubscriptionResponse(series.key(), series.payee()));
                continue;
            }
            if (!hiddenKeys.contains("+" + series.key()) && series.category() != null && NOT_SUBSCRIPTIONS.matcher(series.category()).find()) continue;

            Payment first = series.days().getFirst(), last = series.days().getLast();
            // "New" means it started recently, not that it's among the first payments the app ever saw: a payee whose
            // first payment falls in the first month of my history was most likely paying before the data starts.
            boolean isNew = !series.stopped() && historyStart != null
                    && ChronoUnit.DAYS.between(first.day(), today) <= Math.max(45, series.periodDays() * 2 + 5)
                    && first.day().isAfter(historyStart.plusDays(30));
            String status = series.stopped() ? "STOPPED" : isNew ? "NEW" : "ACTIVE";
            BigDecimal monthly = perMonth(series.amount(), series.every());
            BigDecimal paidThisYear = series.payments().stream().filter(p -> p.day().getYear() == today.getYear())
                    .map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            items.add(new SubscriptionResponse(series.key(), series.payee(), series.accountId(), series.account(), series.category(), series.color(), series.amount(),
                    series.every(), monthly, Money.r2(monthly.multiply(BigDecimal.valueOf(12))), first.day(),
                            last.day(),
                    series.due(), series.days().size(), status, series.change(), Money.r2(paidThisYear)));

            alerts.addAll(alertsFor(series, status, today));
        }
        alerts.removeIf(a -> dismissed.contains(a.key()));
        alerts.sort(Comparator.comparing(SubscriptionAlertResponse::day).reversed());
        items.sort(Comparator.comparing((SubscriptionResponse x) -> x.status().equals("STOPPED")).thenComparing(SubscriptionResponse::monthly, Comparator.reverseOrder()));
        hidden.sort(Comparator.comparing(HiddenSubscriptionResponse::name, String.CASE_INSENSITIVE_ORDER));

        BigDecimal monthly = items.stream().filter(x -> !x.status().equals("STOPPED")).map(SubscriptionResponse::monthly).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SubscriptionOverviewResponse(items, alerts, Money.r2(monthly), Money.r2(monthly.multiply(BigDecimal.valueOf(12))), hidden);
    }

    private List<SubscriptionAlertResponse> alertsFor(Series series, String status, LocalDate today) {
        List<SubscriptionAlertResponse> out = new ArrayList<>();
        Payment last = series.days().getLast();
        PriceChangeResponse priceChange = series.change();
        // A price change is news for about two billing periods.
        if (priceChange != null && ChronoUnit.DAYS.between(priceChange.on(), today) <= Math.max(60,
                series.periodDays() * 2)) {
            boolean up = priceChange.to().compareTo(priceChange.from()) > 0;
            BigDecimal perYear = Money.r2(perMonth(priceChange.to().subtract(priceChange.from()).abs(), series.every()).multiply(BigDecimal.valueOf(12)));
            out.add(new SubscriptionAlertResponse("sub:price:" + series.key() + ":" + priceChange.on(), up ? "PRICE_UP" : "PRICE_DOWN", series.key(), series.payee(), priceChange.to(),
                    priceChange.from(), perYear, series.every(), priceChange.on(), null, series.account(), null,
                            List.of(idOn(series, priceChange.on()))));
        }
        // Charged twice within half a period (same day included), in the last 90 days, for about the same amount.
        List<Payment> ps = series.payments();
        for (int i = 1; i < ps.size(); i++) {
            Payment payment = ps.get(i - 1), b = ps.get(i);
            if (b.day().isBefore(today.minusDays(90))) continue;
            long gap = ChronoUnit.DAYS.between(payment.day(), b.day());
            if (gap * 2 >= series.periodDays()) continue;
            if (payment.amount().subtract(b.amount()).abs().compareTo(b.amount().multiply(new BigDecimal("0.05"))) > 0) continue;
            out.add(new SubscriptionAlertResponse("sub:double:" + series.key() + ":" + b.id(), "DOUBLE", series.key(), series.payee(), b.amount(), payment.amount(), null,
                    series.every(), b.day(), payment.day(), b.account(), payment.account(), List.of(payment.id(),
                            b.id())));
        }
        if (status.equals("NEW"))
            out.add(new SubscriptionAlertResponse("sub:new:" + series.key(), "NEW", series.key(), series.payee(), series.amount(), null, null, series.every(), last.day(),
                    series.days().getFirst().day(), series.account(), null, List.of(last.id())));
        if (status.equals("STOPPED"))
            out.add(new SubscriptionAlertResponse("sub:stopped:" + series.key() + ":" + last.day(), "STOPPED", series.key(), series.payee(), series.amount(), null, null,
                    series.every(), series.due(), last.day(), series.account(), null, List.of(last.id())));
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
