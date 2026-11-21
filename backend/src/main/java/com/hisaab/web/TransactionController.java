package com.hisaab.web;

import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Enums.SplitMethod;
import com.hisaab.domain.Enums.SettlementDirection;
import com.hisaab.domain.Enums.SettlementStatus;
import com.hisaab.domain.Settlement;
import com.hisaab.domain.SplitShare;
import com.hisaab.domain.Txn;
import com.hisaab.repo.SettlementRepo;
import com.hisaab.repo.SplitShareRepo;
import com.hisaab.web.Dtos.PersonRef;
import com.hisaab.service.*;
import com.hisaab.service.TransactionService.Filter;
import com.hisaab.service.TransactionService.ManualTxn;
import com.hisaab.service.TransactionService.TxnUpdate;
import com.hisaab.web.Dtos.ShareDto;
import com.hisaab.web.Dtos.TxnDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

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
    private final SplitShareRepo shares;
    private final SettlementRepo settlements;

    public TransactionController(TransactionService service, AccountService accounts, CategorizationService categorizer,
                                 TransferService transfers, SplitService splits, SplitShareRepo shares,
                                 SettlementRepo settlements) {
        this.service = service;
        this.accounts = accounts;
        this.categorizer = categorizer;
        this.transfers = transfers;
        this.splits = splits;
        this.shares = shares;
        this.settlements = settlements;
    }

    /**
     * With what's mine of it, so the app can say "Lent to Fabis" or "Paid back by Fabis" instead of asking for a
     * category: a payment that's all someone else's isn't spending, and a repayment isn't income.
     */
    TxnDto dto(Txn t) {
        List<SplitShare> split = shares.findByTxn(t);
        List<Settlement> repaid = settlements.findByTxn(t).stream().filter(s -> s.getStatus() == SettlementStatus.CONFIRMED).toList();
        BigDecimal shared = split.stream().map(SplitShare::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal in = sum(repaid, SettlementDirection.IN), out = sum(repaid, SettlementDirection.OUT);
        BigDecimal mine = ReportService.myAmount(t.getDirection(), t.getAmount(), shared, in, out);
        List<PersonRef> lentTo = t.getDirection() == Direction.DEBIT && mine.signum() == 0 && out.signum() == 0
                ? people(split.stream().map(SplitShare::getPerson)) : List.of();
        return TxnDto.of(t, shared.signum() == 0 ? null : shared, mine, lentTo,
                people(repaid.stream().filter(s -> s.getDirection() == SettlementDirection.IN).map(Settlement::getPerson)),
                people(repaid.stream().filter(s -> s.getDirection() == SettlementDirection.OUT).map(Settlement::getPerson)));
    }

    private static BigDecimal sum(List<Settlement> list, SettlementDirection d) {
        return list.stream().filter(s -> s.getDirection() == d).map(Settlement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<PersonRef> people(java.util.stream.Stream<com.hisaab.domain.Person> s) {
        return s.distinct().map(p -> new PersonRef(p.getId(), p.getName())).toList();
    }

    public record PageDto<T>(List<T> items, int page, int size, long total, boolean hasMore) {}

    @GetMapping
    @Transactional(readOnly = true)
    public PageDto<TxnDto> list(@RequestParam(required = false) Long accountId,
                                @RequestParam(required = false) Long categoryId,
                                @RequestParam(required = false) Boolean uncategorized,
                                @RequestParam(required = false) String tag,
                                @RequestParam(required = false) String q,
                                @RequestParam(required = false) Instant from,
                                @RequestParam(required = false) Instant to,
                                @RequestParam(required = false) Direction direction,
                                @RequestParam(defaultValue = "false") boolean includeTransfers,
                                @RequestParam(required = false) java.math.BigDecimal minAmount,
                                @RequestParam(required = false) java.math.BigDecimal maxAmount,
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
        return new PageDto<>(p.getContent().stream().map(this::dto).toList(), page, size, p.getTotalElements(), p.hasNext());
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public TxnDto get(@PathVariable Long id) {
        return dto(service.get(id));
    }

    @PostMapping
    @Transactional
    public TxnDto create(@RequestBody ManualTxn req) {
        if (req.accountId() == null) throw new IllegalArgumentException("Account is required");
        if (req.direction() == null) throw new IllegalArgumentException("Direction is required");
        return dto(service.createManual(req, accounts.get(req.accountId())));
    }

    /** My share of something a friend paid for (see TransactionService#createPaidBy). */
    @PostMapping("/paid-by")
    @Transactional
    public TxnDto createPaidBy(@RequestBody TransactionService.PaidByInput in) {
        Txn t = service.createPaidBy(in);
        splits.rescan(); // they may already have sent the difference
        return dto(t);
    }

    @PutMapping("/{id}/paid-by")
    @Transactional
    public TxnDto updatePaidBy(@PathVariable Long id, @RequestBody TransactionService.PaidByInput in) {
        Txn t = service.updatePaidBy(id, in);
        splits.rescan();
        return dto(t);
    }

    public record UpdateResponse(TxnDto txn, int similarCount, int renamedCount, boolean learnedWording) {}

    @PatchMapping("/{id}")
    @Transactional
    public UpdateResponse update(@PathVariable Long id, @RequestBody TxnUpdate u) {
        var r = service.update(id, u);
        return new UpdateResponse(dto(r.txn()), r.similarCount(), r.renamedCount(), r.learnedWording());
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

    public record LinkRequest(Long otherId) {}

    @PostMapping("/{id}/link")
    @Transactional
    public TxnDto link(@PathVariable Long id, @RequestBody LinkRequest req) {
        Txn t = service.get(id), other = service.get(req.otherId());
        if (t.isPaidByPerson() || other.isPaidByPerson()) throw new IllegalArgumentException("Someone else paid for this, so it isn't a transfer");
        transfers.link(t, other);
        return dto(t);
    }

    @PostMapping("/{id}/unlink")
    @Transactional
    public TxnDto unlink(@PathVariable Long id) {
        Txn t = service.get(id);
        transfers.unlink(t);
        return dto(t);
    }

    // ---- splits -----------------------------------------------------------------------------------------

    public record SplitRequest(SplitMethod method, Boolean includeMe, List<SplitService.Participant> participants) {}

    public record SplitDto(BigDecimal total, BigDecimal othersOwe, BigDecimal myShare, List<ShareDto> shares) {}

    @GetMapping("/{id}/split")
    @Transactional(readOnly = true)
    public SplitDto getSplit(@PathVariable Long id) {
        Txn t = service.get(id);
        return splitDto(t, shares.findByTxn(t).stream().map(ShareDto::of).toList());
    }

    @PutMapping("/{id}/split")
    @Transactional
    public SplitDto setSplit(@PathVariable Long id, @RequestBody SplitRequest req) {
        Txn t = service.get(id);
        if (!t.isDebit()) throw new IllegalArgumentException("Only money I paid out can be split");
        if (t.isPaidByPerson()) throw new IllegalArgumentException("Someone else paid for this; change your share instead");
        var saved = splits.setSplit(t, req.method() == null ? SplitMethod.EQUAL : req.method(),
                Boolean.TRUE.equals(req.includeMe()),
                req.participants());
        splits.rescan(); // a repayment may have arrived before I split it
        return splitDto(t, saved.stream().map(ShareDto::of).toList());
    }

    private static SplitDto splitDto(Txn t, List<ShareDto> list) {
        BigDecimal others = list.stream().map(ShareDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SplitDto(t.getAmount(), others, t.getAmount().subtract(others), list);
    }
}
