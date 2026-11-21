package com.hisaab.categories;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryVoteRepo extends JpaRepository<CategoryVote, Long> {
    List<CategoryVote> findByKeyTypeAndKey(String keyType, String key);

    List<CategoryVote> findByKeyType(String keyType);
}
