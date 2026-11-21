package com.hisaab.messages;

import com.hisaab.common.Enums.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LearnedExampleRepo extends JpaRepository<LearnedExample, Long> {
    List<LearnedExample> findByChannel(Channel channel);
}
