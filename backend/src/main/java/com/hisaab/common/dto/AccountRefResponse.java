package com.hisaab.common.dto;

import com.hisaab.accounts.model.Account;
import com.hisaab.common.model.AccountType;
import com.hisaab.common.model.Institution;

public record AccountRefResponse(Long id, String name, AccountType type, Institution institution, String last4) {
    public static AccountRefResponse of(Account account) {
        if (account == null) return null;
        return new AccountRefResponse(account.getId(), account.getName(), account.getType(), account.getInstitution(),
                account.getLast4());
    }
}
