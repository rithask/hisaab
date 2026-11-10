package com.hisaab.service;

import com.hisaab.domain.Enums.TxnSource;
import com.hisaab.domain.PayeeAlias;
import com.hisaab.domain.Txn;
import com.hisaab.repo.PayeeAliasRepo;
import com.hisaab.repo.TxnRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Learned payee names. Banks write "SWIGGY*INSTAMART BLR"; once I rename that to "Swiggy Instamart" every
 * past and future alert with the same raw text uses my name.
 */
@Service
public class PayeeAliasService {
    private static final List<TxnSource> FROM_ALERTS = List.of(TxnSource.EMAIL, TxnSource.SMS);

    private final PayeeAliasRepo aliases;
    private final TxnRepo txns;

    public PayeeAliasService(PayeeAliasRepo aliases, TxnRepo txns) {
        this.aliases = aliases;
        this.txns = txns;
    }

    /** The name I prefer for this raw payee text, or the text itself. */
    @Transactional
    public String apply(String counterparty) {
        String key = Names.merchantKey(counterparty);
        if (key == null) return counterparty;
        return aliases.findByRawKey(key).map(a -> {
            a.setHits(a.getHits() + 1);
            return a.getDisplayName();
        }).orElse(counterparty);
    }

    /**
     * Remembers a rename and applies it to other transactions that came from alerts with the same raw payee.
     *
     * @return how many other transactions were renamed
     */
    @Transactional
    public int learn(Txn renamed, String oldName, String newName) {
        String oldKey = Names.merchantKey(oldName);
        String newKey = Names.merchantKey(newName);
        if (oldKey == null || newKey == null || newName.isBlank() || oldKey.equals(newKey) && oldName.equals(newName)) return 0;
        if (!FROM_ALERTS.contains(renamed.getSource())) return 0;

        PayeeAlias a = aliases.findByRawKey(oldKey).orElseGet(PayeeAlias::new);
        a.setRawKey(oldKey);
        a.setDisplayName(newName.trim());
        aliases.save(a);
        // Earlier aliases that pointed at the old name now point at the new one.
        for (PayeeAlias other : aliases.findAll()) {
            if (other != a && oldKey.equals(Names.merchantKey(other.getDisplayName()))) other.setDisplayName(newName.trim());
        }

        int n = 0;
        for (Txn t : txns.findByMerchantKeyAndSourceIn(oldKey, FROM_ALERTS)) {
            if (t.getId().equals(renamed.getId())) continue;
            t.setCounterparty(newName.trim());
            t.setMerchantKey(newKey);
            n++;
        }
        return n;
    }

    public List<PayeeAlias> all() {
        return aliases.findAllByOrderByHitsDescDisplayNameAsc();
    }

    @Transactional
    public void forget(Long id) {
        aliases.deleteById(id);
    }
}
