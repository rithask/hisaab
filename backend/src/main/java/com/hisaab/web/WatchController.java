package com.hisaab.web;

import com.hisaab.features.FeatureService;
import com.hisaab.features.FeatureService.Feature;
import com.hisaab.features.RewardService;
import com.hisaab.features.SubscriptionService;
import com.hisaab.features.UnusualService;
import com.hisaab.features.WatchDismissals;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Subscription watch, unusual activity (with refunds I'm waiting for) and card rewards. */
@RestController
@RequestMapping("/api")
public class WatchController {
    private final FeatureService features;
    private final SubscriptionService subscriptions;
    private final UnusualService unusual;
    private final RewardService rewards;
    private final WatchDismissals dismissals;

    public WatchController(FeatureService features, SubscriptionService subscriptions, UnusualService unusual,
                           RewardService rewards, WatchDismissals dismissals) {
        this.features = features;
        this.subscriptions = subscriptions;
        this.unusual = unusual;
        this.rewards = rewards;
        this.dismissals = dismissals;
    }

    public record KeyInput(String key) {}

    // ---- subscriptions

    @GetMapping("/subscriptions")
    public SubscriptionService.Overview subscriptions() {
        features.require(Feature.SUBSCRIPTIONS);
        return subscriptions.overview();
    }

    @PostMapping("/subscriptions/hide")
    public void hide(@RequestBody KeyInput in) {
        features.require(Feature.SUBSCRIPTIONS);
        subscriptions.hide(key(in));
    }

    @PostMapping("/subscriptions/unhide")
    public void unhide(@RequestBody KeyInput in) {
        features.require(Feature.SUBSCRIPTIONS);
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
        features.require(Feature.UNUSUAL);
        return unusual.overview();
    }

    public record RefundInput(long txnId, LocalDate expectedBy) {}

    @GetMapping("/refunds/{txnId}")
    public ResponseEntity<UnusualService.Refund> refund(@PathVariable long txnId) {
        features.require(Feature.UNUSUAL);
        return unusual.refund(txnId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/refunds")
    public UnusualService.Refund expectRefund(@RequestBody RefundInput in) {
        features.require(Feature.UNUSUAL);
        if (in.expectedBy() != null && in.expectedBy().isBefore(LocalDate.now(com.hisaab.service.Money.ZONE)))
            throw new IllegalArgumentException("Pick a date from today on");
        unusual.expectRefund(in.txnId(), in.expectedBy());
        return unusual.refund(in.txnId()).orElseThrow();
    }

    @DeleteMapping("/refunds/{txnId}")
    public void stopWaiting(@PathVariable long txnId) {
        features.require(Feature.UNUSUAL);
        unusual.stopWaiting(txnId);
    }

    // ---- rewards

    @GetMapping("/rewards")
    public List<RewardService.CardRewards> rewards() {
        features.require(Feature.REWARDS);
        return rewards.all();
    }

    @PutMapping("/rewards/{accountId}")
    public Map<String, Boolean> saveRewards(@PathVariable long accountId, @RequestBody RewardService.ProgramInput in) {
        features.require(Feature.REWARDS);
        rewards.save(accountId, in);
        return Map.of("saved", true);
    }

    @DeleteMapping("/rewards/{accountId}")
    public void removeRewards(@PathVariable long accountId) {
        features.require(Feature.REWARDS);
        rewards.remove(accountId);
    }

    private static String key(KeyInput in) {
        if (in == null || in.key() == null || in.key().isBlank()) throw new IllegalArgumentException("Missing key");
        return in.key();
    }
}
