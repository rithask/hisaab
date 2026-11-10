package com.hisaab.features;

import com.hisaab.features.FeatureService.Feature;
import com.hisaab.service.Money;
import com.hisaab.web.TotalsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/**
 * Net worth over time. Every night (and at startup) today's totals are stored as a snapshot. Days before the
 * first snapshot are rebuilt from transactions: bank, cash and card balances are exact, but investments (and money
 * owed to me) aren't known historically, so those days carry the earliest known values and are marked estimated.
 */
@Service
public class NetWorthService {
    private static final Logger log = LoggerFactory.getLogger(NetWorthService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final TotalsService totals;
    private final FeatureService features;

    public NetWorthService(NamedParameterJdbcTemplate jdbc, TotalsService totals, FeatureService features) {
        this.jdbc = jdbc;
        this.totals = totals;
        this.features = features;
    }

    public record Point(LocalDate day, BigDecimal netWorth, BigDecimal cash, BigDecimal cardDues, BigDecimal investments,
                        BigDecimal receivable, boolean estimated) {}

    @EventListener(ApplicationReadyEvent.class)
    public void atStartup() {
        if (features.on(Feature.NETWORTH)) snapshotQuietly();
    }

    @Scheduled(cron = "0 55 23 * * *")   // just before your midnight (TZ)
    public void nightly() {
        if (features.on(Feature.NETWORTH)) snapshotQuietly();
    }

    private void snapshotQuietly() {
        try {
            snapshot();
        } catch (RuntimeException e) {
            log.warn("Net worth snapshot failed: {}", e.toString());
        }
    }

    /** Stores today's real figures and (re)builds the estimated history before the first real snapshot. */
    @Transactional
    public void snapshot() {
        LocalDate today = LocalDate.now(Money.ZONE);
        var t = totals.now();
        upsert(today, t.cash(), t.cardOutstanding(), t.investments(), t.receivable(), false);
        rebuildHistory(today, t.investments(), t.receivable());
    }

    private void rebuildHistory(LocalDate today, BigDecimal investmentsNow, BigDecimal receivableNow) {
        LocalDate firstReal = jdbc.queryForObject("select min(day) from networth_snapshot where not estimated", new MapSqlParameterSource(), LocalDate.class);
        BigDecimal inv = investmentsNow, rec = receivableNow;
        if (firstReal != null && firstReal.isBefore(today)) {
            var r = jdbc.queryForMap("select investments, receivable from networth_snapshot where day = :d", new MapSqlParameterSource("d", Date.valueOf(firstReal)));
            inv = (BigDecimal) r.get("investments");
            rec = (BigDecimal) r.get("receivable");
        }
        LocalDate until = firstReal == null ? today : firstReal; // exclusive
        List<Map<String, Object>> accts = jdbc.queryForList(
                "select id, type, opening_balance, (opening_date at time zone current_setting('TimeZone'))::date start, opening_date from account where not archived",
                new MapSqlParameterSource());
        if (accts.isEmpty()) return;
        LocalDate first = accts.stream().map(a -> ((Date) a.get("start")).toLocalDate()).min(Comparator.naturalOrder()).orElse(until);

        // daily net movement per account since its opening date
        Map<Long, Map<LocalDate, BigDecimal>> nets = new HashMap<>();
        jdbc.query("""
                select t.account_id, (t.occurred_at at time zone current_setting('TimeZone'))::date d,
                       sum(case when t.direction = 'CREDIT' then t.amount else -t.amount end) net
                from txn t join account a on a.id = t.account_id
                where t.occurred_at >= a.opening_date group by 1, 2
                """, rs -> {
            nets.computeIfAbsent(rs.getLong("account_id"), k -> new HashMap<>()).put(rs.getDate("d").toLocalDate(), rs.getBigDecimal("net"));
        });

        Map<Long, BigDecimal> running = new HashMap<>();
        for (LocalDate d = first; d.isBefore(until); d = d.plusDays(1)) {
            BigDecimal cash = BigDecimal.ZERO, dues = BigDecimal.ZERO;
            for (var a : accts) {
                long id = ((Number) a.get("id")).longValue();
                LocalDate start = ((Date) a.get("start")).toLocalDate();
                if (d.isBefore(start)) continue;
                BigDecimal bal = running.getOrDefault(id, BigDecimal.ZERO)
                        .add(nets.getOrDefault(id, Map.of()).getOrDefault(d, BigDecimal.ZERO));
                running.put(id, bal);
                BigDecimal opening = (BigDecimal) a.get("opening_balance");
                if ("CREDIT_CARD".equals(a.get("type"))) dues = dues.add(opening.subtract(bal));
                else cash = cash.add(opening.add(bal));
            }
            upsert(d, cash, dues, inv, rec, true);
        }
    }

    private void upsert(LocalDate day, BigDecimal cash, BigDecimal dues, BigDecimal inv, BigDecimal rec, boolean estimated) {
        BigDecimal net = cash.add(inv).add(rec).subtract(dues);
        jdbc.update("""
                insert into networth_snapshot (day, cash, card_dues, investments, receivable, net_worth, estimated)
                values (:d, :c, :dues, :i, :r, :n, :e)
                on conflict (day) do update set cash = excluded.cash, card_dues = excluded.card_dues, investments = excluded.investments,
                    receivable = excluded.receivable, net_worth = excluded.net_worth, estimated = excluded.estimated
                where networth_snapshot.estimated or not excluded.estimated
                """, new MapSqlParameterSource("d", Date.valueOf(day)).addValue("c", Money.r2(cash)).addValue("dues", Money.r2(dues))
                .addValue("i", Money.r2(inv)).addValue("r", Money.r2(rec)).addValue("n", Money.r2(net)).addValue("e", estimated));
    }

    public List<Point> series(LocalDate from) {
        return jdbc.query("select * from networth_snapshot where day >= :f order by day", new MapSqlParameterSource("f", Date.valueOf(from)),
                (rs, i) -> new Point(rs.getDate("day").toLocalDate(), rs.getBigDecimal("net_worth"), rs.getBigDecimal("cash"),
                        rs.getBigDecimal("card_dues"), rs.getBigDecimal("investments"), rs.getBigDecimal("receivable"), rs.getBoolean("estimated")));
    }

    public Optional<Point> on(LocalDate day) {
        var l = jdbc.query("select * from networth_snapshot where day <= :d order by day desc limit 1", new MapSqlParameterSource("d", Date.valueOf(day)),
                (rs, i) -> new Point(rs.getDate("day").toLocalDate(), rs.getBigDecimal("net_worth"), rs.getBigDecimal("cash"),
                        rs.getBigDecimal("card_dues"), rs.getBigDecimal("investments"), rs.getBigDecimal("receivable"), rs.getBoolean("estimated")));
        return l.stream().findFirst();
    }
}
