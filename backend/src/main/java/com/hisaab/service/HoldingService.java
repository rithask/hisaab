package com.hisaab.service;

import com.hisaab.domain.Enums.AssetType;
import com.hisaab.domain.Holding;
import com.hisaab.domain.MfScheme;
import com.hisaab.repo.HoldingRepo;
import com.hisaab.repo.MfSchemeRepo;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class HoldingService {
    private static final Logger log = LoggerFactory.getLogger(HoldingService.class);

    private final HoldingRepo holdings;
    private final MfSchemeRepo schemes;
    private final PriceService prices;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public HoldingService(HoldingRepo holdings, MfSchemeRepo schemes, PriceService prices, JdbcTemplate jdbc,
                          com.hisaab.config.AppProperties props) {
        this.holdings = holdings;
        this.schemes = schemes;
        this.prices = prices;
        this.jdbc = jdbc;
        this.enabled = props.prices() == null || props.prices().enabled();
    }

    public record HoldingInput(AssetType assetType, String symbol, String exchange, String name,
                               BigDecimal quantity, BigDecimal avgPrice) {}

    @Transactional
    public Holding save(Holding h, HoldingInput in) {
        if (in.assetType() == null) throw new IllegalArgumentException("Asset type is required");
        if (in.symbol() == null || in.symbol().isBlank()) throw new IllegalArgumentException("Symbol is required");
        if (in.quantity() == null || in.quantity().signum() <= 0) throw new IllegalArgumentException("Quantity must be positive");
        if (in.avgPrice() == null || in.avgPrice().signum() < 0) throw new IllegalArgumentException("Average price is required");
        h.setAssetType(in.assetType());
        h.setSymbol(in.assetType() == AssetType.STOCK ? in.symbol().trim().toUpperCase(Locale.ROOT) : in.symbol().trim());
        h.setExchange(in.assetType() == AssetType.STOCK ? (in.exchange() == null ? "NSE" : in.exchange()) : null);
        h.setQuantity(in.quantity());
        h.setAvgPrice(in.avgPrice());
        if (in.name() != null && !in.name().isBlank()) h.setName(in.name().trim());
        if (in.assetType() == AssetType.MF) {
            schemes.findById(h.getSymbol()).ifPresent(s -> {
                if (h.getName() == null) h.setName(s.getName());
                h.setLastPrice(s.getNav());
                h.setPriceAsOf(s.getNavDate() == null ? null : s.getNavDate().atStartOfDay(Money.INDIA).toInstant());
            });
        }
        if (h.getName() == null) h.setName(h.getSymbol());
        holdings.save(h);
        if (in.assetType() == AssetType.STOCK) refreshStock(h);
        return h;
    }

    public record ImportResult(int imported, int removed, List<String> skipped) {}

    /**
     * Import holdings exported from Zerodha (Kite "Holdings → Download" CSV or Console holdings CSV).
     * Columns are detected by name. With {@code replace}, stocks missing from the file are removed (full sync).
     */
    @Transactional
    public ImportResult importCsv(InputStream in, boolean replace) throws java.io.IOException {
        Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        CSVFormat fmt = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setTrim(true).setAllowMissingColumnNames(true).build();
        int imported = 0;
        List<String> skipped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (CSVParser parser = CSVParser.parse(reader, fmt)) {
            Map<String, Integer> header = new HashMap<>();
            parser.getHeaderMap().forEach((k, v) -> header.put(k.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""), v));
            Integer sym = col(header, "instrument", "symbol", "tradingsymbol", "stock");
            Integer qty = col(header, "qty", "quantity", "quantityavailable", "netqty");
            Integer avg = col(header, "avgcost", "averageprice", "avgprice", "buyavg", "averagecost");
            if (sym == null || qty == null || avg == null) {
                throw new IllegalArgumentException("Couldn't find symbol/quantity/average price columns. Found: "
                        + parser.getHeaderNames());
            }
            for (CSVRecord r : parser) {
                try {
                    String symbol = r.get(sym).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                    if (symbol.isEmpty() || symbol.equals("TOTAL")) continue;
                    BigDecimal q = num(r.get(qty));
                    BigDecimal a = num(r.get(avg));
                    if (q.signum() <= 0) continue;
                    Holding h = holdings.findByAssetTypeAndSymbol(AssetType.STOCK, symbol).orElseGet(Holding::new);
                    h.setAssetType(AssetType.STOCK);
                    h.setSymbol(symbol);
                    if (h.getExchange() == null) h.setExchange("NSE");
                    if (h.getName() == null) h.setName(symbol);
                    h.setQuantity(q);
                    h.setAvgPrice(a);
                    holdings.save(h);
                    seen.add(symbol);
                    imported++;
                } catch (RuntimeException e) {
                    skipped.add("Row " + r.getRecordNumber() + ": " + e.getMessage());
                }
            }
        }
        for (String symbol : seen) {
            holdings.findByAssetTypeAndSymbol(AssetType.STOCK, symbol).ifPresent(this::refreshStock);
        }
        int removed = 0;
        if (replace && imported > 0) {
            for (Holding h : holdings.findByAssetType(AssetType.STOCK)) {
                if (!seen.contains(h.getSymbol())) {
                    holdings.delete(h);
                    removed++;
                }
            }
        }
        return new ImportResult(imported, removed, skipped);
    }

    private static Integer col(Map<String, Integer> header, String... names) {
        for (String n : names) if (header.containsKey(n)) return header.get(n);
        return null;
    }

    private static BigDecimal num(String s) {
        return new BigDecimal(s.replace(",", "").replace("₹", "").trim());
    }

    // ---- prices ------------------------------------------------------------------------------------------

    @Transactional
    public void refreshStock(Holding h) {
        String suffix = "BSE".equals(h.getExchange()) ? ".BO" : ".NS";
        prices.quote(h.getSymbol() + suffix).ifPresent(q -> {
            h.setLastPrice(q.price());
            h.setPrevClose(q.previousClose());
            h.setPriceAsOf(q.asOf());
            if (q.name() != null && (h.getName() == null || h.getName().equals(h.getSymbol()))) h.setName(q.name());
            holdings.save(h); // may be called outside a transaction (scheduler / self-invocation)
        });
    }

    /** Every 15 minutes during NSE hours, plus once after close. */
    // NSE trading hours and AMFI NAV publishing are India time, whatever TZ is.
    @Scheduled(cron = "0 */15 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "0 5 16 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledStockRefresh() {
        if (enabled) refreshAllStocks();
    }

    @Transactional
    public int refreshAllStocks() {
        int n = 0;
        for (Holding h : holdings.findByAssetType(AssetType.STOCK)) {
            Instant before = h.getPriceAsOf();
            refreshStock(h);
            if (!Objects.equals(before, h.getPriceAsOf())) n++;
        }
        return n;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!enabled) return;
        try {
            if (schemes.count() == 0) refreshMfNavs();
            refreshAllStocks();
        } catch (Exception e) {
            log.warn("Initial price refresh failed: {}", e.toString());
        }
    }

    /** AMFI publishes NAVs in the evening; fetch late night and again in the morning. */
    @Scheduled(cron = "0 30 23 * * *", zone = "Asia/Kolkata")
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Kolkata")
    public void scheduledNavRefresh() {
        if (!enabled) return;
        try {
            refreshMfNavs();
        } catch (Exception e) {
            log.warn("NAV refresh failed: {}", e.toString());
        }
    }

    private static final DateTimeFormatter AMFI_DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    /**
     * Load AMFI's NAVAll.txt. Columns are located from the header row, since AMFI has changed the layout over time
     * (currently "Scheme Code;ISIN…;ISIN…;Scheme Name;Plan;Option;Net Asset Value;Date").
     */
    public int refreshMfNavs() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(prices.amfiNavFile(), StandardCharsets.UTF_8))) {
            int iCode = 0, iName = 3, iPlan = -1, iOption = -1, iNav = 4, iDate = 5;
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(";", -1);
                if (f.length > 0 && f[0].trim().equalsIgnoreCase("Scheme Code")) {
                    for (int i = 0; i < f.length; i++) {
                        String h = f[i].trim().toLowerCase(Locale.ROOT);
                        if (h.equals("scheme name")) iName = i;
                        else if (h.equals("plan")) iPlan = i;
                        else if (h.equals("option")) iOption = i;
                        else if (h.startsWith("net asset value")) iNav = i;
                        else if (h.equals("date")) iDate = i;
                    }
                    continue;
                }
                if (f.length <= Math.max(iNav, iDate) || !f[iCode].trim().matches("\\d+")) continue;
                BigDecimal nav;
                LocalDate date;
                try {
                    nav = new BigDecimal(f[iNav].trim());
                    date = LocalDate.parse(f[iDate].trim(), AMFI_DATE);
                } catch (RuntimeException e) {
                    continue; // "N.A." NAVs etc.
                }
                StringBuilder name = new StringBuilder(f[iName].trim());
                for (int i : new int[]{iPlan, iOption}) {
                    if (i >= 0 && !f[i].isBlank() && !f[i].trim().equals("-")) name.append(" - ").append(f[i].trim());
                }
                rows.add(new Object[]{f[iCode].trim(), name.toString(), nav, Date.valueOf(date)});
            }
        }
        jdbc.batchUpdate("""
                insert into mf_scheme (code, name, nav, nav_date) values (?, ?, ?, ?)
                on conflict (code) do update set name = excluded.name, nav = excluded.nav, nav_date = excluded.nav_date
                """, rows);
        jdbc.update("""
                update holding h set last_price = m.nav, price_as_of = m.nav_date::timestamp at time zone 'Asia/Kolkata'
                from mf_scheme m where h.asset_type = 'MF' and h.symbol = m.code
                """);
        log.info("Loaded {} mutual fund NAVs from AMFI", rows.size());
        return rows.size();
    }

    /** Every word must appear in the scheme name, e.g. "parag flexi direct growth". */
    public List<MfScheme> searchSchemes(String q) {
        String[] words = q.trim().toLowerCase(Locale.ROOT).split("\\s+");
        org.springframework.data.jpa.domain.Specification<MfScheme> spec = (root, query, cb) -> cb.and(
                Arrays.stream(words).map(w -> cb.like(cb.lower(root.get("name")), "%" + w + "%"))
                        .toArray(jakarta.persistence.criteria.Predicate[]::new));
        return schemes.findAll(spec, org.springframework.data.domain.PageRequest.of(0, 20,
                org.springframework.data.domain.Sort.by("name"))).getContent();
    }
}
