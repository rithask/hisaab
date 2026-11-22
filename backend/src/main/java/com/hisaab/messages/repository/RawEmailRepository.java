package com.hisaab.messages.repository;

import com.hisaab.messages.model.EmailStatus;
import com.hisaab.messages.model.RawEmail;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RawEmailRepository extends JpaRepository<RawEmail, Long> {
    boolean existsByMessageId(String messageId);

    Optional<RawEmail> findByMessageId(String messageId);

    Page<RawEmail> findByStatusOrderByReceivedAtDesc(EmailStatus status, Pageable pageable);

    Page<RawEmail> findAllByOrderByReceivedAtDesc(Pageable pageable);

    List<RawEmail> findByStatus(EmailStatus status);

    long countByStatus(EmailStatus status);
}
