package com.hisaab.messages.repository;

import com.hisaab.messages.model.EmailStatus;
import com.hisaab.messages.model.InboxMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InboxMessageRepository extends JpaRepository<InboxMessage, Long> {
    boolean existsByMessageId(String messageId);

    Optional<InboxMessage> findByMessageId(String messageId);

    Page<InboxMessage> findByStatusOrderByReceivedAtDesc(EmailStatus status, Pageable pageable);

    Page<InboxMessage> findAllByOrderByReceivedAtDesc(Pageable pageable);

    List<InboxMessage> findByStatus(EmailStatus status);

    long countByStatus(EmailStatus status);
}
