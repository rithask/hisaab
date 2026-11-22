package com.hisaab.transactions.service;

import com.hisaab.common.model.Direction;
import com.hisaab.common.model.Institution;
import com.hisaab.accounts.model.Account;
import com.hisaab.common.util.Names;
import com.hisaab.settings.service.SettingsService;
import com.hisaab.transactions.model.Transaction;
import com.hisaab.transactions.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hisaab.common.exception.BadRequestException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Detects money moving between my own accounts (bank → bank, bank → credit card bill) so it is
 * kept out of income/expense totals. Both legs stay in the ledger, flagged as transfers.
 */
@Service
public class TransferService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TransferService.class);
    private static final Duration WINDOW = Duration.ofDays(3);

    private final TransactionRepository txns;
    private final SettingsService settings;

    public TransferService(TransactionRepository txns, SettingsService settings) {
        this.txns = txns;
        this.settings = settings;
    }

    /** Call before the transaction is categorised. {@code hintedCardPayment} comes from the parser. */
    @Transactional
    public void detect(Transaction transaction, boolean hintedCardPayment) {
        if (transaction.getLinkedTxn() != null) return;
        Direction other = transaction.getDirection() == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT;
        List<Transaction> candidates = txns.findTransferCandidates(transaction.getAccount(), other,
                transaction.getAmount(),
                transaction.getOccurredAt().minus(WINDOW), transaction.getOccurredAt().plus(WINDOW));
        candidates.stream()
                .filter(c -> !c.getId().equals(transaction.getId()))
                // a debit to a stranger with the same amount shouldn't be paired; prefer flagged/self ones
                .filter(c -> c.isTransfer() || looksLikeSelf(c) || looksLikeSelf(transaction) || hintedCardPayment
                        || c.getAccount().isCard() || transaction.getAccount().isCard()
                        || mentions(transaction, c.getAccount()) || mentions(c, transaction.getAccount()))
                .min(Comparator.comparing(c -> Duration.between(c.getOccurredAt(), transaction.getOccurredAt()).abs()))
                .ifPresentOrElse(c -> link(transaction, c), () -> {
                    if (hintedCardPayment || looksLikeSelf(transaction)) {
                        transaction.setTransfer(true);
                        log.info("Transaction {} marked as a transfer between my accounts ({}), other side not found yet", transaction.getId(),
                                hintedCardPayment ? "card payment" : "paid to my own name");
                    }
                });
    }

    /** e.g. Canara debit "to Axis Bank" paired with a credit on my Axis account. */
    static boolean mentions(Transaction t, Account other) {
        return other.getInstitution() != Institution.OTHER
                && Names.containsWord(t.getCounterparty(), other.getInstitution().name());
    }

    boolean looksLikeSelf(Transaction t) {
        String owner = settings.ownerName();
        return owner != null && Names.samePerson(owner, t.getCounterparty());
    }

    @Transactional
    public void link(Transaction a, Transaction b) {
        if (a.getAccount().getId().equals(b.getAccount().getId())) {
            throw new BadRequestException("Both sides of a transfer must be in different accounts");
        }
        if (a.getDirection() == b.getDirection()) {
            throw new BadRequestException("A transfer needs one debit and one credit");
        }
        a.setLinkedTxn(b);
        b.setLinkedTxn(a);
        for (Transaction t : List.of(a, b)) {
            t.setTransfer(true);
            t.setCategory(null);
        }
        log.info("Transfer linked: transaction {} (account {}) and {} (account {})", a.getId(), a.getAccount().getId(), b.getId(), b.getAccount().getId());
    }

    @Transactional
    public void unlink(Transaction a) {
        Transaction b = a.getLinkedTxn();
        log.info("Transfer unlinked: transaction {}{}", a.getId(), b == null ? "" : " and " + b.getId());
        a.setLinkedTxn(null);
        a.setTransfer(false);
        if (b != null) {
            b.setLinkedTxn(null);
            b.setTransfer(false);
        }
    }
}
