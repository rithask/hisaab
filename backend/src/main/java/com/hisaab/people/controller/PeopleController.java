package com.hisaab.people.controller;

import com.hisaab.people.service.PeopleService;
import com.hisaab.people.dto.ConfirmSettlementRequest;
import com.hisaab.people.dto.PersonDetailResponse;
import com.hisaab.people.dto.PersonRequest;
import com.hisaab.people.dto.PersonResponse;
import com.hisaab.people.dto.SettlementRecordedResponse;
import com.hisaab.people.dto.SettlementRequest;
import com.hisaab.people.dto.SettlementResponse;
import com.hisaab.transactions.dto.TransactionResponse;
import com.hisaab.people.model.SettlementDirection;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class PeopleController {
    private final PeopleService people;

    public PeopleController(PeopleService people) {
        this.people = people;
    }

    @GetMapping("/people")
    public List<PersonResponse> list() {
        return people.list();
    }

    @PostMapping("/people")
    public PersonResponse create(@RequestBody PersonRequest in) {
        return people.create(in);
    }

    @PutMapping("/people/{id}")
    public PersonResponse update(@PathVariable Long id, @RequestBody PersonRequest in) {
        return people.update(id, in);
    }

    /**
     * Recent money that could be this person settling up, for picking it by hand: money in when they owe me,
     * money out when I owe them.
     */
    @GetMapping("/people/{id}/open-payments")
    public List<TransactionResponse> openPayments(@PathVariable Long id, @RequestParam SettlementDirection direction) {
        return people.openPayments(id, direction);
    }

    @DeleteMapping("/people/{id}")
    public void delete(@PathVariable Long id) {
        people.delete(id);
    }

    @GetMapping("/people/{id}")
    public PersonDetailResponse detail(@PathVariable Long id) {
        return people.detail(id);
    }

    // ---- settlements -------------------------------------------------------------------------------------

    @GetMapping("/settlements/suggested")
    public List<SettlementResponse> suggested() {
        return people.suggested();
    }

    @PostMapping("/settlements")
    public SettlementRecordedResponse record(@RequestBody SettlementRequest in) {
        return people.record(in);
    }

    /** Repayments linked to a payment (not the ones I turned down). */
    @GetMapping("/transactions/{id}/repayments")
    public List<SettlementResponse> repayments(@PathVariable Long id) {
        return people.repayments(id);
    }

    @PostMapping("/settlements/{id}/confirm")
    public SettlementRecordedResponse confirm(@PathVariable Long id, @RequestBody(required = false) ConfirmSettlementRequest in) {
        return people.confirm(id, in);
    }

    @PostMapping("/settlements/{id}/reject")
    public SettlementResponse reject(@PathVariable Long id) {
        return people.reject(id);
    }

    @DeleteMapping("/settlements/{id}")
    public void deleteSettlement(@PathVariable Long id) {
        people.deleteSettlement(id);
    }
}
