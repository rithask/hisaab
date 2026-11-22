package com.hisaab.transactions.controller;

import com.hisaab.people.dto.ShareResponse;
import com.hisaab.transactions.dto.NewTransactionRequest;
import com.hisaab.transactions.dto.PaidByRequest;
import com.hisaab.transactions.dto.SplitRequest;
import com.hisaab.transactions.dto.SplitResponse;
import com.hisaab.transactions.dto.TransactionResponse;
import com.hisaab.transactions.dto.TransactionUpdateRequest;
import com.hisaab.transactions.dto.TransactionUpdateResponse;
import com.hisaab.transactions.dto.TransferLinkRequest;
import com.hisaab.common.model.Direction;
import com.hisaab.people.model.SplitMethod;
import com.hisaab.accounts.service.AccountService;
import com.hisaab.categories.service.CategorizationService;
import com.hisaab.people.repository.SplitShareRepository;
import com.hisaab.people.service.SplitService;
import com.hisaab.transactions.model.Txn;
import com.hisaab.transactions.service.TransactionService;
import com.hisaab.transactions.service.TransactionService.Filter;
import com.hisaab.transactions.service.TransactionViews;
import com.hisaab.transactions.service.TransferService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.hisaab.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {
    private final TransactionService service;
    private final AccountService accounts;
    private final CategorizationService categorizer;
    private final TransferService transfers;
    private final SplitService splits;
    private final SplitShareRepository shares;
    private final TransactionViews views;

    public TransactionController(TransactionService service, AccountService accounts, CategorizationService categorizer,
                                 TransferService transfers, SplitService splits, SplitShareRepository shares,
                                 TransactionViews views) {
        this.service = service;
        this.accounts = accounts;
        this.categorizer = categorizer;
        this.transfers = transfers;
        this.splits = splits;
        this.shares = shares;
        this.views = views;
    }

    TransactionResponse dto(Txn t) {
        return views.one(t);
    }

    public record PageDto<T>(List<T> items, int page, int size, long total, boolean hasMore) {}

    @GetMapping
    @Transactional(readOnly = true)
    public PageDto<TransactionResponse> list(@RequestParam(required = false) Long accountId,
                                @RequestParam(required = false) Long categoryId,
                                @RequestParam(required = false) Boolean uncategorized,
                                @RequestParam(required = false) String tag,
                                @RequestParam(required = false) String q,
                                @RequestParam(required = false) Instant from,
                                @RequestParam(required = false) Instant to,
                                @RequestParam(required = false) Direction direction,
                                @RequestParam(defaultValue = "false") boolean includeTransfers,
                                @RequestParam(required = false) BigDecimal minAmount,
                                @RequestParam(required = false) BigDecimal maxAmount,
                                @RequestParam(defaultValue = "newest") String sort,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "50") int size) {
        Sort order = switch (sort) {
            case "oldest" -> Sort.by(Sort.Direction.ASC, "occurredAt", "id");
            case "largest" -> Sort.by(Sort.Order.desc("amount"), Sort.Order.desc("occurredAt"));
            case "smallest" -> Sort.by(Sort.Order.asc("amount"), Sort.Order.desc("occurredAt"));
            default -> Sort.by(Sort.Direction.DESC, "occurredAt", "id");
        };
        Page<Txn> p = service.search(new Filter(accountId, categoryId, uncategorized, tag, q, from, to,
                        includeTransfers, direction, minAmount, maxAmount),
                PageRequest.of(page, Math.min(size, 200), order));
        return new PageDto<>(views.page(p.getContent()), page, size, p.getTotalElements(), p.hasNext());
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public TransactionResponse get(@PathVariable Long id) {
        return dto(service.get(id));
    }

    @PostMapping
    @Transactional
    public TransactionResponse create(@RequestBody NewTransactionRequest req) {
        if (req.accountId() == null) throw new BadRequestException("Account is required");
        if (req.direction() == null) throw new BadRequestException("Direction is required");
        return dto(service.createManual(req, accounts.get(req.accountId())));
    }

    /** My share of something a friend paid for (see TransactionService#createPaidBy). */
    @PostMapping("/paid-by")
    @Transactional
    public TransactionResponse createPaidBy(@RequestBody PaidByRequest in) {
        Txn t = service.createPaidBy(in);
        return dto(t);
    }

    @PutMapping("/{id}/paid-by")
    @Transactional
    public TransactionResponse updatePaidBy(@PathVariable Long id, @RequestBody PaidByRequest in) {
        Txn t = service.updatePaidBy(id, in);
        return dto(t);
    }

    @PatchMapping("/{id}")
    @Transactional
    public TransactionUpdateResponse update(@PathVariable Long id, @RequestBody TransactionUpdateRequest u) {
        var r = service.update(id, u);
        return new TransactionUpdateResponse(dto(r.txn()), r.similarCount(), r.renamedCount(), r.learnedWording());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** After a category correction, apply the same category to other transactions from the same merchant. */
    @PostMapping("/{id}/apply-to-similar")
    @Transactional
    public Map<String, Integer> applyToSimilar(@PathVariable Long id) {
        Txn t = service.get(id);
        if (t.isPaidByPerson()) return Map.of("updated", 0); // nothing is learned from these
        return Map.of("updated", categorizer.applyToSimilar(t));
    }

    @PostMapping("/{id}/link")
    @Transactional
    public TransactionResponse link(@PathVariable Long id, @RequestBody TransferLinkRequest req) {
        Txn t = service.get(id), other = service.get(req.otherId());
        if (t.isPaidByPerson() || other.isPaidByPerson()) throw new BadRequestException("Someone else paid for this, so it isn't a transfer");
        transfers.link(t, other);
        return dto(t);
    }

    @PostMapping("/{id}/unlink")
    @Transactional
    public TransactionResponse unlink(@PathVariable Long id) {
        Txn t = service.get(id);
        transfers.unlink(t);
        return dto(t);
    }

    // ---- splits -----------------------------------------------------------------------------------------

    @GetMapping("/{id}/split")
    @Transactional(readOnly = true)
    public SplitResponse getSplit(@PathVariable Long id) {
        Txn t = service.get(id);
        return splitDto(t, shares.findByTxn(t).stream().map(ShareResponse::of).toList());
    }

    @PutMapping("/{id}/split")
    @Transactional
    public SplitResponse setSplit(@PathVariable Long id, @RequestBody SplitRequest req) {
        Txn t = service.get(id);
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
