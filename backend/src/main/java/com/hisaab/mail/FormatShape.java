package com.hisaab.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A learned format as a list of words, so two formats can be lined up and merged. Each word has a key (how it
 * compares: the lower-cased word, {@code #} for anything with digits, {@code <AMT>}-style labels for slots) and
 * the regex that matches it. A word may allow any number of other words before it ({@code gapBefore}), and the
 * format may allow any words at the end ({@code gapEnd}); that's how wording that differs between two messages
 * of the same kind is let through.
 */
final class FormatShape {
    record Token(String key, String rx, boolean gapBefore) {
        boolean slot() {
            return key.contains("<");
        }
    }

    final List<Token> tokens;
    final boolean gapEnd;

    FormatShape(List<Token> tokens, boolean gapEnd) {
        this.tokens = List.copyOf(tokens);
        this.gapEnd = gapEnd;
    }

    /** Collects a message's pieces word by word as the format is built. */
    static final class Builder {
        private final StringBuilder key = new StringBuilder(), rx = new StringBuilder();
        private final List<Token> out = new ArrayList<>();

        void piece(String k, String r) {
            key.append(k);
            rx.append(r);
        }

        void space() {
            if (rx.isEmpty()) return;
            out.add(new Token(key.toString(), rx.toString(), false));
            key.setLength(0);
            rx.setLength(0);
        }

        FormatShape done() {
            space();
            return new FormatShape(out, false);
        }
    }

    private static final String GAP = "(?:\\S+\\s+)*?", GAP_END = "(?:\\s+\\S+)*?";

    /** The whole-message regex. The payee takes the rest of the message only when nothing can follow it. */
    String pattern() {
        StringBuilder p = new StringBuilder("^");
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (i > 0) p.append("\\s+");
            if (t.gapBefore()) p.append(GAP);
            boolean last = i == tokens.size() - 1 && !gapEnd;
            String rx = t.rx().replace("(?<payee>.+)", "(?<payee>.+?)");
            if (last && rx.endsWith("(?<payee>.+?)")) rx = rx.substring(0, rx.length() - "(?<payee>.+?)".length()) + "(?<payee>.+)";
            p.append(rx);
        }
        if (gapEnd) p.append(GAP_END);
        return p.append('$').toString();
    }

    /** Readable form, e.g. {@code inr <AMT> debited from a/c *<L4> * on #}; {@code *} marks varying words. */
    String skeleton() {
        StringBuilder s = new StringBuilder();
        for (Token t : tokens) {
            if (!s.isEmpty()) s.append(' ');
            if (t.gapBefore()) s.append("* ");
            s.append(t.key());
        }
        if (gapEnd) s.append(" *");
        return s.toString();
    }

    private static final char TOK = '\u0001', FIELD = '\u0002';

    String encode() {
        StringBuilder s = new StringBuilder(gapEnd ? "1" : "0");
        for (Token t : tokens) s.append(TOK).append(t.gapBefore() ? '1' : '0').append(FIELD).append(t.key()).append(FIELD).append(t.rx());
        return s.toString();
    }

    static Optional<FormatShape> decode(String s) {
        if (s == null || s.isEmpty()) return Optional.empty();
        String[] parts = s.split(String.valueOf(TOK), -1);
        List<Token> tokens = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String[] f = parts[i].split(String.valueOf(FIELD), 3);
            if (f.length != 3) return Optional.empty();
            tokens.add(new Token(f[1], f[2], f[0].equals("1")));
        }
        return Optional.of(new FormatShape(tokens, parts[0].equals("1")));
    }

    /** How alike two formats are, 0..1: twice the words they share in order, over all their words. */
    static double similarity(FormatShape a, FormatShape b, int[][] lcs) {
        int n = a.tokens.size(), m = b.tokens.size();
        return n + m == 0 ? 0 : 2.0 * lcs[0][0] / (n + m);
    }

    /** The minimum share of words two formats must have in common to be merged. */
    static final double MERGE_AT = 0.7;

    /**
     * One format that reads what both read: the words they share stay, the ones only one has become "any words".
     * Empty when they're too different, their slots (amount, payee, account, reference, balance) don't line up one
     * to one in the same order, or a varying stretch would sit right after the payee (it couldn't tell where the
     * payee ends).
     */
    static Optional<FormatShape> merge(FormatShape a, FormatShape b) {
        int n = a.tokens.size(), m = b.tokens.size();
        int[][] l = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--)
                l[i][j] = a.tokens.get(i).key().equals(b.tokens.get(j).key()) ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
        if (l[0][0] < 4 || similarity(a, b, l) < MERGE_AT) return Optional.empty();
        if (!slots(a).equals(slots(b))) return Optional.empty();

        List<Token> out = new ArrayList<>();
        int i = 0, j = 0;
        boolean gap = false;
        while (i < n && j < m) {
            Token x = a.tokens.get(i), y = b.tokens.get(j);
            if (x.key().equals(y.key())) {
                boolean g = gap || x.gapBefore() || y.gapBefore();
                if (g && !out.isEmpty() && out.get(out.size() - 1).key().contains("<PAYEE>")) return Optional.empty();
                String rx = x.rx().equals(y.rx()) || x.slot() ? x.rx() : "(?:" + x.rx() + "|" + y.rx() + ")";
                out.add(new Token(x.key(), rx, g));
                gap = false;
                i++;
                j++;
            } else {
                if (x.slot() && y.slot()) return Optional.empty();
                // skip the word that isn't part of the longest common run (a slot is never skipped)
                if (!x.slot() && (y.slot() || l[i + 1][j] >= l[i][j + 1])) i++;
                else j++;
                gap = true;
            }
        }
        boolean leftover = i < n || j < m;
        for (; i < n; i++) if (a.tokens.get(i).slot()) return Optional.empty();
        for (; j < m; j++) if (b.tokens.get(j).slot()) return Optional.empty();
        boolean end = a.gapEnd || b.gapEnd || leftover || gap;
        if (end && !out.isEmpty() && out.get(out.size() - 1).key().contains("<PAYEE>")) return Optional.empty();
        return Optional.of(new FormatShape(out, end));
    }

    private static List<String> slots(FormatShape f) {
        return f.tokens.stream().filter(Token::slot).map(Token::key).toList();
    }
}
