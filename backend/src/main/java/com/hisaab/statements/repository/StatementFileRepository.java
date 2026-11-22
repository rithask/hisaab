package com.hisaab.statements.repository;

import com.hisaab.statements.model.StatementFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StatementFileRepository extends JpaRepository<StatementFile, Long> {
    List<StatementFile> findAllByOrderByReceivedAtDesc();

    long countByImportStatus(String importStatus);

    List<StatementFile> findByImportStatus(String importStatus);
}
