package com.hisaab.service;

import com.hisaab.domain.Account;
import com.hisaab.domain.ApiToken;
import com.hisaab.domain.Category;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Tag;
import com.hisaab.domain.Txn;
import com.hisaab.repo.AccountRepo;
import com.hisaab.repo.CategoryRepo;
import com.hisaab.repo.TxnRepo;
import com.hisaab.web.DataApi;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * What other apps can read through the Data API, always within the limits of the token asking: its categories,
 * its accounts, and money out only unless it may see money coming in. Transfers between my own accounts are
 * never included. A single transaction outside a token's limits gets the same 404 as one that doesn't exist
 * (RFC 9110 §15.5.4), so a token can't find out what's there; a filter naming a category or account it can't
 * read gets 403, whether or not that category or account exists.
 */
@Service
public class DataApiService {
    /** Refused because of the token's limits (403). */
    public static class Forbidden extends RuntimeException {
        public Forbidden(String message) {
            super(message);
        }
    }

    /** An Idempotency-Key reused with a different request (422, draft-ietf-httpapi-idempotency-key-header). */
    public static class KeyReused extends RuntimeException {
        public KeyReused(String message) {
            super(message);
        }
    }

    public static final int MAX_LIMIT = 200;
    static final int DEFAULT_DAYS = 30;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final TxnRepo txns;
    private final CategoryRepo categories;
    private final AccountRepo accounts;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionService transactions;
    private final AccountService accountViews;
    private final com.hisaab.repo.ApiTokenRepo tokens;
    private final com.hisaab.mail.EmailProcessor processor;
    /** To read back what the database set (created_at, updated_at) on a transaction just recorded. */
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    public DataApiService(TxnRepo txns, CategoryRepo categories, AccountRepo accounts, NamedParameterJdbcTemplate jdbc,
                          TransactionService transactions, AccountService accountViews, com.hisaab.repo.ApiTokenRepo tokens,
                          com.hisaab.mail.EmailProcessor processor) {
        this.processor = processor;
        this.txns = txns;
        this.categories = categories;
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.accountViews = accountViews;
        this.tokens = tokens;
    }

    // ---- transactions -------------------------------------------------------------------------------------

    /** A category asked for by id or name; {@code uncategorised} means none. */
    private record CategoryAsk(boolean uncategorised, Long id) {}

    /**
     * Transactions on {@code date}, or from {@code since} until now (days in my time zone), oldest first.
     * Neither: the last {@value #DEFAULT_DAYS} days. With {@code updatedSince} (an RFC 3339 time): those created or
     * changed at or after it, in the order they changed, and no default window.
     */
    @Transactional(readOnly = true)
    public DataApi.Page<DataApi.Transaction> transactions(ApiToken token, String date, String since, String updatedSince,
                                                          String category, String account, Integer limit, String cursor) {
        if (date != null && since != null) throw new IllegalArgumentException("Send either date or since, not both.");
        Instant changed = null;
        if (updatedSince != null) {
            try {
                changed = OffsetDateTime.parse(updatedSince.trim()).toInstant();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("updated_since must be an RFC 3339 time like 2026-11-13T17:05:00+05:30.");
            }
        }
        boolean byChange = changed != null;
        Instant from = null, to = null;
        if (date != null) {
            LocalDate d = day(date, "date");
            from = d.atStartOfDay(Money.ZONE).toInstant();
            to = d.plusDays(1).atStartOfDay(Money.ZONE).toInstant();
        } else if (since != null) {
            from = day(since, "since").atStartOfDay(Money.ZONE).toInstant();
        } else if (!byChange) {
            from = LocalDate.now(Money.ZONE).minusDays(DEFAULT_DAYS).atStartOfDay(Money.ZONE).toInstant();
        }
        int n = limit == null ? 50 : limit;
        if (n < 1 || n > MAX_LIMIT) throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ".");
        CategoryAsk cat = category == null ? null : askCategory(token, category);
        Long acct = account == null ? null : askAccount(token, account);
        Cursor after = cursor == null ? null : Cursor.decode(cursor);
        if (after != null && after.byChange() != byChange) {
            throw new IllegalArgumentException("That cursor belongs to a list in another order. Send the same parameters as for the first page.");
        }
        // the order the list is in, and what the cursor moves along
        String key = byChange ? "updatedAt" : "occurredAt";

