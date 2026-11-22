package com.hisaab.dataapi.repository;

import com.hisaab.dataapi.model.ApiToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {
    Optional<ApiToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    List<ApiToken> findByRevokedAtIsNullOrderByCreatedAtAsc();
}
