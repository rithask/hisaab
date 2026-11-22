package com.hisaab.transactions.service;

import com.hisaab.accounts.service.AccountService;
import com.hisaab.categories.service.CategorizationService;
import com.hisaab.common.dto.PageResponse;
import com.hisaab.common.exception.BadRequestException;
import com.hisaab.common.model.Direction;
import com.hisaab.people.dto.ShareResponse;
import com.hisaab.people.model.SplitMethod;
import com.hisaab.people.repository.SplitShareRepository;
import com.hisaab.people.service.SplitService;
import com.hisaab.transactions.dto.NewTransactionRequest;
import com.hisaab.transactions.dto.PaidByRequest;
import com.hisaab.transactions.dto.SplitRequest;
import com.hisaab.transactions.dto.SplitResponse;
import com.hisaab.transactions.dto.TransactionResponse;
import com.hisaab.transactions.dto.TransactionUpdateRequest;
import com.hisaab.transactions.dto.TransactionUpdateResponse;
import com.hisaab.transactions.dto.TransferLinkRequest;
import com.hisaab.transactions.model.Txn;
import com.hisaab.transactions.service.TransactionService.Filter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the Transactions screen asks for (search, add, edit, link transfers, split), answered as DTOs. It puts
 * together TransactionService, which does the work on transactions for every feature, with transfers, splits,
 * categorising and the shapes the screen shows.
 */
@Service
public class TransactionFacade {
    private final TransactionService transactions;
    private final AccountService accounts;
    private final CategorizationService categorizer;
    private final TransferService transfers;
    private final SplitService splits;
    private final SplitShareRepository shares;
    private final TransactionViews views;

    public TransactionFacade(TransactionService transactions, AccountService accounts, CategorizationService categorizer,
                             TransferService transfers, SplitService splits, SplitShareRepository shares, TransactionViews views) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.categorizer = categorizer;
        this.transfers = transfers;
        this.splits = splits;
        this.shares = shares;
        this.views = views;
    }

    private TransactionResponse dto(Txn t) {
        return views.one(t);
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> list(Long accountId, Long categoryId, Boolean uncategorized, String tag,
                                                  String q, Instant from, Instant to, Direction direction,
                                                  boolean includeTransfers, BigDecimal minAmount, BigDecimal maxAmount,
                                                  String sort, int page, int size) {
        Sort order = switch (sort) {
            case "oldest" -> Sort.by(Sort.Direction.ASC, "occurredAt", "id");
            case "largest" -> Sort.by(Sort.Order.desc("amount"), Sort.Order.desc("occurredAt"));
            case "smallest" -> Sort.by(Sort.Order.asc("amount"), Sort.Order.desc("occurredAt"));
            default -> Sort.by(Sort.Direction.DESC, "occurredAt", "id");
        };
        Page<Txn> p = transactions.search(new Filter(accountId, categoryId, uncategorized, tag, q, from, to,
                        includeTransfers, direction, minAmount, maxAmount),
                PageRequest.of(page, Math.min(size, 200), order));
        return new PageResponse<>(views.page(p.getContent()), page, size, p.getTotalElements(), p.hasNext());
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(Long id) {
        return dto(transactions.get(id));
    }

    @Transactional
    public TransactionResponse create(NewTransactionRequest req) {
        if (req.accountId() == null) throw new BadRequestException("Account is required");
        if (req.direction() == null) throw new BadRequestException("Direction is required");
        return dto(transactions.createManual(req, accounts.get(req.accountId())));
    }

    /** My share of something a friend paid for (see TransactionService#createPaidBy). */
    @Transactional
    public TransactionResponse createPaidBy(PaidByRequest in) {
        Txn t = transactions.createPaidBy(in);
        return dto(t);
    }

    @Transactional
    public TransactionResponse updatePaidBy(Long id, PaidByRequest in) {
        Txn t = transactions.updatePaidBy(id, in);
        return dto(t);
    }

    @Transactional
    public TransactionUpdateResponse update(Long id, TransactionUpdateRequest u) {
        var r = transactions.update(id, u);
        return new TransactionUpdateResponse(dto(r.txn()), r.similarCount(), r.renamedCount(), r.learnedWording());
    }

    public void delete(Long id) {
        transactions.delete(id);
    }

    /** After a category correction, apply the same category to other transactions from the same merchant. */
    @Transactional
    public Map<String, Integer> applyToSimilar(Long id) {
        Txn t = transactions.get(id);
        if (t.isPaidByPerson()) return Map.of("updated", 0); // nothing is learned from these
        return Map.of("updated", categorizer.applyToSimilar(t));
    }

    @Transactional
    public TransactionResponse link(Long id, TransferLinkRequest req) {
        Txn t = transactions.get(id), other = transactions.get(req.otherId());
        if (t.isPaidByPerson() || other.isPaidByPerson()) throw new BadRequestException("Someone else paid for this, so it isn't a transfer");
        transfers.link(t, other);
        return dto(t);
    }

    @Transactional
    public TransactionResponse unlink(Long id) {
        Txn t = transactions.get(id);
        transfers.unlink(t);
        return dto(t);
    }

    // ---- splits -----------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SplitResponse getSplit(Long id) {
        Txn t = transactions.get(id);
        return splitDto(t, shares.findByTxn(t).stream().map(ShareResponse::of).toList());
    }

    @Transactional
    public SplitResponse setSplit(Long id, SplitRequest req) {
        Txn t = transactions.get(id);
        if (!t.isDebit()) throw new BadRequestException("Only money I paid out can be split");
        if (t.isPaidByPerson()) throw new BadRequestException("Someone else paid for this; change your share instead");
        var saved = splits.setSplit(t, req.method() == null ? SplitMethod.EQUAL : req.method(),
                Boolean.TRUE.equals(req.includeMe()),
                req.participants());
        return splitDto(t, saved.stream().map(ShareResponse::of).toList());
    }

    private static SplitResponse splitDto(Txn t, List<ShareResponse> list) {
        BigDecimal others = list.stream().map(ShareResponse::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SplitResponse(t.getAmount(), others, t.getAmount().subtract(others), list);
    }
}
