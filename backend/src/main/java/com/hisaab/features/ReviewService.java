package com.hisaab.features;

import com.hisaab.service.Money;
import com.hisaab.service.ReportService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/** A year at a glance: totals, the best and worst months, where it went, and how it compares with last year. */
@Service
public class ReviewService {
    private final ReportService reports;
    private final NamedParameterJdbcTemplate jdbc;
    private final NetWorthService networth;

    public ReviewService(ReportService reports, NamedParameterJdbcTemplate jdbc, NetWorthService networth) {
        this.reports = reports;
        this.jdbc = jdbc;
        this.networth = networth;
    }

    public record Month(String month, BigDecimal income, BigDecimal expense) {}

    public record Biggest(String payee, String category, BigDecimal amount, LocalDate day) {}

    public record Review(int year, List<Integer> years, BigDecimal income, BigDecimal expense, BigDecimal saved, Integer savingsRate,
                         long transactions, BigDecimal perDay, List<Month> months, String bestMonth, String costliestMonth,
                         List<ReportService.CategoryTotal> categories, List<ReportService.MerchantTotal> merchants,
                         String mostVisited, long mostVisitedCount, Biggest biggest, String busiestWeekday, long noSpendDays,
                         BigDecimal lastYearExpense, BigDecimal lastYearIncome, BigDecimal networthStart, BigDecimal networthEnd,
                         boolean partial) {}

    public Review year(int year) {
        LocalDate today = LocalDate.now(Money.ZONE);
        LocalDate start = LocalDate.of(year, 1, 1), end = LocalDate.of(year + 1, 1, 1);
        LocalDate until = end.isAfter(today) ? today.plusDays(1) : end;
        Instant from = start.atStartOfDay(Money.ZONE).toInstant(), to = end.atStartOfDay(Money.ZONE).toInstant();
        var r = reports.report(from, to);
        var last = reports.report(LocalDate.of(year - 1, 1, 1).atStartOfDay(Money.ZONE).toInstant(), from);

        List<Month> months = reports.monthly(YearMonth.of(year, 12), 12).stream()
                .filter(m -> !YearMonth.parse(m.month()).isAfter(YearMonth.from(today)))
                .map(m -> new Month(m.month(), m.income(), m.expense())).toList();
        // The month in progress isn't over yet, so it can't be the best or the costliest.
        YearMonth running = YearMonth.from(today);
        String best = months.stream().filter(m -> !YearMonth.parse(m.month()).equals(running))
                .filter(m -> m.income().signum() > 0 || m.expense().signum() > 0)
                .max((a, b) -> a.income().subtract(a.expense()).compareTo(b.income().subtract(b.expense()))).map(Month::month).orElse(null);
        String costliest = months.stream().filter(m -> !YearMonth.parse(m.month()).equals(running)).filter(m -> m.expense().signum() > 0).max((a, b) -> a.expense().compareTo(b.expense())).map(Month::month).orElse(null);

        var p = new MapSqlParameterSource("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
        String eff = """
                with eff as (
                  select t.id, t.direction, t.occurred_at, t.counterparty, t.category_id,
                         case when t.direction = 'DEBIT' then t.amount - coalesce((select sum(s.amount) from split_share s where s.txn_id = t.id), 0)
                              else t.amount end amt
                  from txn t where t.transfer = false and t.occurred_at >= :from and t.occurred_at < :to)
                """;
        long count = jdbc.queryForObject(eff + "select count(*) from eff", p, Long.class);
        var visited = jdbc.queryForList(eff + """
                select counterparty, count(*) n from eff where direction = 'DEBIT' and counterparty is not null
                group by counterparty order by n desc, sum(amt) desc limit 1""", p);
        var biggest = jdbc.query(eff + """
                select e.counterparty, c.name category, e.amt, (e.occurred_at at time zone current_setting('TimeZone'))::date d
                from eff e left join category c on c.id = e.category_id where e.direction = 'DEBIT'
                order by e.amt desc limit 1""", p,
                (rs, i) -> new Biggest(rs.getString("counterparty"), rs.getString("category"), Money.r2(rs.getBigDecimal("amt")), rs.getDate("d").toLocalDate()));
        var weekday = jdbc.queryForList(eff + """
                select extract(isodow from e.occurred_at at time zone current_setting('TimeZone'))::int dow, sum(e.amt) s
                from eff e where e.direction = 'DEBIT' group by 1 order by s desc limit 1""", p);
        long spendDays = jdbc.queryForObject(eff + """
                select count(distinct (occurred_at at time zone current_setting('TimeZone'))::date) from eff where direction = 'DEBIT' and amt > 0""", p, Long.class);
        // Count days from the first transaction of the year, not 1 January, so a partial first year isn't skewed.
        LocalDate firstDay = jdbc.queryForObject(eff + "select min((occurred_at at time zone current_setting('TimeZone'))::date) from eff", p, LocalDate.class);
        LocalDate counted = firstDay == null || firstDay.isBefore(start) ? start : firstDay;
        long days = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(counted, until));

        BigDecimal saved = r.income().subtract(r.expense());
        Integer rate = r.income().signum() > 0 ? saved.multiply(BigDecimal.valueOf(100)).divide(r.income(), 0, RoundingMode.HALF_UP).intValue() : null;
        Integer firstYear = jdbc.queryForObject("select extract(year from min(occurred_at at time zone current_setting('TimeZone')))::int from txn", new MapSqlParameterSource(), Integer.class);
        List<Integer> years = new java.util.ArrayList<>();
        for (int y = today.getYear(); y >= (firstYear == null ? today.getYear() : firstYear); y--) years.add(y);

        return new Review(year, years, r.income(), r.expense(), saved, rate, count,
                Money.r2(r.expense().divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP)), months, best, costliest,
                r.expenseByCategory().stream().limit(6).toList(), r.topMerchants().stream().limit(5).toList(),
                visited.isEmpty() ? null : (String) visited.getFirst().get("counterparty"),
                visited.isEmpty() ? 0 : ((Number) visited.getFirst().get("n")).longValue(),
                biggest.isEmpty() ? null : biggest.getFirst(),
                weekday.isEmpty() ? null : DayOfWeek.of(((Number) weekday.getFirst().get("dow")).intValue()).getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                Math.max(0, days - spendDays), last.expense(), last.income(),
                networth.on(start.minusDays(1)).or(() -> networth.series(start).stream().findFirst()).map(NetWorthService.Point::netWorth).orElse(null),
                networth.on(until.minusDays(1)).map(NetWorthService.Point::netWorth).orElse(null),
                end.isAfter(today));
    }
}
