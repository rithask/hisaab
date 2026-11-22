package com.hisaab.messages.repository;

import com.hisaab.common.model.Enums.Channel;
import com.hisaab.messages.model.SmsTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SmsTemplateRepository extends JpaRepository<SmsTemplate, Long> {
    List<SmsTemplate> findAllByOrderByHitsDescIdAsc();

    List<SmsTemplate> findByChannelOrderByHitsDescIdAsc(Channel channel);

    Optional<SmsTemplate> findBySkeleton(String skeleton);
}
