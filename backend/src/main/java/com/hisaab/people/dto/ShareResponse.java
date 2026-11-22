package com.hisaab.people.dto;

import com.hisaab.people.model.SplitMethod;
import com.hisaab.people.model.SplitShare;
import java.math.BigDecimal;

public record ShareResponse(Long id, Long personId, String personName, SplitMethod method, BigDecimal input,
                            BigDecimal amount) {
    public static ShareResponse of(SplitShare s) {
        return new ShareResponse(s.getId(), s.getPerson().getId(), s.getPerson().getName(), s.getMethod(),
                s.getInput(), s.getAmount());
    }
}
