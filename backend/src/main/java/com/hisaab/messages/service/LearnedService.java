package com.hisaab.messages.service;

import com.hisaab.categories.service.PayeeAliasService;
import com.hisaab.common.model.Institution;
import com.hisaab.common.util.Audit;
import com.hisaab.messages.dto.BankDomainsResponse;
import com.hisaab.messages.dto.LearnedResponse;
import com.hisaab.messages.dto.PayeeAliasResponse;
import com.hisaab.messages.dto.LearnedFormatResponse;
import com.hisaab.messages.dto.TrustedDomainResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * What the app has learned from my corrections (Settings → What it learned): message wordings, payee names, SMS
 * senders and the email senders I trusted, each with a way to make it forget.
 */
@Service
public class LearnedService {
    private final FormatLearningService learning;
    private final PayeeAliasService aliases;
    private final SmsSenders smsSenders;
    private final SenderTrust trust;

    public LearnedService(FormatLearningService learning, PayeeAliasService aliases, SmsSenders smsSenders, SenderTrust trust) {
        this.learning = learning;
        this.aliases = aliases;
        this.smsSenders = smsSenders;
        this.trust = trust;
    }

    @Transactional(readOnly = true)
    public LearnedResponse learned() {
        return new LearnedResponse(
                learning.templates().stream().map(t -> new LearnedFormatResponse(t.getId(), t.getSkeleton(), t.getSample(),
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
    public void resumeFormat(Long id) {
        learning.resume(id);
        Audit.log("Learned format resumed id={}", id);
    }

    public void forgetFormat(Long id) {
        learning.forgetTemplate(id);
        Audit.log("Learned format forgotten id={}", id);
    }

    public void forgetPayee(Long id) {
        aliases.forget(id);
        Audit.log("Learned payee name forgotten id={}", id);
    }

    /** Stop trusting an email domain I trusted: its later alerts wait for review again. */
    public void untrustSender(String domain) {
        trust.untrust(domain);
        Audit.log("Trusted email sender removed domain={}", domain);
    }
}
