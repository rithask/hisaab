package com.hisaab.messages.repository;

import com.hisaab.messages.model.Channel;
import com.hisaab.messages.model.LearnedFormat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LearnedFormatRepository extends JpaRepository<LearnedFormat, Long> {
    List<LearnedFormat> findAllByOrderByHitsDescIdAsc();

    List<LearnedFormat> findByChannelOrderByHitsDescIdAsc(Channel channel);

    Optional<LearnedFormat> findBySkeleton(String skeleton);
}
