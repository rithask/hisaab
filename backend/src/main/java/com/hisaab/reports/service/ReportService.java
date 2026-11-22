package com.hisaab.reports.service;

import com.hisaab.common.model.Direction;
import com.hisaab.common.util.Money;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where money goes and comes from. Transfers are excluded; split bills only count my share (and what a friend paid
 * for me counts as my share of it, with no account); paying a friend back isn't spending again; money people
 * paid me back (confirmed settlements) isn't counted as income.
 */
@Service
public class ReportService {
    private final NamedParameterJdbcTemplate jdbc;

    public ReportService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CategoryTotal(Long categoryId, String name, String color, BigDecimal total, long count) {}

    public record MerchantTotal(String name, BigDecimal total, long count) {}

    public record DayTotal(LocalDate day, BigDecimal expense, BigDecimal income) {}

    public record MonthTotal(String month, BigDecimal expense, BigDecimal income) {}

    public record Report(Instant from, Instant to, BigDecimal income, BigDecimal expense,
                         List<CategoryTotal> expenseByCategory, List<CategoryTotal> incomeByCategory,
                         List<MerchantTotal> topMerchants, List<MerchantTotal> topSources, List<DayTotal> daily) {}

    /** My effective amount per transaction: debits net of others' shares, credits net of repayments. */
    public static final String EFFECTIVE = """
            with eff as (
              select t.id, t.direction, t.occurred_at, t.category_id, t.account_id, t.counterparty,
                     case when t.direction = 'DEBIT'
                          then t.amount - coalesce((select sum(s.amount) from split_share s where s.txn_id = t.id), 0)
                                        - coalesce((select sum(st.amount) from settlement st
                                                    where st.txn_id = t.id and st.status = 'CONFIRMED' and st.direction = 'OUT'), 0)
                          else t.amount - coalesce((select sum(st.amount) from settlement st
                                                    where st.txn_id = t.id and st.status = 'CONFIRMED' and st.direction = 'IN'), 0)
                     end as amt
              from txn t
              where t.transfer = false and t.occurred_at >= :from and t.occurred_at < :to
            )
            """;

    /**
     * The same rule as {@link #EFFECTIVE} for one transaction: money out less others' shares and what I paid back
     * to someone, money in less what was paid back to me. Never below zero.
     */
    public static BigDecimal myAmount(Direction d, BigDecimal amount, BigDecimal shared,
                                      BigDecimal repaidToMe, BigDecimal iRepaid) {
        BigDecimal m = d == Direction.DEBIT ? amount.subtract(shared).subtract(iRepaid) : amount.subtract(repaidToMe);
        return m.signum() < 0 ? BigDecimal.ZERO.setScale(2) : m;
    }

    public Report report(Instant from, Instant to) {
        var p = new MapSqlParameterSource().addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
        BigDecimal income = BigDecimal.ZERO, expense = BigDecimal.ZERO;
        for (var row : jdbc.queryForList(EFFECTIVE + "select direction, coalesce(sum(amt),0) total from eff group by direction", p)) {
            if ("DEBIT".equals(row.get("direction"))) expense = (BigDecimal) row.get("total");
            else income = (BigDecimal) row.get("total");
        }
        return new Report(from, to, income, expense,
                byCategory(p, "DEBIT"), byCategory(p, "CREDIT"),
                merchants(p, "DEBIT"), merchants(p, "CREDIT"), daily(p));
    }

    private List<CategoryTotal> byCategory(MapSqlParameterSource p, String dir) {
        return jdbc.query(EFFECTIVE + """
                        select c.id, coalesce(c.name, 'No category') name, coalesce(c.color, '#94a3b8') color,
                               sum(e.amt) total, count(*) cnt
                        from eff e left join category c on c.id = e.category_id
                        where e.direction = :dir and e.amt > 0
                        group by c.id, c.name, c.color order by total desc
                        """, new MapSqlParameterSource(p.getValues()).addValue("dir", dir),
                (rs, i) -> new CategoryTotal((Long) rs.getObject("id"), rs.getString("name"), rs.getString("color"),
                        rs.getBigDecimal("total"), rs.getLong("cnt")));
    }

    private List<MerchantTotal> merchants(MapSqlParameterSource p, String dir) {
        return jdbc.query(EFFECTIVE + """
                        select coalesce(e.counterparty, 'Unknown') name, sum(e.amt) total, count(*) cnt
                        from eff e where e.direction = :dir and e.amt > 0
                        group by 1 order by total desc limit 8
                        """, new MapSqlParameterSource(p.getValues()).addValue("dir", dir),
                (rs, i) -> new MerchantTotal(rs.getString("name"), rs.getBigDecimal("total"), rs.getLong("cnt")));
    }

    private List<DayTotal> daily(MapSqlParameterSource p) {
        return jdbc.query(EFFECTIVE + """
                        select (e.occurred_at at time zone current_setting('TimeZone'))::date d,
                               coalesce(sum(case when e.direction = 'DEBIT' then e.amt end), 0) expense,
                               coalesce(sum(case when e.direction = 'CREDIT' then e.amt end), 0) income
                        from eff e group by 1 order by 1
                        """, p,
                (rs, i) -> new DayTotal(rs.getDate("d").toLocalDate(), rs.getBigDecimal("expense"), rs.getBigDecimal("income")));
    }

    public List<MonthTotal> monthly(int months) {
        return monthly(YearMonth.now(Money.ZONE), months);
    }

    /** Income and spending for each of the {@code months} months ending with {@code until}. */
    public List<MonthTotal> monthly(YearMonth until, int months) {
        YearMonth first = until.minusMonths(months - 1);
        Instant from = first.atDay(1).atStartOfDay(Money.ZONE).toInstant();
        Instant to = until.plusMonths(1).atDay(1).atStartOfDay(Money.ZONE).toInstant();
        var p = new MapSqlParameterSource().addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
        // one query for all the months, grouped by calendar month in the app's zone (the session's TimeZone)
        Map<String, BigDecimal[]> byMonth = new HashMap<>();
        for (var row : jdbc.queryForList(EFFECTIVE + """
                select to_char(occurred_at at time zone current_setting('TimeZone'), 'YYYY-MM') ym, direction, coalesce(sum(amt), 0) total
                from eff group by 1, 2
                """, p)) {
            BigDecimal[] v = byMonth.computeIfAbsent((String) row.get("ym"), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            if ("DEBIT".equals(row.get("direction"))) v[0] = (BigDecimal) row.get("total");
            else v[1] = (BigDecimal) row.get("total");
        }
        List<MonthTotal> out = new ArrayList<>();
        for (YearMonth ym = first; !ym.isAfter(until); ym = ym.plusMonths(1)) {
            BigDecimal[] v = byMonth.getOrDefault(ym.toString(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            out.add(new MonthTotal(ym.toString(), v[0], v[1]));
        }
        return out;
    }
}
