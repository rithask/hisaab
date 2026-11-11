package com.hisaab.web;

import com.hisaab.domain.Alert;
import com.hisaab.domain.Enums.EmailStatus;
import com.hisaab.domain.RawEmail;
import com.hisaab.domain.StatementFile;
import com.hisaab.mail.EmailProcessor;
import com.hisaab.mail.MailSyncService;
import com.hisaab.mail.StatementStorage;
import com.hisaab.repo.AccountRepo;
import com.hisaab.repo.AlertRepo;
import com.hisaab.repo.RawEmailRepo;
import com.hisaab.repo.StatementFileRepo;
import com.hisaab.service.BalanceService;
import com.hisaab.service.SettingsService;
import com.hisaab.web.Dtos.AlertDto;
import com.hisaab.web.Dtos.EmailDto;
import com.hisaab.web.Dtos.StatementDto;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Emails, alerts, statements and mailbox sync. */
@RestController
@RequestMapping("/api")
public class InboxController {
    private final RawEmailRepo emails;
    private final EmailProcessor processor;
    private final MailSyncService mail;
    private final AlertRepo alerts;
    private final BalanceService balances;
    private final StatementFileRepo statements;
    private final StatementStorage storage;
    private final AccountRepo accounts;
    private final SettingsService settings;
    private final com.hisaab.mail.SmsLearningService learning;
    private final com.hisaab.service.PayeeAliasService aliases;
    private final com.hisaab.features.FeatureService features;

