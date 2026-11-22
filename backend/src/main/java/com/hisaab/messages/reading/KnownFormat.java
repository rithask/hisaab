package com.hisaab.messages.reading;

import com.hisaab.common.model.Enums.EmailStatus;
import com.hisaab.messages.service.SmsReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Step 1: a format I taught comes first; where I've corrected a reading, my answer wins. A format I marked "not a
 * transaction" skips the message; one that was paused (corrected too often) sends it for review.
 */
@Component
class KnownFormat implements ReadingStep {
    private static final Logger log = LoggerFactory.getLogger(KnownFormat.class);
    private final SmsReader reader;

    KnownFormat(SmsReader reader) {
        this.reader = reader;
    }

    @Override
    public void apply(MessageReading r) {
        if (!r.sms()) {
            read(r);
            return;
        }
        try {
            read(r);
        } catch (RuntimeException e) {   // an SMS that breaks reading is kept as an error, not lost
            log.warn("SMS parser failed on message {}: {}", r.raw.getId(), e.toString());
            r.stop(EmailStatus.ERROR, "Couldn't read it: " + e.getMessage());
        }
    }

    private void read(MessageReading r) {
        String parser = r.sms() ? "sms-learned" : "email-learned";
        Optional<SmsReader.Match> learned = reader.match(r.raw.getChannel(), r.raw.getBodyText(), r.raw.getReceivedAt());
        r.raw.setTemplate(learned.map(SmsReader.Match::template).orElse(null));
        if (learned.isEmpty() && reader.pausedFor(r.raw.getChannel(), r.raw.getBodyText()).isPresent()) {
            r.raw.setParser(parser);
            r.stop(EmailStatus.NEEDS_REVIEW, MessageReader.PAUSED_NOTE);
        } else if (learned.isPresent() && learned.get().skip()) {
            r.raw.setParser(parser);
            r.stop(EmailStatus.IGNORED, MessageReader.NOT_A_TXN_NOTE);
        } else if (learned.isPresent()) {
            r.parsed = learned.get().txn();
            r.raw.setParser(parser);
        }
    }
}
