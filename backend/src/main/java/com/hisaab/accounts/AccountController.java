package com.hisaab.accounts;

import com.hisaab.accounts.AccountService.AccountInput;
import com.hisaab.accounts.AccountService.AccountView;
import com.hisaab.common.Audit;
import com.hisaab.config.AuthLog;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final AccountService service;
    private final AccountRepo repo;

    public AccountController(AccountService service, AccountRepo repo) {
        this.service = service;
        this.repo = repo;
    }

    @GetMapping
    public List<AccountView> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(includeArchived);
    }

    @GetMapping("/{id}")
    public AccountView get(@PathVariable Long id) {
        return service.view(service.get(id));
    }

    @PostMapping
    public AccountView create(@RequestBody AccountInput in) {
        return service.view(service.save(new Account(), in));
    }

    @PutMapping("/{id}")
    public AccountView update(@PathVariable Long id, @RequestBody AccountInput in) {
        return service.view(service.save(service.get(id), in));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        var a = service.get(id);
        repo.delete(a);
        // deleting an account deletes its transactions too
        Audit.log("Account deleted id={} name=\"{}\" with its transactions", id, AuthLog.clean(a.getName(), 60));
    }
}
