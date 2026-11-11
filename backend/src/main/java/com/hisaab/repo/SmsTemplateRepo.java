package com.hisaab.repo;

import com.hisaab.domain.SmsTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SmsTemplateRepo extends JpaRepository<SmsTemplate, Long> {
    List<SmsTemplate> findAllByOrderByHitsDescIdAsc();

    List<SmsTemplate> findByChannelOrderByHitsDescIdAsc(com.hisaab.domain.Enums.Channel channel);

    Optional<SmsTemplate> findBySkeleton(String skeleton);
}
