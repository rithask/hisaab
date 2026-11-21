package com.hisaab.categories;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PayeeAliasRepo extends JpaRepository<PayeeAlias, Long> {
    Optional<PayeeAlias> findByRawKey(String rawKey);

    List<PayeeAlias> findAllByOrderByHitsDescDisplayNameAsc();
}
