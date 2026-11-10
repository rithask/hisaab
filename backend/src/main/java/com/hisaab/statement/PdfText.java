package com.hisaab.statement;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Text of a (possibly password-protected) PDF, one visual line per text line. */
public final class PdfText {
    private PdfText() {}

    public static class PasswordRequired extends IOException {
        public PasswordRequired(String message) {
            super(message);
        }
    }

    public record Opened(String text, String password) {}

    /** Tries no password, then each candidate. Banks set either a user or an owner password; both work here. */
    public static Opened open(byte[] pdf, List<String> candidates) throws IOException {
        LinkedHashSet<String> tries = new LinkedHashSet<>();
        tries.add("");
        for (String c : candidates) if (c != null && !c.isBlank()) tries.add(c.trim());
        boolean wrongGiven = false;
        for (String pw : new ArrayList<>(tries)) {
            try (PDDocument doc = Loader.loadPDF(pdf, pw)) {
                PDFTextStripper s = new PDFTextStripper();
                s.setSortByPosition(true);
                return new Opened(s.getText(doc), pw);
            } catch (InvalidPasswordException e) {
                if (!pw.isEmpty()) wrongGiven = true;
            }
        }
        throw new PasswordRequired(wrongGiven ? "That password didn't open the statement." : "This statement is password protected.");
    }
}
