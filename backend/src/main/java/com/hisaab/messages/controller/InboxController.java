package com.hisaab.messages.controller;

import com.hisaab.common.dto.Dtos.EmailDto;
import com.hisaab.common.model.Enums.EmailStatus;
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

import java.time.LocalDate;
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
    public List<EmailDto> emails(@RequestParam(required = false) EmailStatus status,
                                 @RequestParam(defaultValue = "0") int page) {
        PageRequest pr = PageRequest.of(page, 50);
        Page<RawEmail> p = status == null ? emails.findAllByOrderByReceivedAtDesc(pr)
                : emails.findByStatusOrderByReceivedAtDesc(status, pr);
        return p.getContent().stream().map(e -> EmailDto.of(e, false)).toList();
    }

    @GetMapping("/emails/{id}")
    public EmailDto email(@PathVariable Long id) {
        return EmailDto.of(message(id), true);
    }

    @PostMapping("/emails/{id}/reprocess")
    public EmailDto reprocess(@PathVariable Long id) {
        return EmailDto.of(processor.reprocess(message(id)), false);
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
    public EmailDto ignore(@PathVariable Long id) {
        RawEmail e = message(id);
        e.setStatus(EmailStatus.IGNORED);
        return EmailDto.of(e, false);
    }

    // ---- what I tell the app about a message ------------------------------------------------------------

    /** What the parser currently reads from a stored message, to pre-fill the correction form. */
    @GetMapping("/emails/{id}/preview")
    public MessageTeaching.Preview preview(@PathVariable Long id) {
        return teaching.preview(message(id));
    }

    public record TeachResponse(EmailDto message, Long txnId, boolean learnedFormat, boolean learnedAccount, int alsoFixed, int conflicts,
                                boolean pausedFormat, boolean mergedFormat, String category, boolean categoryByWording) {}

    /** Correct a message; the parser learns its format and account from the correction. */
    @PostMapping("/emails/{id}/teach")
    public TeachResponse teach(@PathVariable Long id, @RequestBody MessageTeaching.Teaching in) {
        MessageTeaching.TeachResult r = teaching.teach(message(id), in);
        return new TeachResponse(EmailDto.of(r.message(), false), r.txn() == null ? null : r.txn().getId(),
                r.learnedFormat(), r.learnedAccount(), r.alsoFixed(), r.conflicts(), r.pausedFormat(), r.mergedFormat(), r.category(), r.categoryByWording());
    }

    public record SkipResponse(EmailDto message, boolean learned, int conflicts, int alsoSkipped) {}

    /** "Not a transaction": ignore it and skip messages worded like it from now on. */
    @PostMapping("/emails/{id}/not-a-transaction")
    public SkipResponse notATransaction(@PathVariable Long id) {
        MessageTeaching.SkipResult r = teaching.notATransaction(message(id));
        return new SkipResponse(EmailDto.of(r.message(), false), r.learned(), r.conflicts(), r.alsoSkipped());
    }

    public record TrustResponse(String domain, int recorded) {}

    /** Alerts from this email's domain are genuine: remember that and record the held ones. */
    @PostMapping("/emails/{id}/trust-sender")
    public TrustResponse trustSender(@PathVariable Long id) {
        String domain = SenderTrust.domainOf(message(id).getFromAddress());
        if (domain == null) throw new IllegalArgumentException("This email has no sender address");
        return new TrustResponse(domain, processor.trustSender(domain));
    }

    // ---- the mailbox ------------------------------------------------------------------------------------

    @PostMapping("/mail/sync")
    public MailSyncService.SyncResult sync() {
        return mail.sync();
    }

    @PostMapping("/emails/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws Exception {
        boolean added = mail.ingestEml(file.getInputStream());
        log.info("Email uploaded by hand: {}", added ? "recorded" : "not recorded (duplicate, not a transaction, or needs review)");
        return Map.of("added", added);
    }

    public record ImportRequest(LocalDate since) {}

    /** Starts importing older mail in the background; poll GET /mail/import for progress. */
    @PostMapping("/mail/import")
    public MailSyncService.ImportStatus startImport(@RequestBody ImportRequest req) {
        return mail.startImport(req.since());
    }

    @GetMapping("/mail/import")
    public MailSyncService.ImportStatus importStatus() {
        return mail.importStatus();
    }

    @PostMapping("/mail/import/cancel")
    public MailSyncService.ImportStatus cancelImport() {
        return mail.cancelImport();
    }
}
