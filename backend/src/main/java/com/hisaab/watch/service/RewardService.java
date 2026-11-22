package com.hisaab.watch.service;

import com.hisaab.watch.dto.CardRewardsResponse;
import com.hisaab.watch.dto.RewardCycleResponse;
import com.hisaab.watch.dto.RewardLineResponse;
import com.hisaab.watch.dto.RewardPointsResponse;
import com.hisaab.watch.dto.RewardProgramRequest;
import com.hisaab.watch.dto.RewardProgramResponse;
import com.hisaab.watch.dto.RewardRuleRequest;
import com.hisaab.watch.dto.RewardRuleResponse;
import com.hisaab.common.model.AccountType;
import com.hisaab.accounts.model.Account;
import com.hisaab.accounts.repository.AccountRepository;
import com.hisaab.common.util.Money;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hisaab.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static com.hisaab.common.exception.NotFound.notFound;

/**
 * Card rewards: I describe how each card earns (cashback % or points per ₹100, special rates for some merchants or
 * categories, caps per billing cycle), and this works out what each cycle should have earned. Cashback that the bank
 * actually credited is picked up from the card's credits. Everything earned is an estimate: banks exclude things
 * and round in their own ways, so the statement is the final word.
 */
@Service
public class RewardService {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountRepository accounts;

