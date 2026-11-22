package com.hisaab.categories.repository;

import com.hisaab.categories.model.CategoryVote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryVoteRepository extends JpaRepository<CategoryVote, Long> {
    List<CategoryVote> findByKeyTypeAndKey(String keyType, String key);

    List<CategoryVote> findByKeyType(String keyType);
}
