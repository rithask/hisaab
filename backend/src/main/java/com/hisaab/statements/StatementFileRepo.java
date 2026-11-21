package com.hisaab.statements;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StatementFileRepo extends JpaRepository<StatementFile, Long> {
    List<StatementFile> findAllByOrderByReceivedAtDesc();

    long countByImportStatus(String importStatus);

    List<StatementFile> findByImportStatus(String importStatus);
}
