package com.hisaab.messages.controller;

import com.hisaab.messages.dto.LearnedResponse;
import com.hisaab.messages.service.LearnedService;
import org.springframework.web.bind.annotation.*;

/**
 * What the app has learned from my corrections (Settings → What it learned): message wordings, payee names, SMS
 * senders and the email senders I trusted, each with a way to make it forget.
 */
@RestController
@RequestMapping("/api")
public class LearnedController {
    private final LearnedService learned;

    public LearnedController(LearnedService learned) {
        this.learned = learned;
    }

    @GetMapping("/sms/learned")
    public LearnedResponse learned() {
        return learned.learned();
    }

    /** Use a paused format again (its correction count starts over). */
    @PostMapping("/sms/formats/{id}/resume")
    public void resumeFormat(@PathVariable Long id) {
        learned.resumeFormat(id);
    }

    @DeleteMapping("/sms/formats/{id}")
    public void forgetFormat(@PathVariable Long id) {
        learned.forgetFormat(id);
    }

    @DeleteMapping("/sms/payees/{id}")
    public void forgetPayee(@PathVariable Long id) {
        learned.forgetPayee(id);
    }

    /** Stop trusting an email domain I trusted: its later alerts wait for review again. */
    @DeleteMapping("/trusted-senders/{domain}")
    public void untrustSender(@PathVariable String domain) {
        learned.untrustSender(domain);
    }
}
