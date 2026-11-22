package com.hisaab.categories.repository;

import com.hisaab.categories.model.PayeeAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PayeeAliasRepository extends JpaRepository<PayeeAlias, Long> {
    Optional<PayeeAlias> findByRawKey(String rawKey);

    List<PayeeAlias> findAllByOrderByHitsDescDisplayNameAsc();
}
