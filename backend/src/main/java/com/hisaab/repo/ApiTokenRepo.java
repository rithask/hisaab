package com.hisaab.repo;

import com.hisaab.domain.ApiToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiTokenRepo extends JpaRepository<ApiToken, Long> {
    Optional<ApiToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    List<ApiToken> findByRevokedAtIsNullOrderByCreatedAtAsc();
}
