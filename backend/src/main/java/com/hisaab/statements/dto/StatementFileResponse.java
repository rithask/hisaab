package com.hisaab.statements.dto;

import com.hisaab.common.dto.AccountRefResponse;
import com.hisaab.statements.model.StatementFile;
import java.time.Instant;

public record StatementFileResponse(Long id, AccountRefResponse account, String fileName, long sizeBytes, String periodLabel,
                                    Instant receivedAt, String importStatus, String importNote, boolean fromEmail) {
    public static StatementFileResponse of(StatementFile file) {
        return new StatementFileResponse(file.getId(), file.getAccount() == null ? null : AccountRefResponse.of(file.getAccount()),
                file.getFileName(), file.getSizeBytes(), file.getPeriodLabel(), file.getReceivedAt(), file.getImportStatus(),
                file.getImportNote(), file.getRawEmail() != null);
    }
}
