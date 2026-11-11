package com.hisaab.repo;

import com.hisaab.domain.Enums.Channel;
import com.hisaab.domain.LearnedExample;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LearnedExampleRepo extends JpaRepository<LearnedExample, Long> {
    List<LearnedExample> findByChannel(Channel channel);
}
