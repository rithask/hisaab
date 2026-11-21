package com.hisaab.messages;

import com.hisaab.common.Enums.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SmsTemplateRepo extends JpaRepository<SmsTemplate, Long> {
    List<SmsTemplate> findAllByOrderByHitsDescIdAsc();

    List<SmsTemplate> findByChannelOrderByHitsDescIdAsc(Channel channel);

    Optional<SmsTemplate> findBySkeleton(String skeleton);
}
