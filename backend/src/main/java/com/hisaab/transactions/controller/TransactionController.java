package com.hisaab.transactions.controller;

import com.hisaab.transactions.service.TransactionFacade;
import com.hisaab.transactions.dto.NewTransactionRequest;
import com.hisaab.transactions.dto.PaidByRequest;
import com.hisaab.transactions.dto.SplitRequest;
import com.hisaab.transactions.dto.SplitResponse;
import com.hisaab.transactions.dto.TransactionResponse;
import com.hisaab.transactions.dto.TransactionUpdateRequest;
import com.hisaab.transactions.dto.TransactionUpdateResponse;
import com.hisaab.transactions.dto.TransferLinkRequest;
import com.hisaab.common.model.Direction;
import org.springframework.web.bind.annotation.*;
import com.hisaab.common.dto.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {
    private final TransactionFacade transactions;

    public TransactionController(TransactionFacade transactions) {
        this.transactions = transactions;
    }

    @GetMapping
    public PageResponse<TransactionResponse> list(@RequestParam(required = false) Long accountId,
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
        return transactions.list(accountId, categoryId, uncategorized, tag, q, from, to, direction, includeTransfers, minAmount, maxAmount, sort, page, size);
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable Long id) {
        return transactions.get(id);
    }

    @PostMapping
    public TransactionResponse create(@RequestBody NewTransactionRequest req) {
        return transactions.create(req);
    }

    /** My share of something a friend paid for (see TransactionService#createPaidBy). */
    @PostMapping("/paid-by")
    public TransactionResponse createPaidBy(@RequestBody PaidByRequest in) {
        return transactions.createPaidBy(in);
    }

    @PutMapping("/{id}/paid-by")
    public TransactionResponse updatePaidBy(@PathVariable Long id, @RequestBody PaidByRequest in) {
        return transactions.updatePaidBy(id, in);
    }

    @PatchMapping("/{id}")
    public TransactionUpdateResponse update(@PathVariable Long id, @RequestBody TransactionUpdateRequest u) {
        return transactions.update(id, u);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        transactions.delete(id);
    }

    /** After a category correction, apply the same category to other transactions from the same merchant. */
    @PostMapping("/{id}/apply-to-similar")
    public Map<String, Integer> applyToSimilar(@PathVariable Long id) {
        return transactions.applyToSimilar(id);
    }

    @PostMapping("/{id}/link")
    public TransactionResponse link(@PathVariable Long id, @RequestBody TransferLinkRequest req) {
        return transactions.link(id, req);
    }

    @PostMapping("/{id}/unlink")
    public TransactionResponse unlink(@PathVariable Long id) {
        return transactions.unlink(id);
    }

    // ---- splits -----------------------------------------------------------------------------------------

    @GetMapping("/{id}/split")
    public SplitResponse getSplit(@PathVariable Long id) {
        return transactions.getSplit(id);
    }

    @PutMapping("/{id}/split")
    public SplitResponse setSplit(@PathVariable Long id, @RequestBody SplitRequest req) {
        return transactions.setSplit(id, req);
    }
}
