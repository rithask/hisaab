package com.hisaab.repo;

import com.hisaab.domain.SmsTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SmsTemplateRepo extends JpaRepository<SmsTemplate, Long> {
    List<SmsTemplate> findAllByOrderByHitsDescIdAsc();

    Optional<SmsTemplate> findBySkeleton(String skeleton);
}
