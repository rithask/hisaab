package com.hisaab.messages.repository;

import com.hisaab.messages.model.SmsSender;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SmsSenderRepository extends JpaRepository<SmsSender, String> {}