    public RewardService(NamedParameterJdbcTemplate jdbc, AccountRepository accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    private record Spend(long id, BigDecimal amount, LocalDate day, String text, Long categoryId, String category) {}

    public List<CardRewardsResponse> all() {
        List<CardRewardsResponse> out = new ArrayList<>();
        for (Account account : accounts.findByArchivedFalseOrderByTypeAscNameAsc())
            if (account.getType() == AccountType.CREDIT_CARD) out.add(card(account));
        return out;
    }

    private CardRewardsResponse card(Account account) {
        RewardProgramResponse rewardProgram = program(account.getId()).orElse(null);
        if (rewardProgram == null) return new CardRewardsResponse(account.getId(), account.getName(), account.getLast4(), account.getStatementDay() != null, null, List.of(), null, null, null, null);
        LocalDate today = LocalDate.now(Money.INDIA);
        List<LocalDate[]> ranges = cycles(account.getStatementDay(), today, 13);
        LocalDate from = ranges.getLast()[0];
        List<Spend> spends = spends(account.getId(), from);
        Map<Long, BigDecimal> earnedById = new HashMap<>();
        List<RewardCycleResponse> cycles = new ArrayList<>();
        for (LocalDate[] r : ranges) cycles.add(cycle(rewardProgram, r[0], r[1], r == ranges.getFirst(),
                spends.stream().filter(s -> !s.day().isBefore(r[0]) && !s.day().isAfter(r[1])).toList(), earnedById,
                received(account.getId(), r[0], r[1])));

        boolean points = rewardProgram.kind().equals("POINTS");
        int year = today.getYear();
        BigDecimal yearEarned = cycles.stream().filter(c -> c.to().getYear() == year || c.current()).map(RewardCycleResponse::earned).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal yearReceived = received(account.getId(), LocalDate.of(year, 1, 1), today);
        RewardPointsResponse pts = null;
        if (points && rewardProgram.pointsBalance() != null && rewardProgram.pointsAsOf() != null) {
            BigDecimal since = spends.stream().filter(s -> s.day().isAfter(rewardProgram.pointsAsOf()))
                    .map(s -> earnedById.getOrDefault(s.id(), BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal est = rewardProgram.pointsBalance().add(since);
            pts = new RewardPointsResponse(rewardProgram.pointsBalance(), rewardProgram.pointsAsOf(), since, est, rewardProgram.pointValue() == null ? null : Money.r2(est.multiply(rewardProgram.pointValue())));
        }
        // The running cycle always; past ones only if something happened in them (not before my data starts).
        List<RewardCycleResponse> shown = new ArrayList<>();
        for (RewardCycleResponse cy : cycles) if (shown.size() < 6 && (cy.current() || cy.spent().signum() != 0 || cy.received().signum() != 0)) shown.add(cy);
        return new CardRewardsResponse(account.getId(), account.getName(), account.getLast4(), account.getStatementDay() != null, rewardProgram, shown, yearEarned, value(rewardProgram, yearEarned), Money.r2(yearReceived), pts);
    }

    /** Works out one cycle: each payment goes to the first matching rule; caps are used up in date order. */
    private RewardCycleResponse cycle(RewardProgramResponse rewardProgram, LocalDate from, LocalDate to, boolean current, List<Spend> spends, Map<Long, BigDecimal> earnedById, BigDecimal received) {
        boolean points = rewardProgram.kind().equals("POINTS");
        Map<Integer, BigDecimal[]> byRule = new LinkedHashMap<>();                 // index -1 = base rate: {spent, earned}
        BigDecimal total = BigDecimal.ZERO, spent = BigDecimal.ZERO;
        boolean overallCapped = false;
        for (Spend spend : spends) {
            int idx = -1;
            for (int i = 0; i < rewardProgram.rules().size(); i++) if (matches(rewardProgram.rules().get(i),
                    spend)) { idx = i; break; }
            RewardRuleResponse rule = idx >= 0 ? rewardProgram.rules().get(idx) : null;
            BigDecimal rate = rule != null ? rule.rate() : rewardProgram.baseRate();
            BigDecimal raw = spend.amount().multiply(rate).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            raw = points ? raw.setScale(0, RoundingMode.DOWN) : raw.setScale(2, RoundingMode.HALF_UP);
            BigDecimal[] acc = byRule.computeIfAbsent(idx, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal earn = raw;
            if (spend.amount().signum() < 0) {                                       // a refund takes back what it earned
                acc[0] = acc[0].add(spend.amount());
                acc[1] = acc[1].add(earn);
                total = total.add(earn);
                spent = spent.add(spend.amount());
                earnedById.put(spend.id(), earn);
                continue;
            }
            BigDecimal ruleCap = rule != null ? rule.cycleCap() : null;
            if (ruleCap != null) earn = earn.min(ruleCap.subtract(acc[1]).max(BigDecimal.ZERO));
            if (rewardProgram.cycleCap() != null) {
                BigDecimal left = rewardProgram.cycleCap().subtract(total).max(BigDecimal.ZERO);
                if (earn.compareTo(left) > 0) { earn = left; overallCapped = true; }
            }
            acc[0] = acc[0].add(spend.amount());
            acc[1] = acc[1].add(earn);
            total = total.add(earn);
            spent = spent.add(spend.amount());
            earnedById.put(spend.id(), earn);
        }
        List<RewardLineResponse> lines = new ArrayList<>();
        for (int i = 0; i < rewardProgram.rules().size(); i++) {
            RewardRuleResponse rewardRule = rewardProgram.rules().get(i);
            BigDecimal[] acc = byRule.getOrDefault(i, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            lines.add(new RewardLineResponse(rewardRule.label(), rewardRule.rate(), Money.r2(acc[0]), acc[1], rewardRule.cycleCap(), rewardRule.cycleCap() != null && acc[1].compareTo(rewardRule.cycleCap()) >= 0));
        }
        BigDecimal[] base = byRule.getOrDefault(-1, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        lines.add(new RewardLineResponse("Everything else", rewardProgram.baseRate(), Money.r2(base[0]), base[1],
                null, false));
        boolean capped = overallCapped || rewardProgram.cycleCap() != null
                && total.compareTo(rewardProgram.cycleCap()) >= 0;
        return new RewardCycleResponse(from, to, current, Money.r2(spent), total, value(rewardProgram, total), Money.r2(received), capped, lines);
    }

    private static BigDecimal value(RewardProgramResponse p, BigDecimal earned) {
        if (p.kind().equals("CASHBACK")) return Money.r2(earned);
        return p.pointValue() == null ? null : Money.r2(earned.multiply(p.pointValue()));
    }

    private static boolean matches(RewardRuleResponse rewardRule, Spend spend) {
        boolean text = rewardRule.merchantMatch() == null || rewardRule.merchantMatch().isBlank()
                || spend.text().contains(rewardRule.merchantMatch().toLowerCase(Locale.ROOT).trim());
        boolean cat = rewardRule.categoryId() == null || rewardRule.categoryId().equals(spend.categoryId());
        return text && cat;
    }

    /**
     * Billing cycles, newest first: statement to statement when the card has a statement day, else calendar months.
     * The newest is the cycle running now.
     */
    static List<LocalDate[]> cycles(Integer statementDay, LocalDate today, int count) {
        List<LocalDate[]> out = new ArrayList<>();
        if (statementDay == null) {
            YearMonth month = YearMonth.from(today);
            for (int i = 0; i < count; i++, month = month.minusMonths(1)) out.add(new LocalDate[]{month.atDay(1), month.atEndOfMonth()});
            return out;
        }
        YearMonth m = YearMonth.from(today);
        LocalDate end = onDay(m, statementDay);
        if (end.isBefore(today)) end = onDay(m.plusMonths(1), statementDay);          // the running cycle ends at the next statement
        for (int i = 0; i < count; i++) {
            LocalDate start = onDay(YearMonth.from(end).minusMonths(1), statementDay).plusDays(1);
            out.add(new LocalDate[]{start, end});
            end = start.minusDays(1);
        }
        return out;
    }

    private static LocalDate onDay(YearMonth ym, int day) {
        return ym.atDay(Math.min(day, ym.lengthOfMonth()));
    }

    /**
     * Card spending that can earn: payments, not transfers, not bank fees or interest. Refunds on the card come back
     * as negative amounts, so a returned order takes back what it earned (matched to the same rule by its text).
     */
    private List<Spend> spends(long accountId, LocalDate from) {
        var p = new MapSqlParameterSource("a", accountId).addValue("from", Timestamp.from(from.atStartOfDay(Money.INDIA).toInstant()));
        return jdbc.query("""
                select t.id, case when t.direction = 'DEBIT' then t.amount else -t.amount end amount,
                       -- card billing cycles turn over on Indian dates, whatever TZ is
                       (t.occurred_at at time zone 'Asia/Kolkata')::date d, t.category_id, c.name category,
                       lower(concat_ws(' ', t.counterparty, t.merchant_key, t.description)) txt
                from txn t left join category c on c.id = t.category_id
                where t.account_id = :a and t.transfer = false and t.occurred_at >= :from
                  and coalesce(c.name, '') <> 'Fees & Charges'
                  and (t.direction = 'DEBIT' or concat_ws(' ', t.counterparty, t.description) ~* '(refund|reversal|reversed|chargeback|return)'
                       and concat_ws(' ', t.counterparty, t.description) !~* '(cash ?back|reward|redemption)')
                order by t.occurred_at, t.id
                """, p, (rs, i) -> new Spend(rs.getLong("id"), rs.getBigDecimal("amount"), rs.getDate("d").toLocalDate(),
                rs.getString("txt"), rs.getObject("category_id") == null ? null : rs.getLong("category_id"), rs.getString("category")));
    }

    /** Cashback or reward credits on the card between two dates (inclusive). */
    private BigDecimal received(long accountId, LocalDate from, LocalDate to) {
        var p = new MapSqlParameterSource("a", accountId)
                .addValue("from", Timestamp.from(from.atStartOfDay(Money.INDIA).toInstant()))
                .addValue("to", Timestamp.from(to.plusDays(1).atStartOfDay(Money.INDIA).toInstant()));
        BigDecimal v = jdbc.queryForObject("""
                select coalesce(sum(t.amount), 0) from txn t
                where t.account_id = :a and t.direction = 'CREDIT' and t.transfer = false
                  and t.occurred_at >= :from and t.occurred_at < :to
                  and concat_ws(' ', t.counterparty, t.description) ~* '(cash ?back|reward|redemption|\\mcb\\M)'
                """, p, BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    // ---- setup

    public Optional<RewardProgramResponse> program(long accountId) {
        var p = new MapSqlParameterSource("a", accountId);
        List<RewardRuleResponse> rules = jdbc.query("""
                select r.id, r.label, r.merchant_match, r.category_id, c.name category, r.rate, r.cycle_cap
                from reward_rule r left join category c on c.id = r.category_id where r.account_id = :a order by r.position, r.id
                """, p, (rs, i) -> new RewardRuleResponse(rs.getLong("id"), rs.getString("label"), rs.getString("merchant_match"),
                rs.getObject("category_id") == null ? null : rs.getLong("category_id"), rs.getString("category"),
                rs.getBigDecimal("rate"), rs.getBigDecimal("cycle_cap")));
        List<RewardProgramResponse> found = jdbc.query("select * from reward_program where account_id = :a", p, (rs, i) -> new RewardProgramResponse(
                rs.getString("kind"), rs.getBigDecimal("base_rate"), rs.getBigDecimal("cycle_cap"), rs.getBigDecimal("point_value"),
                rs.getBigDecimal("points_balance"), rs.getDate("points_as_of") == null ? null : rs.getDate("points_as_of").toLocalDate(), rules));
        return found.stream().findFirst();
    }

    @Transactional
    public void save(long accountId, RewardProgramRequest in) {
        Account a = accounts.findById(accountId).orElseThrow(notFound("Card"));
        if (a.getType() != AccountType.CREDIT_CARD) throw new BadRequestException("Rewards are for credit cards");
        if (!"CASHBACK".equals(in.kind()) && !"POINTS".equals(in.kind())) throw new BadRequestException("Choose cashback or points");
        boolean points = in.kind().equals("POINTS");
        BigDecimal max = points ? BigDecimal.valueOf(1000) : BigDecimal.valueOf(100);
        BigDecimal base = rate(in.baseRate(), max, "The usual rate");
        BigDecimal cap = positive(in.cycleCap(), "The cap per cycle");
        BigDecimal pv = points ? positive(in.pointValue(), "The value of a point") : null;
        BigDecimal bal = points ? positive(in.pointsBalance(), "The points balance") : null;
        LocalDate asOf = points && bal != null ? (in.pointsAsOf() != null ? in.pointsAsOf() : LocalDate.now(Money.INDIA)) : null;
        if (asOf != null && asOf.isAfter(LocalDate.now(Money.INDIA))) throw new BadRequestException("The points date can't be in the future");

        var p = new MapSqlParameterSource("a", accountId).addValue("kind", in.kind()).addValue("base", base).addValue("cap", cap)
                .addValue("pv", pv).addValue("bal", bal).addValue("asOf", asOf);
        jdbc.update("""
                insert into reward_program (account_id, kind, base_rate, cycle_cap, point_value, points_balance, points_as_of, updated_at)
                values (:a, :kind, :base, :cap, :pv, :bal, :asOf, now())
                on conflict (account_id) do update set kind = excluded.kind, base_rate = excluded.base_rate,
                  cycle_cap = excluded.cycle_cap, point_value = excluded.point_value, points_balance = excluded.points_balance,
                  points_as_of = excluded.points_as_of, updated_at = now()
                """, p);
        jdbc.update("delete from reward_rule where account_id = :a", p);
        List<RewardRuleRequest> rules = in.rules() == null ? List.of() : in.rules();
        if (rules.size() > 30) throw new BadRequestException("That's a lot of rules; 30 at most");
        int pos = 0;
        for (RewardRuleRequest rewardRuleRequest : rules) {
            String match = rewardRuleRequest.merchantMatch() == null || rewardRuleRequest.merchantMatch().isBlank()
                    ? null : rewardRuleRequest.merchantMatch().trim();
            if (match == null && rewardRuleRequest.categoryId() == null) throw new BadRequestException("Each special rate needs a merchant or a category");
            if (match != null && match.length() > 200) throw new BadRequestException("Merchant text is too long");
            String label = rewardRuleRequest.label() == null || rewardRuleRequest.label().isBlank() ? (match != null ? match : "Category") : rewardRuleRequest.label().trim();
            if (label.length() > 100) label = label.substring(0, 100);
            jdbc.update("""
                    insert into reward_rule (account_id, position, label, merchant_match, category_id, rate, cycle_cap)
                    values (:a, :pos, :label, :m, :c, :rate, :cap)
                    """, new MapSqlParameterSource("a", accountId).addValue("pos", pos++).addValue("label", label).addValue("m", match)
                    .addValue("c", rewardRuleRequest.categoryId()).addValue("rate", rate(rewardRuleRequest.rate(), max, label)).addValue("cap", positive(rewardRuleRequest.cycleCap(), "A cap")));
        }
    }

    public void remove(long accountId) {
        jdbc.update("delete from reward_program where account_id = :a", new MapSqlParameterSource("a", accountId));
    }

    private static BigDecimal rate(BigDecimal v, BigDecimal max, String what) {
        if (v == null) return BigDecimal.ZERO;
        if (v.signum() < 0 || v.compareTo(max) > 0) throw new BadRequestException(what + " must be between 0 and " + max.toPlainString());
        return v;
    }

    private static BigDecimal positive(BigDecimal v, String what) {
        if (v == null) return null;
        if (v.signum() < 0) throw new BadRequestException(what + " can't be negative");
        return v;
    }
}
