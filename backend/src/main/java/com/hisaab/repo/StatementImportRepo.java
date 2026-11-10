package com.hisaab.repo;

import com.hisaab.domain.Enums.ImportStatus;
import com.hisaab.domain.StatementImport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StatementImportRepo extends JpaRepository<StatementImport, Long> {
    List<StatementImport> findByStatusOrderByCreatedAtDesc(ImportStatus status);

    long countByStatus(ImportStatus status);

    java.util.Optional<StatementImport> findFirstByStatementFileAndStatus(com.hisaab.domain.StatementFile file, ImportStatus status);
}
