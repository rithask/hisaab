package com.hisaab.mail;

import com.hisaab.config.AppProperties;
import com.hisaab.service.SettingsService;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reads the dedicated alerts mailbox over IMAP (Gmail: imap.gmail.com, iCloud: imap.mail.me.com, port 993,
 * with an app-specific password). Tracks the last seen UID so each message is processed once; the mailbox is
 * opened read-only and nothing is deleted.
 */
@Service
public class MailSyncService {
    private static final Logger log = LoggerFactory.getLogger(MailSyncService.class);

    private final AppProperties.Mail cfg;
    private final SettingsService settings;
    private final EmailProcessor processor;
    private final ReentrantLock lock = new ReentrantLock();

    public MailSyncService(AppProperties props, SettingsService settings, EmailProcessor processor) {
        this.cfg = props.mail();
        this.settings = settings;
        this.processor = processor;
    }

    public record SyncResult(int fetched, int processed, String error) {}

    public boolean configured() {
        return cfg != null && cfg.configured();
    }

    @Scheduled(fixedDelayString = "${app.mail.poll-interval:PT2M}", initialDelayString = "PT20S")
    public void scheduledSync() {
        if (configured()) sync();
    }

    /** Fetch messages that arrived since the last sync. The first ever sync just bookmarks the mailbox. */
    public SyncResult sync() {
        return withFolder(folder -> {
            long validity = folder.getUIDValidity();
            long next = folder.getUIDNext();
            String storedValidity = settings.get(SettingsService.IMAP_UID_VALIDITY).orElse(null);
            String storedLast = settings.get(SettingsService.IMAP_LAST_UID).orElse(null);
            if (storedLast == null || !String.valueOf(validity).equals(storedValidity)) {
                settings.set(SettingsService.IMAP_UID_VALIDITY, String.valueOf(validity));
                settings.set(SettingsService.IMAP_LAST_UID, String.valueOf(next - 1));
                log.info("Mailbox bookmarked at UID {}; only new mail will be processed from now on", next - 1);
                return new SyncResult(0, 0, null);
            }
            long last = Long.parseLong(storedLast);
            if (next - 1 <= last) return new SyncResult(0, 0, null);
            Message[] msgs = folder.getMessagesByUID(last + 1, UIDFolder.MAXUID);
            int processed = 0, fetched = 0;
            for (Message m : msgs) {
                long uid = folder.getUID(m);
                if (uid <= last) continue; // IMAP returns the newest message for an empty range
                fetched++;
                if (handle(m, validity, uid)) processed++;
                settings.set(SettingsService.IMAP_LAST_UID, String.valueOf(uid));
            }
            return new SyncResult(fetched, processed, null);
        });
    }

    /** One-off import of older mail (optional). Duplicates are skipped by Message-ID. */
    public SyncResult importSince(LocalDate since) {
        return withFolder(folder -> {
            Date d = Date.from(since.atStartOfDay(com.hisaab.service.Money.ZONE).toInstant());
            Message[] msgs = ((Folder) folder).search(new ReceivedDateTerm(ComparisonTerm.GE, d));
            long validity = folder.getUIDValidity();
            int processed = 0;
            for (Message m : msgs) {
                if (handle(m, validity, folder.getUID(m))) processed++;
            }
            return new SyncResult(msgs.length, processed, null);
        });
    }

    /** Process an .eml file uploaded by hand (testing, or importing an old alert). */
    public boolean ingestEml(InputStream eml) throws Exception {
        Message m = new jakarta.mail.internet.MimeMessage(Session.getInstance(new Properties()), eml);
        return processor.process(toIncoming(m, 0, 0));
    }

    private interface FolderWork {
        SyncResult run(UIDFolder folder) throws Exception;
    }

