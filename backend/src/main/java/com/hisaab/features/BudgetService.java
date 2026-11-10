package com.hisaab.features;

import com.hisaab.service.Money;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** Monthly spending limits per category. Spending counts only my share of split bills; transfers never count. */
@Service
public class BudgetService {
    private final NamedParameterJdbcTemplate jdbc;

    public BudgetService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param projected spending by the end of the month at the current pace
     * @param status    OK, WARN (80% used, or on pace to go over) or OVER
     */
    public record Budget(Long categoryId, String name, String color, BigDecimal limit, BigDecimal spent, BigDecimal remaining,
                         int pct, BigDecimal projected, String status) {}

    public List<Budget> month(YearMonth ym) {
        LocalDate today = LocalDate.now(Money.ZONE);
        var p = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(ym.atDay(1).atStartOfDay(Money.ZONE).toInstant()))
                .addValue("to", Timestamp.from(ym.plusMonths(1).atDay(1).atStartOfDay(Money.ZONE).toInstant()));
        double elapsed = YearMonth.from(today).equals(ym) ? today.getDayOfMonth() : ym.lengthOfMonth();
        double fraction = Math.max(1, elapsed) / ym.lengthOfMonth();
        return jdbc.query("""
                select b.category_id, c.name, c.color, b.monthly_limit,
                       coalesce((select sum(t.amount - coalesce((select sum(s.amount) from split_share s where s.txn_id = t.id), 0))
                                 from txn t
                                 where t.category_id = b.category_id and t.direction = 'DEBIT' and t.transfer = false
                                   and t.occurred_at >= :from and t.occurred_at < :to), 0) spent
                from budget b join category c on c.id = b.category_id
                order by c.name
                """, p, (rs, i) -> {
            BigDecimal limit = rs.getBigDecimal("monthly_limit");
            BigDecimal spent = rs.getBigDecimal("spent").max(BigDecimal.ZERO);
            int pct = spent.multiply(BigDecimal.valueOf(100)).divide(limit, 0, RoundingMode.HALF_UP).intValue();
            BigDecimal projected = Money.r2(spent.divide(BigDecimal.valueOf(fraction), 2, RoundingMode.HALF_UP));
            String status = spent.compareTo(limit) > 0 ? "OVER"
                    : pct >= 80 || (fraction < 1 && projected.compareTo(limit) > 0 && pct >= 50) ? "WARN" : "OK";
            return new Budget(rs.getLong("category_id"), rs.getString("name"), rs.getString("color"), limit, Money.r2(spent),
                    Money.r2(limit.subtract(spent)), pct, projected, status);
        });
    }

    public void set(long categoryId, BigDecimal limit) {
        if (limit == null || limit.signum() <= 0) throw new IllegalArgumentException("A budget must be more than zero");
        jdbc.update("""
                insert into budget (category_id, monthly_limit) values (:c, :l)
                on conflict (category_id) do update set monthly_limit = excluded.monthly_limit
                """, new MapSqlParameterSource("c", categoryId).addValue("l", Money.r2(limit)));
    }

    public void remove(long categoryId) {
        jdbc.update("delete from budget where category_id = :c", new MapSqlParameterSource("c", categoryId));
    }
}
