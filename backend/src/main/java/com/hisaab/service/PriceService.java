package com.hisaab.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Market data from Yahoo Finance's public chart endpoint (delayed ~15 min for NSE/BSE) and FX rates.
 * Unofficial and occasionally rate-limited, so every call degrades gracefully.
 */
@Service
public class PriceService {
    private static final Logger log = LoggerFactory.getLogger(PriceService.class);
    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper json;
    private final Map<String, Quote> fxCache = new ConcurrentHashMap<>();

    public PriceService(ObjectMapper json) {
        this.json = json;
    }

    public record Quote(BigDecimal price, BigDecimal previousClose, String name, Instant asOf) {}

    /** @param yahooSymbol e.g. RELIANCE.NS, 500325.BO, USDINR=X */
    public Optional<Quote> quote(String yahooSymbol) {
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/"
                + URLEncoder.encode(yahooSymbol, StandardCharsets.UTF_8) + "?range=1d&interval=1d";
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", UA).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("Price lookup {} failed: HTTP {}", yahooSymbol, res.statusCode());
                return Optional.empty();
            }
            JsonNode meta = json.readTree(res.body()).path("chart").path("result").path(0).path("meta");
            if (!meta.has("regularMarketPrice")) return Optional.empty();
            BigDecimal price = meta.get("regularMarketPrice").decimalValue();
            JsonNode prev = meta.has("chartPreviousClose") ? meta.get("chartPreviousClose") : meta.get("previousClose");
            String name = meta.has("longName") ? meta.get("longName").asString()
                    : meta.has("shortName") ? meta.get("shortName").asString() : null;
            Instant asOf = meta.has("regularMarketTime")
                    ? Instant.ofEpochSecond(meta.get("regularMarketTime").asLong()) : Instant.now();
            return Optional.of(new Quote(price, prev == null ? null : prev.decimalValue(), name, asOf));
        } catch (IOException | JacksonException e) {
            log.warn("Price lookup {} failed: {}", yahooSymbol, e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** INR per unit of the given currency, cached for 6 hours. */
    public Optional<BigDecimal> inrRate(String currency) {
        if ("INR".equals(currency)) return Optional.of(BigDecimal.ONE);
        Quote cached = fxCache.get(currency);
        if (cached != null && cached.asOf().isAfter(Instant.now().minus(Duration.ofHours(6)))) {
            return Optional.of(cached.price());
        }
        Optional<Quote> q = quote(currency + "INR=X");
        q.ifPresent(v -> fxCache.put(currency, new Quote(v.price(), null, null, Instant.now())));
        return q.map(Quote::price).or(() -> Optional.ofNullable(cached).map(Quote::price));
    }

    /** Download AMFI's daily NAV file (all mutual fund schemes). */
    public InputStream amfiNavFile() throws IOException, InterruptedException {
        HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(
                        URI.create("https://portal.amfiindia.com/spages/NAVAll.txt"))
                .header("User-Agent", UA).timeout(Duration.ofSeconds(60)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) throw new IOException("AMFI NAV download failed: HTTP " + res.statusCode());
        return res.body();
    }
}
