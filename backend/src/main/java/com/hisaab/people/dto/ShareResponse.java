package com.hisaab.people.dto;

import com.hisaab.people.model.SplitMethod;
import com.hisaab.people.model.SplitShare;
import java.math.BigDecimal;

public record ShareResponse(Long id, Long personId, String personName, SplitMethod method, BigDecimal input,
                            BigDecimal amount) {
    public static ShareResponse of(SplitShare share) {
        return new ShareResponse(share.getId(), share.getPerson().getId(), share.getPerson().getName(),
                share.getMethod(),
                share.getInput(), share.getAmount());
    }
}
