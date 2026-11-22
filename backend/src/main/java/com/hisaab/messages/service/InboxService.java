package com.hisaab.messages.service;

import com.hisaab.common.exception.BadRequestException;
import com.hisaab.messages.dto.InboxMessageResponse;
import com.hisaab.messages.dto.SkipResponse;
import com.hisaab.messages.dto.TeachPreviewResponse;
import com.hisaab.messages.dto.TeachRequest;
import com.hisaab.messages.dto.TeachResponse;
import com.hisaab.messages.dto.TrustResponse;
import com.hisaab.messages.model.EmailStatus;
import com.hisaab.messages.model.RawEmail;
import com.hisaab.messages.repository.RawEmailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.hisaab.common.exception.NotFound.notFound;

/**
 * The inbox (Settings → Messages): bank emails and SMS as they arrived, and what I tell the app about one: read it
 * again, teach it, it's not a transaction, trust its sender.
 */
@Service
public class InboxService {
    private static final Logger log = LoggerFactory.getLogger(InboxService.class);
    private static final int PAGE_SIZE = 50;

    private final RawEmailRepository messages;
    private final EmailProcessor processor;
    private final MessageTeaching teaching;

    public InboxService(RawEmailRepository messages, EmailProcessor processor, MessageTeaching teaching) {
        this.messages = messages;
        this.processor = processor;
        this.teaching = teaching;
    }

    private RawEmail message(Long id) {
        return messages.findById(id).orElseThrow(notFound("Message"));
    }

    /** Newest first, a page at a time; all of them, or only those with this status. */
    public List<InboxMessageResponse> list(EmailStatus status, int page) {
        PageRequest pageRequest = PageRequest.of(page, PAGE_SIZE);
        Page<RawEmail> found = status == null ? messages.findAllByOrderByReceivedAtDesc(pageRequest)
                : messages.findByStatusOrderByReceivedAtDesc(status, pageRequest);
        return found.getContent().stream().map(m -> InboxMessageResponse.of(m, false)).toList();
    }

    /** One message, with its full text. */
    public InboxMessageResponse get(Long id) {
        return InboxMessageResponse.of(message(id), true);
    }

    public InboxMessageResponse reprocess(Long id) {
        return InboxMessageResponse.of(processor.reprocess(message(id)), false);
    }

    /** Reads every message waiting for review again (handy right after adding an account); returns how many were read. */
    public int reprocessPending() {
        int parsed = 0;
        for (EmailStatus status : List.of(EmailStatus.NEEDS_REVIEW, EmailStatus.ERROR)) {
            for (RawEmail m : messages.findByStatus(status)) {
                if (processor.reprocess(m).getStatus() == EmailStatus.PARSED) parsed++;
            }
        }
        if (parsed > 0) log.info("Reprocessed waiting messages: {} now read", parsed);
        return parsed;
    }

    @Transactional
    public InboxMessageResponse ignore(Long id) {
        RawEmail m = message(id);
        m.setStatus(EmailStatus.IGNORED);
        return InboxMessageResponse.of(m, false);
    }

    /** What the parser currently reads from a stored message, to pre-fill the correction form. */
    public TeachPreviewResponse preview(Long id) {
        return teaching.preview(message(id));
    }

    /** Corrects a message; the parser learns its format and account from the correction. */
    public TeachResponse teach(Long id, TeachRequest in) {
        MessageTeaching.TeachResult taught = teaching.teach(message(id), in);
        return new TeachResponse(InboxMessageResponse.of(taught.message(), false), taught.txn() == null ? null : taught.txn().getId(),
                taught.learnedFormat(), taught.learnedAccount(), taught.alsoFixed(), taught.conflicts(), taught.pausedFormat(),
                taught.mergedFormat(), taught.category(), taught.categoryByWording());
    }

    /** "Not a transaction": ignores it and skips messages worded like it from now on. */
    public SkipResponse notATransaction(Long id) {
        MessageTeaching.SkipResult skipped = teaching.notATransaction(message(id));
        return new SkipResponse(InboxMessageResponse.of(skipped.message(), false), skipped.learned(), skipped.conflicts(),
                skipped.alsoSkipped());
    }

    /** Alerts from this email's domain are genuine: remembers that and records the held ones. */
    public TrustResponse trustSender(Long id) {
        String domain = SenderTrust.domainOf(message(id).getFromAddress());
        if (domain == null) throw new BadRequestException("This email has no sender address");
        return new TrustResponse(domain, processor.trustSender(domain));
    }
}
