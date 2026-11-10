package com.hisaab.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Normalisation and fuzzy matching of merchant and person names. */
public final class Names {
    private Names() {}

    /** "Swiggy Instamart" -> "swiggy instamart"; strips digits/punctuation so variants collapse together. */
    public static String merchantKey(String counterparty) {
        if (counterparty == null) return null;
        String k = counterparty.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return k.isEmpty() ? null : k;
    }

    static Set<String> tokens(String s) {
        if (s == null) return Set.of();
        return Arrays.stream(s.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").split("\\s+"))
                .filter(t -> t.length() >= 3)
                .collect(Collectors.toSet());
    }

    /**
     * True when two names refer to the same person: every significant token of the shorter name appears in the
     * longer one, and at least two tokens overlap (or one, if a name has a single token).
     * "ARJUN RAVI K" matches "Arjun Ravi K"; "Arjun Das" does not.
     */
    public static boolean samePerson(String a, String b) {
        Set<String> ta = tokens(a), tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return false;
        long overlap = ta.stream().filter(tb::contains).count();
        int smaller = Math.min(ta.size(), tb.size());
        return overlap == smaller && overlap >= Math.min(2, smaller);
    }

    /** Whole-word, case-insensitive containment. */
    public static boolean containsWord(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isBlank()) return false;
        String h = " " + haystack.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ") + " ";
        String n = " " + needle.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim() + " ";
        return h.contains(n);
    }
}
