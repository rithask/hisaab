package com.hisaab.service;

import com.hisaab.domain.ApiToken;
import com.hisaab.domain.ApiToken.Job;
import com.hisaab.repo.AccountRepo;
import com.hisaab.repo.ApiTokenRepo;
import com.hisaab.repo.CategoryRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * The tokens apps and shortcuts use: an iPhone Shortcut forwarding bank SMS, a mileage app reading fuel spends, a
 * widget showing balances. Each token does one job, within the limits I chose. Only a SHA-256 hash is stored; the
 * token itself is shown once, when it's created.
 */
@Service
public class ApiTokenService {
    private static final Logger log = LoggerFactory.getLogger(ApiTokenService.class);
    private static final String PREFIX = "hisaab_";

    private final ApiTokenRepo tokens;
    private final CategoryRepo categories;
    private final AccountRepo accounts;
    private final SecureRandom random = new SecureRandom();

    public ApiTokenService(ApiTokenRepo tokens, CategoryRepo categories, AccountRepo accounts) {
        this.tokens = tokens;
        this.categories = categories;
        this.accounts = accounts;
    }

    /**
     * What I chose for a token. Limits that don't apply to its job are ignored.
     *
     * @param categories category ids (and "none" for uncategorised) it may see or use; null for all
     * @param accounts   account ids it may see or add to; null for all
     * @param moneyIn    also money coming in (otherwise money out only)
     */
    public record TokenInput(String name, Job job, List<String> categories, List<Long> accounts, Boolean moneyIn, Boolean includeNotes) {}

    public record TokenView(Long id, String name, Job job, String hint, List<String> categories, List<Long> accounts,
                            boolean moneyIn, boolean includeNotes, Instant createdAt, Instant lastUsedAt) {}

    public record Created(String token, TokenView view) {}

    @Transactional(readOnly = true)
    public List<TokenView> list() {
        return tokens.findByRevokedAtIsNullOrderByCreatedAtAsc().stream().map(this::view).toList();
    }

    @Transactional
    public Created create(TokenInput in) {
        if (in.job() == null) throw new IllegalArgumentException("Choose what the token is for");
        ApiToken t = new ApiToken();
        t.setJob(in.job());
        apply(t, in);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        t.setTokenHash(sha256(token));
        t.setHint(token.substring(token.length() - 4));
        ApiToken saved = tokens.save(t);
        log.info("API token created id={} name=\"{}\" job={} hint=…{}", saved.getId(), forLog(saved.getName()), saved.getJob(), saved.getHint());
        return new Created(token, view(saved));
    }

    /** Changes a token's name or limits; its job stays (make a new token for another job). */
    @Transactional
    public TokenView update(Long id, TokenInput in) {
        ApiToken t = active(id);
        apply(t, in);
        log.info("API token changed id={} name=\"{}\" job={}", t.getId(), forLog(t.getName()), t.getJob());
        return view(t);
    }

    @Transactional
    public void revoke(Long id) {
        ApiToken t = active(id);
        t.setRevokedAt(Instant.now());
        log.info("API token revoked id={} name=\"{}\" job={}", t.getId(), forLog(t.getName()), t.getJob());
    }

    /** A name as it goes in the log: one line, no control characters. */
    private static String forLog(String s) {
        return s == null ? "" : s.replaceAll("\\p{Cntrl}", " ");
    }

    /** The active token presented, if it's one of mine; notes when it was used. */
    @Transactional
    public Optional<ApiToken> authenticate(String presented) {
        if (presented == null || !presented.startsWith(PREFIX) || presented.length() > 200) return Optional.empty();
        Optional<ApiToken> t = tokens.findByTokenHashAndRevokedAtIsNull(sha256(presented));
        t.ifPresent(x -> {
            Instant now = Instant.now();
            // once a minute is enough, so busy apps don't write on every request
            if (x.getLastUsedAt() == null || x.getLastUsedAt().isBefore(now.minus(Duration.ofMinutes(1)))) x.setLastUsedAt(now);
        });
        return t;
    }

    private ApiToken active(Long id) {
        return tokens.findById(id).filter(ApiToken::isActive).orElseThrow(() -> new NoSuchElementException("Token not found"));
    }

    private void apply(ApiToken t, TokenInput in) {
        String name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Give the token a name, like \"Mileage app\"");
        if (name.length() > 60) throw new IllegalArgumentException("Keep the name under 60 characters");
        t.setName(name);
        Job job = t.getJob();
        boolean usesCategories = job == Job.READ_TRANSACTIONS || job == Job.ADD_TRANSACTIONS || job == Job.READ_TOTALS;
        boolean usesAccounts = job != Job.SEND_SMS;
        t.setCategoryIds(usesCategories ? categoryLimit(in.categories()) : null);
        t.setAccountIds(usesAccounts ? accountLimit(in.accounts()) : null);
        t.setMoneyIn(usesCategories && Boolean.TRUE.equals(in.moneyIn()));
        t.setIncludeNotes(job != Job.READ_TRANSACTIONS || !Boolean.FALSE.equals(in.includeNotes()));
    }

    private String categoryLimit(List<String> ids) {
        if (ids == null) return null;
        if (ids.isEmpty()) throw new IllegalArgumentException("Pick at least one category, or All");
        Set<String> out = new LinkedHashSet<>();
        for (String raw : ids) {
            String v = raw == null ? "" : raw.trim();
            if (v.equals(ApiToken.UNCATEGORISED)) {
                out.add(v);
                continue;
            }
            try {
                long id = Long.parseLong(v);
                if (!categories.existsById(id)) throw new IllegalArgumentException("Unknown category");
                out.add(Long.toString(id));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Unknown category");
            }
        }
        return ApiToken.join(out);
    }

    private String accountLimit(List<Long> ids) {
        if (ids == null) return null;
        if (ids.isEmpty()) throw new IllegalArgumentException("Pick at least one account, or All");
        Set<Long> out = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null || !accounts.existsById(id)) throw new IllegalArgumentException("Unknown account");
            out.add(id);
        }
        return ApiToken.join(out);
    }

    private TokenView view(ApiToken t) {
        // limits name what exists now: a deleted category simply drops out (and allows nothing in its place)
        Set<String> cats = t.categoryLimit();
        Set<Long> accts = t.accountLimit();
        List<String> c = cats == null ? null : cats.stream()
                .filter(x -> x.equals(ApiToken.UNCATEGORISED) || categories.existsById(Long.valueOf(x))).toList();
        List<Long> a = accts == null ? null : accts.stream().filter(accounts::existsById).toList();
        return new TokenView(t.getId(), t.getName(), t.getJob(), t.getHint(), c, a, t.isMoneyIn(), t.isIncludeNotes(),
                t.getCreatedAt(), t.getLastUsedAt());
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
