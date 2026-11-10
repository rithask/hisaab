package com.hisaab.service;

import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Txn;
import com.hisaab.repo.TxnRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Detects money moving between my own accounts (bank → bank, bank → credit card bill) so it is
 * kept out of income/expense totals. Both legs stay in the ledger, flagged as transfers.
 */
@Service
public class TransferService {
    private static final Duration WINDOW = Duration.ofDays(3);

    private final TxnRepo txns;
    private final SettingsService settings;

    public TransferService(TxnRepo txns, SettingsService settings) {
        this.txns = txns;
        this.settings = settings;
    }

    /** Call before the transaction is categorised. {@code hintedCardPayment} comes from the parser. */
    @Transactional
    public void detect(Txn t, boolean hintedCardPayment) {
        if (t.getLinkedTxn() != null) return;
        Direction other = t.getDirection() == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT;
        List<Txn> candidates = txns.findTransferCandidates(t.getAccount(), other, t.getAmount(),
                t.getOccurredAt().minus(WINDOW), t.getOccurredAt().plus(WINDOW));
        candidates.stream()
                .filter(c -> !c.getId().equals(t.getId()))
                // a debit to a stranger with the same amount shouldn't be paired; prefer flagged/self ones
                .filter(c -> c.isTransfer() || looksLikeSelf(c) || looksLikeSelf(t) || hintedCardPayment
                        || c.getAccount().isCard() || t.getAccount().isCard()
                        || mentions(t, c.getAccount()) || mentions(c, t.getAccount()))
                .min(Comparator.comparing(c -> Duration.between(c.getOccurredAt(), t.getOccurredAt()).abs()))
                .ifPresentOrElse(c -> link(t, c), () -> {
                    if (hintedCardPayment || looksLikeSelf(t)) t.setTransfer(true);
                });
    }

    /** e.g. Canara debit "to Axis Bank" paired with a credit on my Axis account. */
    static boolean mentions(Txn t, com.hisaab.domain.Account other) {
        return other.getInstitution() != com.hisaab.domain.Enums.Institution.OTHER
                && Names.containsWord(t.getCounterparty(), other.getInstitution().name());
    }

    boolean looksLikeSelf(Txn t) {
        String owner = settings.ownerName();
        return owner != null && Names.samePerson(owner, t.getCounterparty());
    }

    @Transactional
    public void link(Txn a, Txn b) {
        if (a.getAccount().getId().equals(b.getAccount().getId())) {
            throw new IllegalArgumentException("Both sides of a transfer must be in different accounts");
        }
        if (a.getDirection() == b.getDirection()) {
            throw new IllegalArgumentException("A transfer needs one debit and one credit");
        }
        a.setLinkedTxn(b);
        b.setLinkedTxn(a);
        for (Txn t : List.of(a, b)) {
            t.setTransfer(true);
            t.setCategory(null);
        }
    }

    @Transactional
    public void unlink(Txn a) {
        Txn b = a.getLinkedTxn();
        a.setLinkedTxn(null);
        a.setTransfer(false);
        if (b != null) {
            b.setLinkedTxn(null);
            b.setTransfer(false);
        }
    }
}
