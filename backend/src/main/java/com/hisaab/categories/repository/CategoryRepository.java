package com.hisaab.categories.repository;

import com.hisaab.categories.model.Category;
import com.hisaab.common.model.Enums.CategoryKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findAllByOrderByKindAscNameAsc();

    Optional<Category> findByNameAndKind(String name, CategoryKind kind);

    /** By name, ignoring case; the oldest if two kinds share it (as the Data API always picked). */
    Optional<Category> findFirstByNameIgnoreCaseOrderByIdAsc(String name);
}
