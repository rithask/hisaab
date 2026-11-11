package com.hisaab.repo;

import com.hisaab.domain.TrustedSender;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrustedSenderRepo extends JpaRepository<TrustedSender, String> {}
