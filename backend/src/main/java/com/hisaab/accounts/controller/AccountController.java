package com.hisaab.accounts.controller;

import com.hisaab.accounts.dto.AccountRequest;
import com.hisaab.accounts.dto.AccountResponse;
import com.hisaab.accounts.model.Account;
import com.hisaab.accounts.service.AccountService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final AccountService service;
    public AccountController(AccountService service) {
        this.service = service;
    }

    @GetMapping
    public List<AccountResponse> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(includeArchived);
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable Long id) {
        return service.view(service.get(id));
    }

    @PostMapping
    public AccountResponse create(@RequestBody AccountRequest in) {
        return service.view(service.save(new Account(), in));
    }

    @PutMapping("/{id}")
    public AccountResponse update(@PathVariable Long id, @RequestBody AccountRequest in) {
        return service.view(service.save(service.get(id), in));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
