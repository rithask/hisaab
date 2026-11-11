package com.hisaab.mail;

import com.hisaab.domain.Enums.Institution;
import com.hisaab.domain.TrustedSender;
import com.hisaab.repo.TrustedSenderRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether an alert email really comes from its bank before it becomes a transaction. Anyone can put
 * "HDFC Bank" and the right wording in an email, so two things must hold:
 * <ul>
 *   <li>the From address is one of the bank's domains (built in below, plus any I've trusted), and</li>
 *   <li>my mail provider didn't report it as forged. Gmail, iCloud and most providers add an
 *       {@code Authentication-Results} header (RFC 8601) saying whether DMARC/DKIM confirmed the From domain.</li>
 * </ul>
 * Emails that fail either are kept for review, never dropped.
 */
@Service
public class SenderTrust {
    public static final String PASS = "PASS", FAIL = "FAIL", NONE = "NONE";
    public static final String UNKNOWN_SENDER = "UNKNOWN_SENDER", UNVERIFIED_SENDER = "UNVERIFIED_SENDER";

    /** The domains each bank sends alerts and statements from (old domains and the RBI-mandated .bank.in ones). */
    static final Map<Institution, List<String>> BANK_DOMAINS = Map.of(
            Institution.HDFC, List.of("hdfcbank.net", "hdfcbank.com", "hdfc.bank.in", "hdfcbank.bank.in"),
            Institution.AXIS, List.of("axisbank.com", "axis.bank.in", "axisbank.bank.in"),
            Institution.CANARA, List.of("canarabank.com", "canarabank.in", "canarabank.bank.in"),
            Institution.SBI, List.of("sbi.co.in", "sbi.bank.in", "sbicard.com", "sbicard.bank.in"));

    private final TrustedSenderRepo trusted;

    public SenderTrust(TrustedSenderRepo trusted) {
        this.trusted = trusted;
    }

    public record Verdict(boolean ok, String hold, String note) {}

    /** Whether an alert email from {@code from} with this provider verdict may be recorded as {@code bank}'s. */
    @Transactional(readOnly = true)
    public Verdict judge(String from, String senderCheck, Institution bank, String bankName) {
        String domain = domainOf(from);
        if (FAIL.equals(senderCheck)) {
            return new Verdict(false, UNVERIFIED_SENDER, "Your mail provider couldn't confirm this really came from "
                    + (domain == null ? "its sender" : domain) + ", so it wasn't recorded. If it's genuine, add the transaction by hand.");
        }
        if (domain != null && (matches(domain, BANK_DOMAINS.getOrDefault(bank, List.of())) || isTrusted(domain))) {
            return new Verdict(true, null, null);
        }
        return new Verdict(false, UNKNOWN_SENDER, "Sent from " + (domain == null ? "an unknown address" : domain)
                + ", which isn't a known " + bankName + " address, so it wasn't recorded. If it's your bank, trust this sender.");
    }

    private boolean isTrusted(String domain) {
        for (String d = domain; d != null; d = parent(d)) if (trusted.existsById(d)) return true;
        return false;
    }

    @Transactional
    public void trust(String domain) {
        String d = domain == null ? null : domain.trim().toLowerCase(Locale.ROOT);
        if (d == null || !d.matches("[a-z0-9.-]+\\.[a-z]{2,}")) throw new IllegalArgumentException("Not an email domain: " + domain);
        if (!trusted.existsById(d)) trusted.save(new TrustedSender(d));
    }

    public static String domainOf(String from) {
        if (from == null) return null;
        int at = from.lastIndexOf('@');
        if (at < 0) return null;
        String d = from.substring(at + 1).trim().toLowerCase(Locale.ROOT).replaceAll("[>\\s]+$", "");
        return d.isEmpty() ? null : d;
    }

    /** {@code alerts.hdfcbank.net} matches {@code hdfcbank.net}; {@code hdfcbank.net.evil.com} doesn't. */
    static boolean matches(String domain, List<String> allowed) {
        for (String a : allowed) if (domain.equals(a) || domain.endsWith("." + a)) return true;
        return false;
    }

    private static String parent(String d) {
        int dot = d.indexOf('.');
        return dot < 0 || d.indexOf('.', dot + 1) < 0 ? null : d.substring(dot + 1);
    }

    private static final Pattern RESULT = Pattern.compile("(?i)\\b(dmarc|dkim|spf)\\s*=\\s*([a-z]+)([^;]*)");
    private static final Pattern SIGNER = Pattern.compile("(?i)header\\.(?:d|i)\\s*=\\s*@?([a-z0-9.-]+)");
    private static final Pattern MAIL_FROM = Pattern.compile("(?i)smtp\\.mailfrom\\s*=\\s*(?:[^@\\s;]*@)?([a-z0-9.-]+)");

    /**
     * Reads the provider's {@code Authentication-Results} header: the topmost one, which my provider adds (copies
     * that came inside the message sit below it). DMARC decides when present: pass is PASS, fail is FAIL. Without
     * a DMARC verdict, a passing DKIM signature or SPF check for the From domain is PASS. Anything else is NONE:
     * not confirmed, but not reported as forged either. Banks publish DMARC, so a forged bank address fails it.
     */
    public static String check(String authResults, String from) {
        if (authResults == null || authResults.isBlank()) return NONE;
        String fromDomain = domainOf(from);
        Matcher m = RESULT.matcher(authResults.replaceAll("\\s+", " "));
        boolean aligned = false;
        while (m.find()) {
            String kind = m.group(1).toLowerCase(Locale.ROOT), result = m.group(2).toLowerCase(Locale.ROOT);
            if (kind.equals("dmarc")) {
                if (result.equals("pass")) return PASS;
                if (result.equals("fail")) return FAIL;
            } else if (result.equals("pass") && fromDomain != null) {
                Matcher d = (kind.equals("dkim") ? SIGNER : MAIL_FROM).matcher(m.group(3));
                if (d.find() && related(fromDomain, d.group(1).toLowerCase(Locale.ROOT))) aligned = true;
            }
        }
        return aligned ? PASS : NONE;
    }

    /** Relaxed alignment (as DMARC defines it): one domain is the other or a subdomain of it. */
    private static boolean related(String a, String b) {
        return a.equals(b) || a.endsWith("." + b) || b.endsWith("." + a);
    }
}
