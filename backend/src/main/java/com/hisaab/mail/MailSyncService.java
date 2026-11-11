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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** Older-mail imports run one at a time on their own thread; their progress is kept here for the app to show. */
    private final ExecutorService importer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mail-import");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean cancelImport = new AtomicBoolean();
    private volatile ImportStatus importStatus = ImportStatus.IDLE;

    public MailSyncService(AppProperties props, SettingsService settings, EmailProcessor processor) {
        this.cfg = props.mail();
        this.settings = settings;
        this.processor = processor;
    }

    public record SyncResult(int fetched, int processed, String error) {}

    /**
     * Where an older-mail import has got to.
     *
     * @param state IDLE, CONNECTING (signing in and searching), RUNNING, DONE, CANCELLED or FAILED
     * @param total emails found since the date (known once the search finishes)
     * @param done  emails looked at so far
     * @param added of those, how many were new
     */
    public record ImportStatus(String state, LocalDate since, Integer total, int done, int added, Instant startedAt,
                               Instant finishedAt, String error) {
        static final ImportStatus IDLE = new ImportStatus("IDLE", null, null, 0, 0, null, null, null);

        public boolean active() {
            return state.equals("CONNECTING") || state.equals("RUNNING");
        }

        ImportStatus progress(int done, int added) {
            return new ImportStatus(state, since, total, done, added, startedAt, null, null);
        }

        ImportStatus finish(String state, String error) {
            return new ImportStatus(state, since, total, done, added, startedAt, Instant.now(), error);
        }
    }

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
            long[] uids = uidsOf(folder, folder.getMessagesByUID(last + 1, UIDFolder.MAXUID));
            int[] counts = new int[2];                                          // fetched, processed
            fetchInBatches(folder, uids, validity, (uid, added) -> {
                if (uid <= last) return true;                                   // IMAP returns the newest message for an empty range
                counts[0]++;
                if (added) counts[1]++;
                settings.set(SettingsService.IMAP_LAST_UID, String.valueOf(uid));
                return true;
            });
            return new SyncResult(counts[0], counts[1], null);
        });
    }

    public ImportStatus importStatus() {
        return importStatus;
    }

    /**
     * Starts a one-off import of older mail in the background and returns straight away; follow it with
     * {@link #importStatus()}. Emails already seen are skipped (by Message-ID). Only one import runs at a time.
     */
    public synchronized ImportStatus startImport(LocalDate since) {
        if (!configured()) throw new IllegalStateException("The mailbox isn't set up (MAIL_HOST, MAIL_USERNAME, MAIL_PASSWORD)");
        if (since == null) throw new IllegalArgumentException("Pick a start date");
        if (since.isAfter(LocalDate.now(com.hisaab.service.Money.ZONE))) throw new IllegalArgumentException("Pick a date that isn't in the future");
        if (importStatus.active()) throw new IllegalStateException("An import is already running");
        cancelImport.set(false);
        importStatus = new ImportStatus("CONNECTING", since, null, 0, 0, Instant.now(), null, null);
        importer.submit(() -> runImport(since));
        return importStatus;
    }

    /** Stops a running import after the email it's on. What was imported so far stays. */
    public ImportStatus cancelImport() {
        if (importStatus.active()) cancelImport.set(true);
        return importStatus;
    }

    private void runImport(LocalDate since) {
        SyncResult r = withFolder(folder -> {
            Date d = Date.from(since.atStartOfDay(com.hisaab.service.Money.ZONE).toInstant());
            long[] uids = uidsOf(folder, ((Folder) folder).search(new ReceivedDateTerm(ComparisonTerm.GE, d)));
            long validity = folder.getUIDValidity();
            ImportStatus s = importStatus;
            importStatus = new ImportStatus("RUNNING", since, uids.length, 0, 0, s.startedAt(), null, null);
            int[] counts = new int[2];                                          // done, added
            fetchInBatches(folder, uids, validity, (uid, added) -> {
                counts[0]++;
                if (added) counts[1]++;
                importStatus = importStatus.progress(counts[0], counts[1]);
                return !cancelImport.get();
            });
            return new SyncResult(uids.length, counts[1], null);
        }, true);
        ImportStatus s = importStatus;
        if (r.error() != null) importStatus = s.finish("FAILED", r.error());
        else importStatus = s.finish(cancelImport.get() && s.done() < (s.total() == null ? 0 : s.total()) ? "CANCELLED" : "DONE", null);
        log.info("Mail import since {}: {} of {} looked at, {} new ({})", since, s.done(), s.total(), s.added(), importStatus.state());
    }

    @jakarta.annotation.PreDestroy
    void stopImporter() {
        cancelImport.set(true);
        importer.shutdownNow();
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
        return withFolder(work, false);
    }

    /** @param wait wait for a sync that's already running (imports), rather than giving up (the scheduled sync) */
    private SyncResult withFolder(FolderWork work, boolean wait) {
        if (!configured()) return new SyncResult(0, 0, "Mailbox is not configured (set MAIL_HOST, MAIL_USERNAME, MAIL_PASSWORD)");
        if (wait) lock.lock();
        else if (!lock.tryLock()) return new SyncResult(0, 0, "A sync is already running");
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

    /** Per message: called with its UID and whether it was new; return false to stop. */
    private interface EachMessage {
        boolean accept(long uid, boolean added);
    }

    /** Emails fetched per IMAP request. Bounded so a big import never holds more than one batch in memory. */
    static final int BATCH = 50;
    private static final Session PARSE = Session.getInstance(new Properties());

    /** The UIDs of these messages, in mailbox order, fetched in one request. */
    private static long[] uidsOf(UIDFolder folder, Message[] msgs) throws MessagingException {
        FetchProfile fp = new FetchProfile();
        fp.add(UIDFolder.FetchProfileItem.UID);
        ((Folder) folder).fetch(msgs, fp);
        long[] uids = new long[msgs.length];
        for (int i = 0; i < msgs.length; i++) uids[i] = folder.getUID(msgs[i]);
        return uids;
    }

    /**
     * Fetches whole messages BATCH at a time with one standard request each
     * ({@code UID FETCH <uids> (UID INTERNALDATE BODY.PEEK[])}; PEEK leaves them unread) and processes them in order.
     * Reading a message part by part instead costs several round trips per email, which makes big imports crawl.
     */
    private void fetchInBatches(UIDFolder folder, long[] uids, long validity, EachMessage each) throws MessagingException {
        org.eclipse.angus.mail.imap.IMAPFolder imap = (org.eclipse.angus.mail.imap.IMAPFolder) folder;
        for (int i = 0; i < uids.length; i += BATCH) {
            long[] part = Arrays.copyOfRange(uids, i, Math.min(uids.length, i + BATCH));
            StringBuilder set = new StringBuilder();
            for (long u : part) set.append(set.isEmpty() ? "" : ",").append(u);
            Map<Long, org.eclipse.angus.mail.imap.protocol.FetchResponse> got = new HashMap<>();
            imap.doCommand(p -> {
                org.eclipse.angus.mail.iap.Response[] r = p.command("UID FETCH " + set + " (UID INTERNALDATE BODY.PEEK[])", null);
                for (org.eclipse.angus.mail.iap.Response x : r) {
                    if (x instanceof org.eclipse.angus.mail.imap.protocol.FetchResponse fr) {
                        var u = fr.getItem(org.eclipse.angus.mail.imap.protocol.UID.class);
                        if (u != null) got.put(u.uid, fr);
                    }
                }
                p.notifyResponseHandlers(r);
                p.handleResult(r[r.length - 1]);
                return null;
            });
            for (long uid : part) {
                var fr = got.get(uid);
                boolean added = fr != null && handle(fr, validity, uid);
                if (!each.accept(uid, added)) return;
            }
        }
    }

    private boolean handle(org.eclipse.angus.mail.imap.protocol.FetchResponse fr, long validity, long uid) {
        IncomingEmail in;
        try {
            var body = fr.getItem(org.eclipse.angus.mail.imap.protocol.BODY.class);
            var date = fr.getItem(org.eclipse.angus.mail.imap.protocol.INTERNALDATE.class);
            if (body == null) return false;
            Message m = new jakarta.mail.internet.MimeMessage(PARSE, body.getByteArrayInputStream());
            in = toIncoming(m, validity, uid, date == null ? null : date.getDate());
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
        return toIncoming(m, validity, uid, null);
    }

    /** @param received when the mailbox received it (IMAP INTERNALDATE), if known; else the message's own dates */
    static IncomingEmail toIncoming(Message m, long validity, long uid, Date received) throws Exception {
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
        Date when = received != null ? received : m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
        String[] auth = m.getHeader("Authentication-Results");
        return new IncomingEmail(messageId, from, m.getSubject(), when == null ? Instant.now() : when.toInstant(),
                text, c.attachments, SenderTrust.check(auth == null || auth.length == 0 ? null : auth[0], from));
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
