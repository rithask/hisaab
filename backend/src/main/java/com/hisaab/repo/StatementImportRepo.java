package com.hisaab.repo;

import com.hisaab.domain.Enums.ImportStatus;
import com.hisaab.domain.StatementFile;
import com.hisaab.domain.StatementImport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StatementImportRepo extends JpaRepository<StatementImport, Long> {
    List<StatementImport> findByStatusOrderByCreatedAtDesc(ImportStatus status);

    long countByStatus(ImportStatus status);

    Optional<StatementImport> findFirstByStatementFileAndStatus(StatementFile file, ImportStatus status);
}
