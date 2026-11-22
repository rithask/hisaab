package com.hisaab.dataapi.service;

import com.hisaab.dataapi.dto.TokenCreatedResponse;
import com.hisaab.dataapi.dto.TokenRequest;
import com.hisaab.dataapi.dto.TokenResponse;
import com.hisaab.dataapi.model.TokenJob;
import com.hisaab.accounts.repository.AccountRepository;
import com.hisaab.categories.repository.CategoryRepository;
import com.hisaab.dataapi.model.ApiToken;
import com.hisaab.dataapi.repository.ApiTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hisaab.common.exception.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.hisaab.common.exception.NotFound.notFound;

/**
 * The tokens apps and shortcuts use: an iPhone Shortcut forwarding bank SMS, a mileage app reading fuel spends, a
 * widget showing balances. Each token does one job, within the limits I chose. Only a SHA-256 hash is stored; the
 * token itself is shown once, when it's created.
 */
@Service
public class ApiTokenService {
    private static final Logger log = LoggerFactory.getLogger(ApiTokenService.class);
    private static final String PREFIX = "hisaab_";

    private final ApiTokenRepository tokens;
    private final CategoryRepository categories;
    private final AccountRepository accounts;
    private final SecureRandom random = new SecureRandom();

    public ApiTokenService(ApiTokenRepository tokens, CategoryRepository categories, AccountRepository accounts) {
        this.tokens = tokens;
        this.categories = categories;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public List<TokenResponse> list() {
        return tokens.findByRevokedAtIsNullOrderByCreatedAtAsc().stream().map(this::view).toList();
    }

    @Transactional
    public TokenCreatedResponse create(TokenRequest in) {
        if (in.job() == null) throw new BadRequestException("Choose what the token is for");
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
        return new TokenCreatedResponse(token, view(saved));
    }

    /** Changes a token's name or limits; its job stays (make a new token for another job). */
    @Transactional
    public TokenResponse update(Long id, TokenRequest in) {
        ApiToken token = active(id);
        apply(token, in);
        log.info("API token changed id={} name=\"{}\" job={}", token.getId(), forLog(token.getName()), token.getJob());
        return view(token);
    }

    @Transactional
    public void revoke(Long id) {
        ApiToken token = active(id);
        token.setRevokedAt(Instant.now());
        log.info("API token revoked id={} name=\"{}\" job={}", token.getId(), forLog(token.getName()), token.getJob());
    }

    /** A name as it goes in the log: one line, no control characters. */
    private static String forLog(String s) {
        return s == null ? "" : s.replaceAll("\\p{Cntrl}", " ");
    }

    /** The active token presented, if it's one of mine; notes when it was used. */
    @Transactional
    public Optional<ApiToken> authenticate(String presented) {
        if (presented == null || !presented.startsWith(PREFIX) || presented.length() > 200) return Optional.empty();
        Optional<ApiToken> token = tokens.findByTokenHashAndRevokedAtIsNull(sha256(presented));
        token.ifPresent(x -> {
            Instant now = Instant.now();
            // once a minute is enough, so busy apps don't write on every request
            if (x.getLastUsedAt() == null || x.getLastUsedAt().isBefore(now.minus(Duration.ofMinutes(1)))) x.setLastUsedAt(now);
        });
        return token;
    }

    private ApiToken active(Long id) {
        return tokens.findById(id).filter(ApiToken::isActive).orElseThrow(notFound("Token"));
    }

    private void apply(ApiToken token, TokenRequest in) {
        String name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty()) throw new BadRequestException("Give the token a name, like \"Mileage app\"");
        if (name.length() > 60) throw new BadRequestException("Keep the name under 60 characters");
        token.setName(name);
        TokenJob job = token.getJob();
        boolean usesCategories = job == TokenJob.READ_TRANSACTIONS || job == TokenJob.ADD_TRANSACTIONS || job == TokenJob.READ_TOTALS;
        boolean usesAccounts = job != TokenJob.SEND_SMS;
        token.setCategoryIds(usesCategories ? categoryLimit(in.categories()) : null);
        token.setAccountIds(usesAccounts ? accountLimit(in.accounts()) : null);
        token.setMoneyIn(usesCategories && Boolean.TRUE.equals(in.moneyIn()));
        token.setIncludeNotes(job != TokenJob.READ_TRANSACTIONS || !Boolean.FALSE.equals(in.includeNotes()));
    }

    private String categoryLimit(List<String> ids) {
        if (ids == null) return null;
        if (ids.isEmpty()) throw new BadRequestException("Pick at least one category, or All");
        Set<String> out = new LinkedHashSet<>();
        for (String raw : ids) {
            String v = raw == null ? "" : raw.trim();
            if (v.equals(ApiToken.UNCATEGORISED)) {
                out.add(v);
                continue;
            }
            try {
                long id = Long.parseLong(v);
                if (!categories.existsById(id)) throw new BadRequestException("Unknown category");
                out.add(Long.toString(id));
            } catch (NumberFormatException e) {
                throw new BadRequestException("Unknown category");
            }
        }
        return ApiToken.join(out);
    }

    private String accountLimit(List<Long> ids) {
        if (ids == null) return null;
        if (ids.isEmpty()) throw new BadRequestException("Pick at least one account, or All");
        Set<Long> out = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null || !accounts.existsById(id)) throw new BadRequestException("Unknown account");
            out.add(id);
        }
        return ApiToken.join(out);
    }

    private TokenResponse view(ApiToken t) {
        // limits name what exists now: a deleted category simply drops out (and allows nothing in its place)
        Set<String> cats = t.categoryLimit();
        Set<Long> accts = t.accountLimit();
        List<String> c = cats == null ? null : cats.stream()
                .filter(x -> x.equals(ApiToken.UNCATEGORISED) || categories.existsById(Long.valueOf(x))).toList();
        List<Long> a = accts == null ? null : accts.stream().filter(accounts::existsById).toList();
        return new TokenResponse(t.getId(), t.getName(), t.getJob(), t.getHint(), c, a, t.isMoneyIn(), t.isIncludeNotes(),
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
