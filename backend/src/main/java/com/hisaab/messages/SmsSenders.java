package com.hisaab.messages;

import com.hisaab.accounts.Account;
import com.hisaab.common.Enums.Institution;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * What messages taught about where they come from: which of my accounts a bank's message means when it carries no
 * number (or a different one), and which bank a sender id belongs to, for texts that don't name their bank.
 */
@Service
public class SmsSenders {
    private final AccountHintRepo hints;
    private final SmsSenderRepo senders;

    public SmsSenders(AccountHintRepo hints, SmsSenderRepo senders) {
        this.hints = hints;
        this.senders = senders;
    }

    public Optional<Account> hintedAccount(Institution institution, String last4) {
        return hints.findByInstitutionAndLast4(institution, last4 == null ? "" : last4)
                .map(AccountHint::getAccount).filter(a -> !a.isArchived());
    }

    /** Remembers that this bank's SMS with these digits (or none) means this account. */
    @Transactional
    public void rememberAccount(Institution institution, String last4, Account account) {
        String key = last4 == null ? "" : last4;
        AccountHint h = hints.findByInstitutionAndLast4(institution, key).orElseGet(AccountHint::new);
        h.setInstitution(institution);
        h.setLast4(key);
        h.setAccount(account);
        hints.save(h);
    }

    /** "VM-HDFCBK-S" -> "HDFCBK": the part of a sender id that stays the same across operators and routes. */
    public static String senderKey(String sender) {
        if (sender == null) return null;
        String s = sender.trim().toUpperCase(Locale.ROOT).replaceAll("^[A-Z]{2}-", "").replaceAll("-[A-Z]$", "").replaceAll("[^A-Z0-9]", "");
        return s.length() < 3 || s.length() > 40 || s.matches("\\d+") ? null : s;
    }

    @Transactional
    public void rememberSender(String sender, Institution institution) {
        String key = senderKey(sender);
        if (key == null || institution == Institution.OTHER) return;
        SmsSender s = senders.findById(key).orElseGet(() -> new SmsSender(key, institution));
        s.setInstitution(institution);
        s.setHits(s.getHits() + 1);
        senders.save(s);
    }

    public Optional<Institution> senderInstitution(String sender) {
        String key = senderKey(sender);
        return key == null ? Optional.empty() : senders.findById(key).map(SmsSender::getInstitution);
    }

    public long senderCount() {
        return senders.count();
    }
}
