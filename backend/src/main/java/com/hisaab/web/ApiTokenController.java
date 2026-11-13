package com.hisaab.web;

import com.hisaab.service.ApiTokenService;
import com.hisaab.service.ApiTokenService.TokenInput;
import com.hisaab.service.ApiTokenService.TokenView;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Managing API tokens, from the app (owner session only). */
@RestController
@RequestMapping("/api/tokens")
public class ApiTokenController {
    private final ApiTokenService tokens;

    public ApiTokenController(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    @GetMapping
    public List<TokenView> list() {
        return tokens.list();
    }

    /** The token itself is in the reply, and only there. */
    @PostMapping
    public ApiTokenService.Created create(@RequestBody TokenInput in) {
        return tokens.create(in);
    }

    @PutMapping("/{id}")
    public TokenView update(@PathVariable Long id, @RequestBody TokenInput in) {
        return tokens.update(id, in);
    }

    @DeleteMapping("/{id}")
    public void revoke(@PathVariable Long id) {
        tokens.revoke(id);
    }
}
