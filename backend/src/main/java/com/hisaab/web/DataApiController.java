package com.hisaab.web;

import com.hisaab.config.ApiTokenFilter;
import com.hisaab.domain.ApiToken;
import com.hisaab.service.DataApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * The Data API for other apps ({@code /api/v1}, docs/api.md). Only API tokens get here, each only to the
 * endpoints of its job (ApiTokenFilter), and every reply stays within that token's limits.
 */
@RestController
@RequestMapping("/api/v1")
public class DataApiController {
    private final DataApiService api;

    public DataApiController(DataApiService api) {
        this.api = api;
    }

    private static ApiToken token(HttpServletRequest req) {
        return (ApiToken) req.getAttribute(ApiTokenFilter.TOKEN);
    }

    /** The next page is also linked in a {@code Link: <…>; rel="next"} header (RFC 8288). */
    @GetMapping("/transactions")
    public org.springframework.http.ResponseEntity<DataApi.Page<DataApi.Transaction>> transactions(HttpServletRequest req,
                                                          @RequestParam(required = false) String date,
                                                          @RequestParam(required = false) String since,
                                                          @RequestParam(name = "updated_since", required = false) String updatedSince,
                                                          @RequestParam(required = false) String category,
                                                          @RequestParam(required = false) String account,
                                                          @RequestParam(required = false) Integer limit,
                                                          @RequestParam(required = false) String cursor) {
        DataApi.Page<DataApi.Transaction> page = api.transactions(token(req), date, since, updatedSince, category, account, limit, cursor);
        var res = org.springframework.http.ResponseEntity.ok();
        if (page.nextCursor() != null) {
            String next = org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromCurrentRequest()
                    .replaceQueryParam("cursor", page.nextCursor()).build().toUriString();
            res.header(org.springframework.http.HttpHeaders.LINK, "<" + next + ">; rel=\"next\"");
        }
        return res.body(page);
    }

    @GetMapping("/transactions/{id}")
    public DataApi.Transaction transaction(HttpServletRequest req, @PathVariable String id) {
        return api.transaction(token(req), id);
    }

    @GetMapping("/categories")
    public DataApi.ListOf<DataApi.Category> categories(HttpServletRequest req) {
        return api.categories(token(req));
    }

    @GetMapping("/accounts")
    public DataApi.ListOf<DataApi.AccountRef> accounts(HttpServletRequest req) {
        return api.accounts(token(req));
    }

    @GetMapping("/summary")
    public DataApi.Summary summary(HttpServletRequest req,
                                   @RequestParam(required = false) String month,
                                   @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to,
                                   @RequestParam(name = "group_by", required = false) String groupBy,
                                   @RequestParam(required = false) String direction,
                                   @RequestParam(required = false) String category,
                                   @RequestParam(required = false) String account) {
        return api.summary(token(req), month, from, to, groupBy, direction, category, account);
    }

    /**
     * Adds a transaction: 201 with a Location header (RFC 9110 §15.3.2). With an Idempotency-Key header
     * (draft-ietf-httpapi-idempotency-key-header), repeating the same request returns what the first one created.
     */
    @PostMapping("/transactions")
    public org.springframework.http.ResponseEntity<DataApi.Transaction> add(HttpServletRequest req,
                                                                           @RequestBody(required = false) DataApi.NewTransaction in,
                                                                           @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        DataApiService.Added a = api.add(token(req), in, key);
        java.net.URI location = org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(a.transaction().id()).toUri();
        return org.springframework.http.ResponseEntity.created(location).body(a.transaction());
    }

    @GetMapping("/balances")
    public DataApi.Balances balances(HttpServletRequest req) {
        return api.balances(token(req));
    }

    @PostMapping("/sms")
    public DataApi.SmsReceived sms(HttpServletRequest req, @RequestBody(required = false) DataApi.NewSms in) {
        return api.sms(token(req), in);
    }
}