    public InboxController(RawEmailRepo emails, EmailProcessor processor, MailSyncService mail, AlertRepo alerts,
                           BalanceService balances, StatementFileRepo statements, StatementStorage storage,
                           AccountRepo accounts, SettingsService settings,
                           com.hisaab.mail.SmsLearningService learning,
                           com.hisaab.service.PayeeAliasService aliases,
                           com.hisaab.features.FeatureService features) {
        this.features = features;
        this.learning = learning;
        this.aliases = aliases;
        this.emails = emails;
        this.processor = processor;
        this.mail = mail;
        this.alerts = alerts;
        this.balances = balances;
        this.statements = statements;
        this.storage = storage;
        this.accounts = accounts;
        this.settings = settings;
    }

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
        return EmailDto.of(emails.findById(id).orElseThrow(() -> new NoSuchElementException("Email not found")), true);
    }

    @PostMapping("/emails/{id}/reprocess")
    public EmailDto reprocess(@PathVariable Long id) {
        RawEmail e = emails.findById(id).orElseThrow(() -> new NoSuchElementException("Email not found"));
        return EmailDto.of(processor.reprocess(e), false);
    }

    public record TrustResponse(String domain, int recorded) {}

    /** Alerts from this email's domain are genuine: remember that and record the held ones. */
    @PostMapping("/emails/{id}/trust-sender")
    public TrustResponse trustSender(@PathVariable Long id) {
        RawEmail e = emails.findById(id).orElseThrow(() -> new NoSuchElementException("Email not found"));
        String domain = com.hisaab.mail.SenderTrust.domainOf(e.getFromAddress());
        if (domain == null) throw new IllegalArgumentException("This email has no sender address");
        return new TrustResponse(domain, processor.trustSender(domain));
    }

    public record SkipResponse(EmailDto message, boolean learned, int conflicts, int alsoSkipped) {}

    /** "Not a transaction": ignore it and skip messages worded like it from now on. */
    @PostMapping("/emails/{id}/not-a-transaction")
    public SkipResponse notATransaction(@PathVariable Long id) {
        RawEmail e = emails.findById(id).orElseThrow(() -> new NoSuchElementException("Message not found"));
        EmailProcessor.SkipResult r = processor.notATransaction(e);
        return new SkipResponse(EmailDto.of(r.message(), false), r.learned(), r.conflicts(), r.alsoSkipped());
    }

    @PostMapping("/emails/{id}/ignore")
    @Transactional
    public EmailDto ignore(@PathVariable Long id) {
        RawEmail e = emails.findById(id).orElseThrow(() -> new NoSuchElementException("Email not found"));
        e.setStatus(EmailStatus.IGNORED);
        return EmailDto.of(e, false);
    }

    // ---- SMS learning ------------------------------------------------------------------------------------

    /** What the parser currently reads from a stored SMS, to pre-fill the correction form. */
    @GetMapping("/emails/{id}/preview")
    public EmailProcessor.Preview preview(@PathVariable Long id) {
        return processor.preview(emails.findById(id).orElseThrow(() -> new NoSuchElementException("Message not found")));
    }

    public record TeachResponse(EmailDto message, Long txnId, boolean learnedFormat, boolean learnedAccount, int alsoFixed, int conflicts,
                                boolean pausedFormat) {}

    /** Correct an SMS; the parser learns its format and account from the correction. */
    @PostMapping("/emails/{id}/teach")
    public TeachResponse teach(@PathVariable Long id, @RequestBody EmailProcessor.Teaching in) {
        RawEmail e = emails.findById(id).orElseThrow(() -> new NoSuchElementException("Message not found"));
        EmailProcessor.TeachResult r = processor.teach(e, in);
        return new TeachResponse(EmailDto.of(r.message(), false), r.txn() == null ? null : r.txn().getId(),
                r.learnedFormat(), r.learnedAccount(), r.alsoFixed(), r.conflicts(), r.pausedFormat());
    }

    public record TemplateDto(Long id, String skeleton, String sample, String institution, String direction, int hits,
                              java.time.Instant lastUsedAt, String channel, String kind, int corrections, boolean paused) {}

    public record AliasDto(Long id, String rawKey, String displayName, int hits) {}

    public record Learned(List<TemplateDto> formats, List<AliasDto> payees, long senders) {}

    @GetMapping("/sms/learned")
    @Transactional(readOnly = true)
    public Learned learned() {
        return new Learned(
                learning.templates().stream().map(t -> new TemplateDto(t.getId(), t.getSkeleton(), t.getSample(),
                        t.getInstitution() == null ? null : t.getInstitution().name(), t.getDirection() == null ? null : t.getDirection().name(),
                        t.getHits(), t.getLastUsedAt(), t.getChannel().name(), t.getKind(), t.getCorrections(), t.isPaused())).toList(),
                aliases.all().stream().map(a -> new AliasDto(a.getId(), a.getRawKey(), a.getDisplayName(), a.getHits())).toList(),
                learning.senderCount());
    }

    /** Use a paused format again (its correction count starts over). */
    @PostMapping("/sms/formats/{id}/resume")
    public void resumeFormat(@PathVariable Long id) {
        learning.resume(id);
    }

    @DeleteMapping("/sms/formats/{id}")
    public void forgetFormat(@PathVariable Long id) {
        learning.forgetTemplate(id);
    }

    @DeleteMapping("/sms/payees/{id}")
    public void forgetPayee(@PathVariable Long id) {
        aliases.forget(id);
    }

    /** Retry every email waiting for review (handy right after adding an account). */
    @PostMapping("/emails/reprocess-pending")
    public Map<String, Integer> reprocessPending() {
        int parsed = 0;
        for (EmailStatus st : List.of(EmailStatus.NEEDS_REVIEW, EmailStatus.ERROR)) {
            for (RawEmail e : emails.findByStatus(st)) {
                if (processor.reprocess(e).getStatus() == EmailStatus.PARSED) parsed++;
            }
        }
        return Map.of("parsed", parsed);
    }

    @PostMapping("/mail/sync")
    public MailSyncService.SyncResult sync() {
        return mail.sync();
    }

    @PostMapping("/emails/upload")
    public Map<String, Object> upload(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws Exception {
        boolean added = mail.ingestEml(file.getInputStream());
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

    // ---- alerts ------------------------------------------------------------------------------------------

    @GetMapping("/alerts")
    @Transactional(readOnly = true)
    public List<AlertDto> alerts() {
        return alerts.findByDismissedFalseOrderByCreatedAtDesc().stream().map(AlertDto::of).toList();
    }

    @PostMapping("/alerts/{id}/dismiss")
    @Transactional
    public void dismiss(@PathVariable Long id) {
        alerts.findById(id).orElseThrow().setDismissed(true);
    }

    /** Add a balance adjustment so the app matches what the bank reported. */
    @PostMapping("/alerts/{id}/adjust")
    @Transactional
    public Map<String, Long> adjust(@PathVariable Long id) {
        Alert a = alerts.findById(id).orElseThrow();
        return Map.of("adjustmentId", balances.adjust(a).getId());
    }

    // ---- statements --------------------------------------------------------------------------------------

    @GetMapping("/statements")
    @Transactional(readOnly = true)
    public List<StatementDto> statements() {
        return statements.findAllByOrderByReceivedAtDesc().stream().map(StatementDto::of).toList();
    }

    @GetMapping("/statements/{id}/file")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        StatementFile s = statements.findById(id).orElseThrow(() -> new NoSuchElementException("Statement not found"));
        Resource file = new FileSystemResource(storage.resolve(s.getStoredPath()));
        if (!file.exists()) throw new NoSuchElementException("File is missing from storage");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(s.getFileName()).build().toString())
                .body(file);
    }

    public record AssignRequest(Long accountId) {}

    @PatchMapping("/statements/{id}")
    @Transactional
    public StatementDto assign(@PathVariable Long id, @RequestBody AssignRequest req) {
        StatementFile s = statements.findById(id).orElseThrow();
        s.setAccount(req.accountId() == null ? null : accounts.findById(req.accountId()).orElseThrow());
        return StatementDto.of(s);
    }

    // ---- settings ----------------------------------------------------------------------------------------

    public record MailStatus(boolean configured, String lastSync, String lastError) {}

    public record SettingsDto(String ownerName, MailStatus mail, boolean showInvestmentsOnHome, java.util.Map<String, Boolean> features) {}

    @GetMapping("/settings")
    public SettingsDto getSettings() {
        return new SettingsDto(settings.ownerName(), new MailStatus(mail.configured(),
                settings.get(SettingsService.MAIL_LAST_SYNC).orElse(null),
                settings.get(SettingsService.MAIL_LAST_ERROR).filter(s -> !s.isBlank()).orElse(null)),
                !"false".equals(settings.get(SettingsService.HOME_SHOW_INVESTMENTS).orElse("true")),
                features.all());
    }

    public record SettingsInput(String ownerName, Boolean showInvestmentsOnHome, java.util.Map<String, Boolean> features) {}

    @PutMapping("/settings")
    public SettingsDto putSettings(@RequestBody SettingsInput in) {
        if (in.ownerName() != null) settings.set(SettingsService.OWNER_NAME, in.ownerName().trim());
        features.update(in.features());
        if (in.showInvestmentsOnHome() != null) settings.set(SettingsService.HOME_SHOW_INVESTMENTS, in.showInvestmentsOnHome().toString());
        return getSettings();
    }
}
