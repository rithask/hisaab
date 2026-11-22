package com.hisaab.messages.repository;

import com.hisaab.common.model.Enums.Channel;
import com.hisaab.messages.model.LearnedExample;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LearnedExampleRepository extends JpaRepository<LearnedExample, Long> {
    List<LearnedExample> findByChannel(Channel channel);
}
