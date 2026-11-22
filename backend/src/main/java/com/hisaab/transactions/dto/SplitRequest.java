package com.hisaab.transactions.dto;

import com.hisaab.people.dto.SplitParticipantRequest;
import com.hisaab.people.model.SplitMethod;
import java.util.List;

public record SplitRequest(SplitMethod method, Boolean includeMe, List<SplitParticipantRequest> participants) {}
