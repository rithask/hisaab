package com.hisaab.mail;

import java.time.Instant;
import java.util.List;

/** @param senderCheck PASS, FAIL or NONE; see {@link SenderTrust#check} */
public record IncomingEmail(String messageId, String from, String subject, Instant receivedAt, String text,
                            List<Attachment> attachments, String senderCheck) {

    public record Attachment(String fileName, String contentType, byte[] data) {
        public boolean isPdf() {
            return (contentType != null && contentType.toLowerCase().contains("pdf"))
                    || (fileName != null && fileName.toLowerCase().endsWith(".pdf"));
        }
    }
}
