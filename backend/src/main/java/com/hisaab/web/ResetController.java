package com.hisaab.web;

import com.hisaab.service.ResetService;
import com.hisaab.service.ResetService.Part;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Settings → Reset: what a reset would remove, and doing it. */
@RestController
@RequestMapping("/api/reset")
public class ResetController {
    private final ResetService reset;

    public ResetController(ResetService reset) {
        this.reset = reset;
    }

    @GetMapping
    public ResetService.Preview preview() {
        return reset.preview();
    }

    /** @param word what I typed to confirm ("reset") */
    public record ResetIn(List<Part> parts, String word, boolean withoutBackup) {}

    @PostMapping
    public ResetService.Result reset(@RequestBody ResetIn in) {
        return reset.reset(in.parts(), in.word(), in.withoutBackup());
    }
}
