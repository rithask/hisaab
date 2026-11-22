package com.hisaab.messages.controller;

import com.hisaab.messages.dto.BankDomainsResponse;
import com.hisaab.messages.dto.LearnedResponse;
import com.hisaab.messages.dto.PayeeAliasResponse;
import com.hisaab.messages.dto.SmsTemplateResponse;
import com.hisaab.messages.dto.TrustedDomainResponse;
import com.hisaab.common.model.Institution;
import com.hisaab.categories.service.PayeeAliasService;
import com.hisaab.common.util.Audit;
import com.hisaab.messages.service.SenderTrust;
import com.hisaab.messages.service.SmsLearningService;
import com.hisaab.messages.service.SmsSenders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/**
 * What the app has learned from my corrections (Settings → What it learned): message wordings, payee names, SMS
 * senders and the email senders I trusted, each with a way to make it forget.
 */
@RestController
@RequestMapping("/api")
public class LearnedController {
    private final SmsLearningService learning;
    private final PayeeAliasService aliases;
    private final SmsSenders smsSenders;
    private final SenderTrust trust;

    public LearnedController(SmsLearningService learning, PayeeAliasService aliases, SmsSenders smsSenders, SenderTrust trust) {
        this.learning = learning;
        this.aliases = aliases;
        this.smsSenders = smsSenders;
        this.trust = trust;
    }

    @GetMapping("/sms/learned")
    @Transactional(readOnly = true)
    public LearnedResponse learned() {
        return new LearnedResponse(
                learning.templates().stream().map(t -> new SmsTemplateResponse(t.getId(), t.getSkeleton(), t.getSample(),
                        t.getInstitution() == null ? null : t.getInstitution().name(), t.getDirection() == null ? null : t.getDirection().name(),
                        t.getHits(), t.getLastUsedAt(), t.getChannel().name(), t.getKind(), t.getCorrections(), t.isPaused(), t.isAuto())).toList(),
                aliases.all().stream().map(a -> new PayeeAliasResponse(a.getId(), a.getRawKey(), a.getDisplayName(), a.getHits())).toList(),
                smsSenders.senderCount(),
                trust.trustedSenders().stream().map(t -> new TrustedDomainResponse(t.getDomain(), t.getCreatedAt())).toList(),
                bankDomains());
    }

    /** Each bank's built-in domains, in the order banks are listed everywhere else. */
    private static List<BankDomainsResponse> bankDomains() {
        return Arrays.stream(Institution.values()).filter(SenderTrust.BANK_DOMAINS::containsKey)
                .map(i -> new BankDomainsResponse(i.name(), i.label(), SenderTrust.BANK_DOMAINS.get(i))).toList();
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

    /** Stop trusting an email domain I trusted: its later alerts wait for review again. */
    @DeleteMapping("/trusted-senders/{domain}")
    public void untrustSender(@PathVariable String domain) {
        trust.untrust(domain);
        Audit.log("Trusted email sender removed domain={}", domain);
    }
}
