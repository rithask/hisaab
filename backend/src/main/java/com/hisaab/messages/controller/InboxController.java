package com.hisaab.messages.controller;

import com.hisaab.messages.dto.InboxMessageResponse;
import com.hisaab.messages.dto.MailImportRequest;
import com.hisaab.messages.dto.MailImportStatusResponse;
import com.hisaab.messages.dto.MailSyncResponse;
import com.hisaab.messages.dto.SkipResponse;
import com.hisaab.messages.dto.TeachPreviewResponse;
import com.hisaab.messages.dto.TeachRequest;
import com.hisaab.messages.dto.TeachResponse;
import com.hisaab.messages.dto.TrustResponse;
import com.hisaab.messages.model.EmailStatus;
import com.hisaab.messages.service.InboxService;
import com.hisaab.messages.service.MailSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * The inbox (Settings → Messages): bank emails and SMS as they arrived, what I tell the app about one (teach it, it's
 * not a transaction, trust its sender), and the mailbox (sync now, upload an email, import older mail).
 */
@RestController
@RequestMapping("/api")
public class InboxController {
    private static final Logger log = LoggerFactory.getLogger(InboxController.class);

    private final InboxService inbox;
    private final MailSyncService mail;

    public InboxController(InboxService inbox, MailSyncService mail) {
        this.inbox = inbox;
        this.mail = mail;
    }

    // ---- messages ---------------------------------------------------------------------------------------

    @GetMapping("/emails")
    public List<InboxMessageResponse> emails(@RequestParam(required = false) EmailStatus status,
                                             @RequestParam(defaultValue = "0") int page) {
        return inbox.list(status, page);
    }

    @GetMapping("/emails/{id}")
    public InboxMessageResponse email(@PathVariable Long id) {
        return inbox.get(id);
    }

    @PostMapping("/emails/{id}/reprocess")
    public InboxMessageResponse reprocess(@PathVariable Long id) {
        return inbox.reprocess(id);
    }

    /** Retry every message waiting for review (handy right after adding an account). */
    @PostMapping("/emails/reprocess-pending")
    public Map<String, Integer> reprocessPending() {
        return Map.of("parsed", inbox.reprocessPending());
    }

    @PostMapping("/emails/{id}/ignore")
    public InboxMessageResponse ignore(@PathVariable Long id) {
        return inbox.ignore(id);
    }

    // ---- what I tell the app about a message ------------------------------------------------------------

    /** What the parser currently reads from a stored message, to pre-fill the correction form. */
    @GetMapping("/emails/{id}/preview")
    public TeachPreviewResponse preview(@PathVariable Long id) {
        return inbox.preview(id);
    }

    /** Correct a message; the parser learns its format and account from the correction. */
    @PostMapping("/emails/{id}/teach")
    public TeachResponse teach(@PathVariable Long id, @RequestBody TeachRequest in) {
        return inbox.teach(id, in);
    }

    /** "Not a transaction": ignore it and skip messages worded like it from now on. */
    @PostMapping("/emails/{id}/not-a-transaction")
    public SkipResponse notATransaction(@PathVariable Long id) {
        return inbox.notATransaction(id);
    }

    /** Alerts from this email's domain are genuine: remember that and record the held ones. */
    @PostMapping("/emails/{id}/trust-sender")
    public TrustResponse trustSender(@PathVariable Long id) {
        return inbox.trustSender(id);
    }

    // ---- the mailbox ------------------------------------------------------------------------------------

    @PostMapping("/mail/sync")
    public MailSyncResponse sync() {
        return mail.sync();
    }

    @PostMapping("/emails/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws Exception {
        boolean added = mail.ingestEml(file.getInputStream());
        log.info("Email uploaded by hand: {}", added ? "recorded" : "not recorded (duplicate, not a transaction, or needs review)");
        return Map.of("added", added);
    }

    /** Starts importing older mail in the background; poll GET /mail/import for progress. */
    @PostMapping("/mail/import")
    public MailImportStatusResponse startImport(@RequestBody MailImportRequest req) {
        return mail.startImport(req.since());
    }

    @GetMapping("/mail/import")
    public MailImportStatusResponse importStatus() {
        return mail.importStatus();
    }

    @PostMapping("/mail/import/cancel")
    public MailImportStatusResponse cancelImport() {
        return mail.cancelImport();
    }
}
