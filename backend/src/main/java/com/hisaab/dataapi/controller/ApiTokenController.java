package com.hisaab.dataapi.controller;

import com.hisaab.dataapi.dto.TokenCreatedResponse;
import com.hisaab.dataapi.dto.TokenRequest;
import com.hisaab.dataapi.dto.TokenResponse;
import com.hisaab.dataapi.service.ApiTokenService;
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
    public List<TokenResponse> list() {
        return tokens.list();
    }

    /** The token itself is in the reply, and only there. */
    @PostMapping
    public TokenCreatedResponse create(@RequestBody TokenRequest in) {
        return tokens.create(in);
    }

    @PutMapping("/{id}")
    public TokenResponse update(@PathVariable Long id, @RequestBody TokenRequest in) {
        return tokens.update(id, in);
    }

    @DeleteMapping("/{id}")
    public void revoke(@PathVariable Long id) {
        tokens.revoke(id);
    }
}