        Instant start = from, end = to, changedFrom = changed;
        Specification<Txn> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>(limits(token, root, cb));
            if (start != null) ps.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), start));
            if (end != null) ps.add(cb.lessThan(root.get("occurredAt"), end));
            if (changedFrom != null) ps.add(cb.greaterThanOrEqualTo(root.get("updatedAt"), changedFrom));
            if (cat != null) ps.add(cat.uncategorised() ? cb.isNull(root.get("category")) : cb.equal(root.join("category", JoinType.LEFT).get("id"), cat.id()));
            if (acct != null) ps.add(cb.equal(root.get("account").get("id"), acct));
            if (after != null) {
                ps.add(cb.or(cb.greaterThan(root.get(key), after.at()),
                        cb.and(cb.equal(root.get(key), after.at()), cb.greaterThan(root.get("id"), after.id()))));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        List<Txn> rows = txns.findAll(spec, PageRequest.of(0, n + 1, Sort.by(key, "id"))).getContent();
        boolean more = rows.size() > n;
        List<Txn> page = more ? rows.subList(0, n) : rows;
        Txn last = more ? page.getLast() : null;
        String next = last == null ? null : new Cursor(byChange, byChange ? last.getUpdatedAt() : last.getOccurredAt(), last.getId()).encode();
        return new DataApi.Page<>(page.stream().map(t -> transaction(token, t)).toList(), more, next);
    }

    @Transactional(readOnly = true)
    public DataApi.Transaction transaction(ApiToken token, String id) {
        Txn t;
        try {
            t = txns.findById(Long.parseLong(id)).orElse(null);
        } catch (NumberFormatException e) {
            t = null;
        }
        if (t == null || !visible(token, t)) throw new NoSuchElementException("No such transaction.");
        return transaction(token, t);
    }

    /** The categories this token may see (or use, for adding), for another app's menus. */
    @Transactional(readOnly = true)
    public DataApi.ListOf<DataApi.Category> categories(ApiToken token) {
        return new DataApi.ListOf<>(categories.findAll(Sort.by("kind", "name")).stream()
                .filter(c -> token.allowsCategory(c.getId()))
                .filter(c -> token.isMoneyIn() || c.getKind() == com.hisaab.domain.Enums.CategoryKind.EXPENSE)
                .map(c -> new DataApi.Category(c.getId().toString(), c.getName(), c.getKind().name().toLowerCase(Locale.ROOT)))
                .toList());
    }

    @Transactional(readOnly = true)
    public DataApi.ListOf<DataApi.AccountRef> accounts(ApiToken token) {
        return new DataApi.ListOf<>(accounts.findByArchivedFalseOrderByTypeAscNameAsc().stream()
                .filter(a -> token.allowsAccount(a.getId())).map(DataApiService::account).toList());
    }

    /** What the token's limits allow, as query conditions. Transfers never; money in only when allowed. */
    private static List<Predicate> limits(ApiToken token, jakarta.persistence.criteria.Root<Txn> root, jakarta.persistence.criteria.CriteriaBuilder cb) {
        List<Predicate> ps = new ArrayList<>();
        ps.add(cb.isFalse(root.get("transfer")));
        if (!token.isMoneyIn()) ps.add(cb.equal(root.get("direction"), Direction.DEBIT));
        Set<String> cats = token.categoryLimit();
        if (cats != null) {
            List<Long> ids = cats.stream().filter(c -> !c.equals(ApiToken.UNCATEGORISED)).map(Long::valueOf).toList();
            List<Predicate> any = new ArrayList<>();
            if (!ids.isEmpty()) any.add(root.join("category", JoinType.LEFT).get("id").in(ids));
            if (cats.contains(ApiToken.UNCATEGORISED)) any.add(cb.isNull(root.get("category")));
            ps.add(any.isEmpty() ? cb.disjunction() : cb.or(any.toArray(Predicate[]::new)));
        }
        Set<Long> accts = token.accountLimit();
        if (accts != null) ps.add(accts.isEmpty() ? cb.disjunction() : root.get("account").get("id").in(accts));
        return ps;
    }

    static boolean visible(ApiToken token, Txn t) {
        return !t.isTransfer() && (token.isMoneyIn() || t.getDirection() == Direction.DEBIT)
                && token.allowsCategory(t.getCategory() == null ? null : t.getCategory().getId())
                && token.allowsAccount(t.getAccount().getId());
    }

    private CategoryAsk askCategory(ApiToken token, String v) {
        String s = v.trim();
        if (s.equalsIgnoreCase("uncategorised") || s.equalsIgnoreCase("uncategorized") || s.equalsIgnoreCase(ApiToken.UNCATEGORISED)) {
            if (!token.allowsCategory(null)) throw new Forbidden("This token can't read uncategorised transactions.");
            return new CategoryAsk(true, null);
        }
        Optional<Category> c = s.matches("\\d+") ? categories.findById(Long.parseLong(s))
                : categories.findAll().stream().filter(x -> x.getName().equalsIgnoreCase(s)).findFirst();
        if (c.isEmpty() || !token.allowsCategory(c.get().getId())) throw new Forbidden("This token can't read the category \"" + clip(s) + "\".");
        return new CategoryAsk(false, c.get().getId());
    }

    private Long askAccount(ApiToken token, String v) {
        Long id = v.trim().matches("\\d+") ? Long.valueOf(v.trim()) : null;
        if (id == null || !accounts.existsById(id) || !token.allowsAccount(id)) throw new Forbidden("This token can't read that account.");
        return id;
    }

    private static DataApi.Transaction transaction(ApiToken token, Txn t) {
        boolean notes = token.isIncludeNotes();
        return new DataApi.Transaction(t.getId().toString(), t.getDirection().name().toLowerCase(Locale.ROOT),
                time(t.getOccurredAt()), amount(t.getAmount()),
                t.getOriginalAmount() == null ? null : new DataApi.Amount(t.getOriginalAmount().setScale(2, RoundingMode.HALF_UP).toPlainString(), t.getOriginalCurrency()),
                t.getCounterparty(),
                t.getCategory() == null ? null : new DataApi.CategoryRef(t.getCategory().getId().toString(), t.getCategory().getName()),
                account(t.getAccount()),
                notes ? t.getNotes() : null,
                notes ? t.getTags().stream().map(Tag::getName).sorted().toList() : List.of(),
                time(t.getCreatedAt()), time(t.getUpdatedAt()));
    }

    static DataApi.AccountRef account(Account a) {
        return new DataApi.AccountRef(a.getId().toString(), a.getName(), a.getType().name().toLowerCase(Locale.ROOT), a.getLast4());
    }

    static DataApi.Amount amount(BigDecimal v) {
        return v == null ? null : new DataApi.Amount(v.setScale(2, RoundingMode.HALF_UP).toPlainString(), "INR");
    }

    static String time(Instant at) {
        return at == null ? null : TIME.format(at.atZone(Money.ZONE));
    }

    private static LocalDate day(String v, String name) {
        try {
            return LocalDate.parse(v.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be a date like 2026-11-12.");
        }
    }

    private static String clip(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    /**
     * Where a page ended: the last transaction's time (when it happened, or when it changed for an updated_since
     * list) and id, the order the list is in. Opaque to apps.
     */
    record Cursor(boolean byChange, Instant at, long id) {
        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(((byChange ? "u" : "o") + "|" + at + "|" + id).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String s) {
            try {
                String[] p = new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8).split("\\|");
                if (!p[0].equals("u") && !p[0].equals("o")) throw new IllegalArgumentException();
                return new Cursor(p[0].equals("u"), Instant.parse(p[1]), Long.parseLong(p[2]));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("That cursor isn't valid. Use next_cursor from the previous page.");
            }
        }
    }

    // ---- totals ---------------------------------------------------------------------------------------------

    /**
     * Totals for a period, grouped, counted the way the app's own screens count: my share of split payments,
     * no transfers, days in my time zone. Never single payments.
     *
     * @param month    2026-11; or {@code from}/{@code to} (days, both included); neither: this month
     * @param groupBy  category (default), account, month or day
     */
    @Transactional(readOnly = true)
    public DataApi.Summary summary(ApiToken token, String month, String fromDay, String toDay, String groupBy,
                                   String direction, String category, String account) {
        LocalDate first, last;
        if (month != null) {
            if (fromDay != null || toDay != null) throw new IllegalArgumentException("Send either month or from/to, not both.");
            YearMonth ym;
            try {
                ym = YearMonth.parse(month.trim());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("month must look like 2026-11.");
            }
            first = ym.atDay(1);
            last = ym.atEndOfMonth();
        } else if (fromDay != null) {
            first = day(fromDay, "from");
            last = toDay == null ? LocalDate.now(Money.ZONE) : day(toDay, "to");
            if (last.isBefore(first)) throw new IllegalArgumentException("to must not be before from.");
        } else {
            if (toDay != null) throw new IllegalArgumentException("Send from with to.");
            YearMonth ym = YearMonth.now(Money.ZONE);
            first = ym.atDay(1);
            last = ym.atEndOfMonth();
        }
        String by = groupBy == null ? "category" : groupBy.trim().toLowerCase(Locale.ROOT);
        String dir = direction == null ? "debit" : direction.trim().toLowerCase(Locale.ROOT);
        if (!dir.equals("debit") && !dir.equals("credit")) throw new IllegalArgumentException("direction must be debit or credit.");
        if (dir.equals("credit") && !token.isMoneyIn()) throw new Forbidden("This token can only read money going out.");
        CategoryAsk cat = category == null ? null : askCategory(token, category);
        Long acct = account == null ? null : askAccount(token, account);

        String key, name, order;
        switch (by) {
            case "category" -> {
                key = "e.category_id::text";
                name = "coalesce(c.name, 'Uncategorised')";
                order = "total desc";
            }
            case "account" -> {
                key = "e.account_id::text";
                name = "a.name";
                order = "total desc";
            }
            case "month", "day" -> {
                key = "to_char(e.occurred_at at time zone current_setting('TimeZone'), '" + (by.equals("month") ? "YYYY-MM" : "YYYY-MM-DD") + "')";
                name = key;
                order = "k";
            }
            default -> throw new IllegalArgumentException("group_by must be category, account, month or day.");
        }

        var p = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(first.atStartOfDay(Money.ZONE).toInstant()))
                .addValue("to", Timestamp.from(last.plusDays(1).atStartOfDay(Money.ZONE).toInstant()))
                .addValue("dir", dir.toUpperCase(Locale.ROOT));
        StringBuilder where = new StringBuilder("e.direction = :dir and e.amt > 0");
        Set<String> cats = token.categoryLimit();
        if (cats != null) {
            List<Long> ids = cats.stream().filter(c -> !c.equals(ApiToken.UNCATEGORISED)).map(Long::valueOf).toList();
            List<String> any = new ArrayList<>();
            if (!ids.isEmpty()) {
                any.add("e.category_id in (:cats)");
                p.addValue("cats", ids);
            }
            if (cats.contains(ApiToken.UNCATEGORISED)) any.add("e.category_id is null");
            where.append(" and ").append(any.isEmpty() ? "false" : "(" + String.join(" or ", any) + ")");
        }
        Set<Long> accts = token.accountLimit();
        if (accts != null) {
            if (accts.isEmpty()) where.append(" and false");
            else {
                where.append(" and e.account_id in (:accts)");
                p.addValue("accts", accts);
            }
        }
        if (cat != null) {
            if (cat.uncategorised()) where.append(" and e.category_id is null");
            else {
                where.append(" and e.category_id = :cat");
                p.addValue("cat", cat.id());
            }
        }
        if (acct != null) {
            where.append(" and e.account_id = :acct");
            p.addValue("acct", acct);
        }
        List<DataApi.Group> groups = jdbc.query(ReportService.EFFECTIVE + "select " + key + " k, " + name + " n, sum(e.amt) total, count(*) cnt"
                        + " from eff e left join category c on c.id = e.category_id left join account a on a.id = e.account_id"
                        + " where " + where + " group by 1, 2 order by " + order,
                p, (rs, i) -> new DataApi.Group(rs.getString("k"), rs.getString("n"), amount(rs.getBigDecimal("total")), rs.getLong("cnt")));
        BigDecimal total = groups.stream().map(g -> new BigDecimal(g.total().value())).reduce(BigDecimal.ZERO, BigDecimal::add);
        long count = groups.stream().mapToLong(DataApi.Group::count).sum();
        return new DataApi.Summary(first.toString(), last.toString(), dir, by, amount(total), count, groups);
    }

    // ---- adding ---------------------------------------------------------------------------------------------

    /** @param replayed the same request was made before with this Idempotency-Key: this is what it created */
    public record Added(DataApi.Transaction transaction, boolean replayed) {}

    /**
     * Records a payment an app sends, within the token's limits (its accounts, its categories, money in only
     * when allowed). With an Idempotency-Key, a retry of the same request returns the first one instead of adding
     * it again.
     */
    @Transactional
    public Added add(ApiToken token, DataApi.NewTransaction in, String idempotencyKey) {
        if (in == null) throw new IllegalArgumentException("Send the transaction as JSON.");
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null && (key.length() > 100 || !key.matches("[\\x21-\\x7e]+"))) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to 100 visible ASCII characters.");
        }
        BigDecimal amount = in.amount();
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("amount must be a positive number.");
        if (amount.stripTrailingZeros().scale() > 2) throw new IllegalArgumentException("amount can have at most two decimals.");
        String dirName = in.direction() == null ? "debit" : in.direction().trim().toLowerCase(Locale.ROOT);
        Direction direction = switch (dirName) {
            case "debit" -> Direction.DEBIT;
            case "credit" -> Direction.CREDIT;
            default -> throw new IllegalArgumentException("direction must be debit or credit.");
        };
        if (direction == Direction.CREDIT && !token.isMoneyIn()) throw new Forbidden("This token can only add money going out.");
        if (in.account() == null || in.account().isBlank()) throw new IllegalArgumentException("account is required.");
        Long accountId = in.account().trim().matches("\\d+") ? Long.valueOf(in.account().trim()) : null;
        Account account = accountId == null ? null : accounts.findById(accountId).orElse(null);
        if (account == null || account.isArchived() || !token.allowsAccount(account.getId())) {
            throw new Forbidden("This token can't add to that account.");
        }
        Long categoryId = null;
        if (in.category() != null && !in.category().isBlank()) {
            String c = in.category().trim();
            Optional<Category> cat = c.matches("\\d+") ? categories.findById(Long.parseLong(c))
                    : categories.findAll().stream().filter(x -> x.getName().equalsIgnoreCase(c)).findFirst();
            if (cat.isEmpty() || !token.allowsCategory(cat.get().getId())) throw new Forbidden("This token can't use the category \"" + clip(c) + "\".");
            if (cat.get().getKind() != CategorizationService.kindFor(direction)) {
                throw new IllegalArgumentException(cat.get().getName() + " is " + (direction == Direction.DEBIT ? "an income" : "a spending")
                        + " category, so it can't be used for money " + (direction == Direction.DEBIT ? "going out." : "coming in."));
            }
            categoryId = cat.get().getId();
        }
        Instant at = Instant.now();
        if (in.occurredAt() != null && !in.occurredAt().isBlank()) {
            try {
                at = OffsetDateTime.parse(in.occurredAt().trim()).toInstant();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("occurred_at must be an RFC 3339 time like 2026-11-13T17:05:00+05:30.");
            }
            if (at.isAfter(Instant.now().plus(Duration.ofMinutes(5)))) throw new IllegalArgumentException("occurred_at can't be in the future.");
        }
        String payee = in.payee() == null || in.payee().isBlank() ? null : in.payee().trim();
        if (payee != null && payee.length() > 200) throw new IllegalArgumentException("payee can be at most 200 characters.");
        String notes = in.notes() == null || in.notes().isBlank() ? null : in.notes().trim();
        if (notes != null && notes.length() > 2000) throw new IllegalArgumentException("notes can be at most 2000 characters.");

        // what the request asked for, so a reused key with a different request is told apart from a retry
        String fingerprint = ApiTokenService.sha256(String.join("\u0000", amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                direction.name(), account.getId().toString(), String.valueOf(payee), String.valueOf(categoryId),
                in.occurredAt() == null ? "now" : at.toString(), String.valueOf(notes)));
        ApiToken me = tokens.getReferenceById(token.getId());
        if (key != null) {
            Optional<Txn> before = txns.findByApiTokenAndRequestKey(me, key);
            if (before.isPresent()) {
                if (!fingerprint.equals(before.get().getRequestHash())) {
                    throw new KeyReused("This Idempotency-Key was already used for a different request.");
                }
                return new Added(transaction(token, before.get()), true);
            }
        }
        Txn t = transactions.createManual(new TransactionService.ManualTxn(account.getId(), direction, amount, "INR", at,
                payee, null, categoryId, null, notes, false), account);
        t.setApiToken(me);
        t.setRequestKey(key);
        t.setRequestHash(key == null ? null : fingerprint);
        txns.flush();
        em.refresh(t);
        return new Added(transaction(token, t), false);
    }

    // ---- balances -------------------------------------------------------------------------------------------

    /** Balances of the token's accounts, as Accounts shows them: for a card, what's owed on it. */
    @Transactional(readOnly = true)
    public DataApi.Balances balances(ApiToken token) {
        List<DataApi.Balance> out = new ArrayList<>();
        for (Account a : accounts.findByArchivedFalseOrderByTypeAscNameAsc()) {
            if (!token.allowsAccount(a.getId())) continue;
            AccountService.AccountView v = accountViews.view(a);
            AccountService.CardInfo c = v.card();
            out.add(new DataApi.Balance(account(a), amount(v.balance()), c == null ? null
                    : new DataApi.Card(amount(c.creditLimit()), amount(c.available()),
                    c.dueDate() == null ? null : c.dueDate().toString(), c.dueDate() == null ? null : c.daysToDue())));
        }
        return new DataApi.Balances(out, time(Instant.now()));
    }

    // ---- SMS ------------------------------------------------------------------------------------------------

    static final int MAX_SMS = 2000;

    /** Records a bank SMS a phone forwarded; the same text sent twice is counted once. */
    @Transactional
    public DataApi.SmsReceived sms(ApiToken token, DataApi.NewSms in) {
        if (in == null || in.text() == null || in.text().isBlank()) throw new IllegalArgumentException("text is required.");
        if (in.text().length() > MAX_SMS) throw new IllegalArgumentException("text can be at most " + MAX_SMS + " characters.");
        String sender = in.sender() == null || in.sender().isBlank() ? null : in.sender().trim();
        if (sender != null && sender.length() > 100) throw new IllegalArgumentException("sender can be at most 100 characters.");
        Instant now = Instant.now(), at = now;
        if (in.receivedAt() != null && !in.receivedAt().isBlank()) {
            try {
                at = OffsetDateTime.parse(in.receivedAt().trim()).toInstant();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("received_at must be an RFC 3339 time like 2026-11-13T17:05:00+05:30.");
            }
            // a phone whose clock runs ahead: it's being forwarded now anyway
            if (at.isAfter(now)) at = now;
        }
        com.hisaab.mail.EmailProcessor.SmsOutcome o = processor.processSms(sender, in.text(), at);
        if (o.alreadySeen()) return new DataApi.SmsReceived("duplicate", "Already received this SMS", null);
        Txn t = o.txn();
        if (t != null) {
            txns.flush();
            em.refresh(t);
        }
        return new DataApi.SmsReceived(o.message().getStatus().name().toLowerCase(Locale.ROOT),
                t != null ? com.hisaab.mail.EmailProcessor.summary(t) : o.message().getNote(),
                t == null ? null : transaction(token, t));
    }
}
