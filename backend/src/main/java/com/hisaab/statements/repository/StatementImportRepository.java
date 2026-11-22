package com.hisaab.statements.repository;

import com.hisaab.statements.model.ImportStatus;
import com.hisaab.statements.model.StatementFile;
import com.hisaab.statements.model.StatementImport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StatementImportRepository extends JpaRepository<StatementImport, Long> {
    List<StatementImport> findByStatusOrderByCreatedAtDesc(ImportStatus status);

    long countByStatus(ImportStatus status);

    Optional<StatementImport> findFirstByStatementFileAndStatus(StatementFile file, ImportStatus status);
}
