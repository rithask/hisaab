package com.hisaab.watch;

import com.hisaab.common.Money;
import com.hisaab.settings.FeatureService.Feature;
import com.hisaab.settings.RequiresFeature;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    public record KeyInput(String key) {}

    // ---- subscriptions

    @GetMapping("/subscriptions")
    public SubscriptionService.Overview subscriptions() {
        return subscriptions.overview();
    }

    @PostMapping("/subscriptions/hide")
    public void hide(@RequestBody KeyInput in) {
        subscriptions.hide(key(in));
    }

    @PostMapping("/subscriptions/unhide")
    public void unhide(@RequestBody KeyInput in) {
        subscriptions.unhide(key(in));
    }

    // ---- "looks fine" for an alert from either feature

    @PostMapping("/watch/dismiss")
    public void dismiss(@RequestBody KeyInput in) {
        dismissals.dismiss(key(in));
    }

    @PostMapping("/watch/restore")
    public void restore(@RequestBody KeyInput in) {
        dismissals.restore(key(in));
    }

    // ---- unusual activity and refunds

    @GetMapping("/unusual")
    public UnusualService.Overview unusual() {
        return unusual.overview();
    }

    public record RefundInput(long txnId, LocalDate expectedBy) {}

    @GetMapping("/refunds/{txnId}")
    public ResponseEntity<UnusualService.Refund> refund(@PathVariable long txnId) {
        return unusual.refund(txnId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/refunds")
    public UnusualService.Refund expectRefund(@RequestBody RefundInput in) {
        if (in.expectedBy() != null && in.expectedBy().isBefore(LocalDate.now(Money.ZONE)))
            throw new IllegalArgumentException("Pick a date from today on");
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
    public List<RewardService.CardRewards> rewards() {
        return rewards.all();
    }

    @PutMapping("/rewards/{accountId}")
    @RequiresFeature(Feature.REWARDS)
    public Map<String, Boolean> saveRewards(@PathVariable long accountId, @RequestBody RewardService.ProgramInput in) {
        rewards.save(accountId, in);
        return Map.of("saved", true);
    }

    @DeleteMapping("/rewards/{accountId}")
    @RequiresFeature(Feature.REWARDS)
    public void removeRewards(@PathVariable long accountId) {
        rewards.remove(accountId);
    }

    private static String key(KeyInput in) {
        if (in == null || in.key() == null || in.key().isBlank()) throw new IllegalArgumentException("Missing key");
        return in.key();
    }

    // ---- bills
    @GetMapping("/bills")
    public List<BillService.Bill> bills(@RequestParam(required = false) Integer within) {
        return within == null ? bills.all() : bills.upcoming(within);
    }

    public record BillKey(String key) {}

    @PostMapping("/bills/ignore")
    public void notABill(@RequestBody BillKey in) {
        bills.ignore(in.key());
    }

    @PostMapping("/bills/unignore")
    public void isABill(@RequestBody BillKey in) {
        bills.unignore(in.key());
    }
}
