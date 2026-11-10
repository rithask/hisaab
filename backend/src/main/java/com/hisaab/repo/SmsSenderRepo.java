package com.hisaab.repo;

import com.hisaab.domain.SmsSender;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SmsSenderRepo extends JpaRepository<SmsSender, String> {}
