package com.hisaab.repo;

import com.hisaab.domain.Enums.EmailStatus;
import com.hisaab.domain.RawEmail;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RawEmailRepo extends JpaRepository<RawEmail, Long> {
    boolean existsByMessageId(String messageId);

    java.util.Optional<com.hisaab.domain.RawEmail> findByMessageId(String messageId);

    Page<RawEmail> findByStatusOrderByReceivedAtDesc(EmailStatus status, Pageable pageable);

    Page<RawEmail> findAllByOrderByReceivedAtDesc(Pageable pageable);

    List<RawEmail> findByStatus(EmailStatus status);

    long countByStatus(EmailStatus status);
}
