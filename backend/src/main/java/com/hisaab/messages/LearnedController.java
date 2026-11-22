package com.hisaab.messages;

import com.hisaab.categories.PayeeAliasService;
import com.hisaab.common.Audit;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * What the app has learned from my corrections (Settings → What it learned): message wordings, payee names, SMS
 * senders, each with a way to make it forget.
 */
@RestController
@RequestMapping("/api")
public class LearnedController {
    private final SmsLearningService learning;
    private final PayeeAliasService aliases;
    private final SmsSenders smsSenders;

    public LearnedController(SmsLearningService learning, PayeeAliasService aliases, SmsSenders smsSenders) {
        this.learning = learning;
        this.aliases = aliases;
        this.smsSenders = smsSenders;
    }

    public record TemplateDto(Long id, String skeleton, String sample, String institution, String direction, int hits,
                              Instant lastUsedAt, String channel, String kind, int corrections, boolean paused,
                              boolean auto) {}

    public record AliasDto(Long id, String rawKey, String displayName, int hits) {}

    /** @param senders SMS sender ids whose bank the app learned */
    public record Learned(List<TemplateDto> formats, List<AliasDto> payees, long senders) {}

    @GetMapping("/sms/learned")
    @Transactional(readOnly = true)
    public Learned learned() {
        return new Learned(
                learning.templates().stream().map(t -> new TemplateDto(t.getId(), t.getSkeleton(), t.getSample(),
                        t.getInstitution() == null ? null : t.getInstitution().name(), t.getDirection() == null ? null : t.getDirection().name(),
                        t.getHits(), t.getLastUsedAt(), t.getChannel().name(), t.getKind(), t.getCorrections(), t.isPaused(), t.isAuto())).toList(),
                aliases.all().stream().map(a -> new AliasDto(a.getId(), a.getRawKey(), a.getDisplayName(), a.getHits())).toList(),
                smsSenders.senderCount());
    }

    /** Use a paused format again (its correction count starts over). */
    @PostMapping("/sms/formats/{id}/resume")
    public void resumeFormat(@PathVariable Long id) {
        learning.resume(id);
        Audit.log("Learned format resumed id={}", id);
    }

    @DeleteMapping("/sms/formats/{id}")
    public void forgetFormat(@PathVariable Long id) {
        learning.forgetTemplate(id);
        Audit.log("Learned format forgotten id={}", id);
    }

    @DeleteMapping("/sms/payees/{id}")
    public void forgetPayee(@PathVariable Long id) {
        aliases.forget(id);
        Audit.log("Learned payee name forgotten id={}", id);
    }
}
