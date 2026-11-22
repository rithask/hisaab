package com.hisaab.settings.controller;

import com.hisaab.settings.dto.ResetPreviewResponse;
import com.hisaab.settings.dto.ResetRequest;
import com.hisaab.settings.dto.ResetResultResponse;
import com.hisaab.settings.service.ResetService;
import org.springframework.web.bind.annotation.*;

/** Settings → Reset: what a reset would remove, and doing it. */
@RestController
@RequestMapping("/api/reset")
public class ResetController {
    private final ResetService reset;

    public ResetController(ResetService reset) {
        this.reset = reset;
    }

    @GetMapping
    public ResetPreviewResponse preview() {
        return reset.preview();
    }

    @PostMapping
    public ResetResultResponse reset(@RequestBody ResetRequest in) {
        return reset.reset(in.parts(), in.word(), in.withoutBackup());
    }
}