    private SyncResult withFolder(FolderWork work) {
        if (!configured()) return new SyncResult(0, 0, "Mailbox is not configured (set MAIL_HOST, MAIL_USERNAME, MAIL_PASSWORD)");
        if (!lock.tryLock()) return new SyncResult(0, 0, "A sync is already running");
        Store store = null;
        try {
            Properties p = new Properties();
            p.put("mail.store.protocol", "imaps");
            p.put("mail.imaps.host", cfg.host());
            p.put("mail.imaps.port", String.valueOf(cfg.port()));
            p.put("mail.imaps.ssl.enable", "true");
            p.put("mail.imaps.connectiontimeout", "20000");
            p.put("mail.imaps.timeout", "60000");
            store = Session.getInstance(p).getStore("imaps");
            store.connect(cfg.host(), cfg.port(), cfg.username(), cfg.password());
            Folder folder = store.getFolder(cfg.folder() == null ? "INBOX" : cfg.folder());
            folder.open(Folder.READ_ONLY);
            try {
                SyncResult r = work.run((UIDFolder) folder);
                settings.set(SettingsService.MAIL_LAST_SYNC, Instant.now().toString());
                settings.set(SettingsService.MAIL_LAST_ERROR, "");
                if (r.fetched() > 0) log.info("Mail sync: {} fetched, {} new", r.fetched(), r.processed());
                return r;
            } finally {
                folder.close(false);
            }
        } catch (Exception e) {
            log.warn("Mail sync failed: {}", e.toString());
            settings.set(SettingsService.MAIL_LAST_ERROR, Instant.now() + " " + e.getMessage());
            return new SyncResult(0, 0, e.getMessage());
        } finally {
            if (store != null) {
                try {
                    store.close();
                } catch (MessagingException ignored) {
                    // closing anyway
                }
            }
            lock.unlock();
        }
    }

    private boolean handle(Message m, long validity, long uid) {
        IncomingEmail in;
        try {
            in = toIncoming(m, validity, uid);
        } catch (Exception e) {
            log.warn("Could not read message uid={}: {}", uid, e.toString());
            return false;
        }
        try {
            return processor.process(in);
        } catch (Exception e) {
            log.warn("Failed to process message uid={}: {}", uid, e.toString());
            try {
                processor.recordFailure(in, e);
            } catch (Exception ignored) {
                // best effort
            }
            return false;
        }
    }

    // ---- MIME handling -----------------------------------------------------------------------------------

    public static IncomingEmail toIncoming(Message m, long validity, long uid) throws Exception {
        String[] ids = m.getHeader("Message-ID");
        String messageId = ids != null && ids.length > 0 ? ids[0].trim() : null;
        Collector c = new Collector();
        walk(m, c);
        String text = c.html.length() > 0 ? HtmlText.toText(c.html.toString())
                : HtmlText.normaliseLines(c.plain.toString());
        if (messageId == null) messageId = "sha256:" + sha256(m.getSubject() + "|" + m.getSentDate() + "|" + text);
        String from = m.getFrom() != null && m.getFrom().length > 0
                ? ((m.getFrom()[0] instanceof InternetAddress ia) ? ia.getAddress() : m.getFrom()[0].toString())
                : null;
        Date when = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
        return new IncomingEmail(messageId, from, m.getSubject(), when == null ? Instant.now() : when.toInstant(),
                text, c.attachments);
    }

    private static final class Collector {
        final StringBuilder plain = new StringBuilder();
        final StringBuilder html = new StringBuilder();
        final List<IncomingEmail.Attachment> attachments = new ArrayList<>();
    }

    private static void walk(Part part, Collector c) throws Exception {
        String disposition = part.getDisposition();
        String fileName = part.getFileName() == null ? null : MimeUtility.decodeText(part.getFileName());
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) walk(mp.getBodyPart(i), c);
        } else if (part.isMimeType("message/rfc822")) {
            walk((Part) part.getContent(), c); // forwarded-as-attachment
        } else if (Part.ATTACHMENT.equalsIgnoreCase(disposition) || fileName != null) {
            try (InputStream in = part.getInputStream()) {
                c.attachments.add(new IncomingEmail.Attachment(fileName, part.getContentType(), in.readAllBytes()));
            }
        } else if (part.isMimeType("text/html")) {
            c.html.append(content(part)).append('\n');
        } else if (part.isMimeType("text/plain")) {
            c.plain.append(content(part)).append('\n');
        }
    }

    private static String content(Part p) throws Exception {
        Object o = p.getContent();
        if (o instanceof String s) return s;
        if (o instanceof InputStream in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
        return "";
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

}
