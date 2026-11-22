package com.hisaab.messages.repository;

import com.hisaab.messages.model.TrustedSender;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrustedSenderRepository extends JpaRepository<TrustedSender, String> {
    java.util.List<TrustedSender> findAllByOrderByDomainAsc();
}
