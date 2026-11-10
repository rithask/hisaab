package com.hisaab.repo;

import com.hisaab.domain.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRepo extends JpaRepository<Alert, Long> {
    List<Alert> findByDismissedFalseOrderByCreatedAtDesc();

    long countByDismissedFalse();
}
