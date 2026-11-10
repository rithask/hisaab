package com.hisaab.repo;

import com.hisaab.domain.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TagRepo extends JpaRepository<Tag, Long> {
    Optional<Tag> findByNameIgnoreCase(String name);

    List<Tag> findAllByOrderByNameAsc();
}
