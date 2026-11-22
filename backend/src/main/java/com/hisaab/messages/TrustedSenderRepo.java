package com.hisaab.messages;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TrustedSenderRepo extends JpaRepository<TrustedSender, String> {
    java.util.List<TrustedSender> findAllByOrderByDomainAsc();
}
