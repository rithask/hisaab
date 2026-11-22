package com.hisaab.watch.controller;

import com.hisaab.watch.dto.BillKeyRequest;
import com.hisaab.watch.dto.BillKeyResponse;
import com.hisaab.watch.dto.BillResponse;
import com.hisaab.watch.dto.CardRewardsResponse;
import com.hisaab.watch.dto.RefundRequest;
import com.hisaab.watch.dto.RefundResponse;
import com.hisaab.watch.dto.RewardProgramRequest;
import com.hisaab.watch.dto.SubscriptionOverviewResponse;
import com.hisaab.watch.dto.UnusualOverviewResponse;
import com.hisaab.settings.model.Feature;
import com.hisaab.common.util.Money;
import com.hisaab.settings.service.RequiresFeature;
import com.hisaab.watch.service.BillService;
import com.hisaab.watch.service.RewardService;
import com.hisaab.watch.service.SubscriptionService;
import com.hisaab.watch.service.UnusualService;
import com.hisaab.watch.service.WatchDismissals;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.hisaab.common.exception.BadRequestException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Subscription watch, unusual activity (with refunds I'm waiting for) and card rewards. */
@RestController
@RequestMapping("/api")
public class WatchController {
    private final BillService bills;
    private final SubscriptionService subscriptions;
    private final UnusualService unusual;
    private final RewardService rewards;
    private final WatchDismissals dismissals;

    public WatchController(BillService bills, SubscriptionService subscriptions, UnusualService unusual,
                           RewardService rewards, WatchDismissals dismissals) {
        this.bills = bills;
        this.subscriptions = subscriptions;
        this.unusual = unusual;
        this.rewards = rewards;
        this.dismissals = dismissals;
    }

    // ---- subscriptions

    @GetMapping("/subscriptions")
    public SubscriptionOverviewResponse subscriptions() {
        return subscriptions.overview();
    }

    @PostMapping("/subscriptions/hide")
    public void hide(@RequestBody BillKeyRequest in) {
        subscriptions.hide(key(in));
    }

    @PostMapping("/subscriptions/unhide")
    public void unhide(@RequestBody BillKeyRequest in) {
        subscriptions.unhide(key(in));
    }

    // ---- "looks fine" for an alert from either feature

    @PostMapping("/watch/dismiss")
    public void dismiss(@RequestBody BillKeyRequest in) {
        dismissals.dismiss(key(in));
    }

    @PostMapping("/watch/restore")
    public void restore(@RequestBody BillKeyRequest in) {
        dismissals.restore(key(in));
    }

    // ---- unusual activity and refunds

    @GetMapping("/unusual")
    public UnusualOverviewResponse unusual() {
        return unusual.overview();
    }

    @GetMapping("/refunds/{txnId}")
    public ResponseEntity<RefundResponse> refund(@PathVariable long txnId) {
        return unusual.refund(txnId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/refunds")
    public RefundResponse expectRefund(@RequestBody RefundRequest in) {
        if (in.expectedBy() != null && in.expectedBy().isBefore(LocalDate.now(Money.ZONE)))
            throw new BadRequestException("Pick a date from today on");
        unusual.expectRefund(in.txnId(), in.expectedBy());
        return unusual.refund(in.txnId()).orElseThrow();
    }

    @DeleteMapping("/refunds/{txnId}")
    public void stopWaiting(@PathVariable long txnId) {
        unusual.stopWaiting(txnId);
    }

    // ---- rewards

    @GetMapping("/rewards")
    @RequiresFeature(Feature.REWARDS)
    public List<CardRewardsResponse> rewards() {
        return rewards.all();
    }

    @PutMapping("/rewards/{accountId}")
    @RequiresFeature(Feature.REWARDS)
    public Map<String, Boolean> saveRewards(@PathVariable long accountId, @RequestBody RewardProgramRequest in) {
        rewards.save(accountId, in);
        return Map.of("saved", true);
    }

    @DeleteMapping("/rewards/{accountId}")
    @RequiresFeature(Feature.REWARDS)
    public void removeRewards(@PathVariable long accountId) {
        rewards.remove(accountId);
    }

    private static String key(BillKeyRequest in) {
        if (in == null || in.key() == null || in.key().isBlank()) throw new BadRequestException("Missing key");
        return in.key();
    }

    // ---- bills
    @GetMapping("/bills")
    public List<BillResponse> bills(@RequestParam(required = false) Integer within) {
        return within == null ? bills.all() : bills.upcoming(within);
    }

    @PostMapping("/bills/ignore")
    public void notABill(@RequestBody BillKeyResponse in) {
        bills.ignore(in.key());
    }

    @PostMapping("/bills/unignore")
    public void isABill(@RequestBody BillKeyResponse in) {
        bills.unignore(in.key());
    }
}
