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
import com.hisaab.messages.model.RawEmail;
import com.hisaab.messages.repository.RawEmailRepository;
import com.hisaab.messages.service.EmailProcessor;
import com.hisaab.messages.service.MailSyncService;
import com.hisaab.messages.service.MessageTeaching;
import com.hisaab.messages.service.SenderTrust;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.hisaab.common.exception.BadRequestException;
import java.util.List;
import java.util.Map;

import static com.hisaab.common.exception.NotFound.notFound;

/**
 * The inbox (Settings → Messages): bank emails and SMS as they arrived, what I tell the app about one (teach it, it's
 * not a transaction, trust its sender), and the mailbox (sync now, upload an email, import older mail).
 */
@RestController
@RequestMapping("/api")
public class InboxController {
    private static final Logger log = LoggerFactory.getLogger(InboxController.class);

    private final RawEmailRepository emails;
    private final EmailProcessor processor;
    private final MessageTeaching teaching;
    private final MailSyncService mail;

    public InboxController(RawEmailRepository emails, EmailProcessor processor, MessageTeaching teaching, MailSyncService mail) {
        this.emails = emails;
        this.processor = processor;
        this.teaching = teaching;
        this.mail = mail;
    }

    private RawEmail message(Long id) {
        return emails.findById(id).orElseThrow(notFound("Message"));
    }

    // ---- messages ---------------------------------------------------------------------------------------

    @GetMapping("/emails")
    public List<InboxMessageResponse> emails(@RequestParam(required = false) EmailStatus status,
                                 @RequestParam(defaultValue = "0") int page) {
        PageRequest pr = PageRequest.of(page, 50);
        Page<RawEmail> p = status == null ? emails.findAllByOrderByReceivedAtDesc(pr)
                : emails.findByStatusOrderByReceivedAtDesc(status, pr);
        return p.getContent().stream().map(e -> InboxMessageResponse.of(e, false)).toList();
    }

    @GetMapping("/emails/{id}")
    public InboxMessageResponse email(@PathVariable Long id) {
        return InboxMessageResponse.of(message(id), true);
    }

    @PostMapping("/emails/{id}/reprocess")
    public InboxMessageResponse reprocess(@PathVariable Long id) {
        return InboxMessageResponse.of(processor.reprocess(message(id)), false);
    }

    /** Retry every message waiting for review (handy right after adding an account). */
    @PostMapping("/emails/reprocess-pending")
    public Map<String, Integer> reprocessPending() {
        int parsed = 0;
        for (EmailStatus st : List.of(EmailStatus.NEEDS_REVIEW, EmailStatus.ERROR)) {
            for (RawEmail e : emails.findByStatus(st)) {
                if (processor.reprocess(e).getStatus() == EmailStatus.PARSED) parsed++;
            }
        }
        if (parsed > 0) log.info("Reprocessed waiting messages: {} now read", parsed);
        return Map.of("parsed", parsed);
    }

    @PostMapping("/emails/{id}/ignore")
    @Transactional
    public InboxMessageResponse ignore(@PathVariable Long id) {
        RawEmail e = message(id);
        e.setStatus(EmailStatus.IGNORED);
        return InboxMessageResponse.of(e, false);
    }

    // ---- what I tell the app about a message ------------------------------------------------------------

    /** What the parser currently reads from a stored message, to pre-fill the correction form. */
    @GetMapping("/emails/{id}/preview")
    public TeachPreviewResponse preview(@PathVariable Long id) {
        return teaching.preview(message(id));
    }

    /** Correct a message; the parser learns its format and account from the correction. */
    @PostMapping("/emails/{id}/teach")
    public TeachResponse teach(@PathVariable Long id, @RequestBody TeachRequest in) {
        MessageTeaching.TeachResult r = teaching.teach(message(id), in);
        return new TeachResponse(InboxMessageResponse.of(r.message(), false), r.txn() == null ? null : r.txn().getId(),
                r.learnedFormat(), r.learnedAccount(), r.alsoFixed(), r.conflicts(), r.pausedFormat(), r.mergedFormat(), r.category(), r.categoryByWording());
    }

    /** "Not a transaction": ignore it and skip messages worded like it from now on. */
    @PostMapping("/emails/{id}/not-a-transaction")
    public SkipResponse notATransaction(@PathVariable Long id) {
        MessageTeaching.SkipResult r = teaching.notATransaction(message(id));
        return new SkipResponse(InboxMessageResponse.of(r.message(), false), r.learned(), r.conflicts(), r.alsoSkipped());
    }

    /** Alerts from this email's domain are genuine: remember that and record the held ones. */
    @PostMapping("/emails/{id}/trust-sender")
    public TrustResponse trustSender(@PathVariable Long id) {
        String domain = SenderTrust.domainOf(message(id).getFromAddress());
        if (domain == null) throw new BadRequestException("This email has no sender address");
        return new TrustResponse(domain, processor.trustSender(domain));
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
