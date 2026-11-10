package com.hisaab.repo;

import com.hisaab.domain.StatementFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StatementFileRepo extends JpaRepository<StatementFile, Long> {
    List<StatementFile> findAllByOrderByReceivedAtDesc();

    long countByImportStatus(String importStatus);
}
