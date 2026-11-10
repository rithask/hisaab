package com.hisaab.repo;

import com.hisaab.domain.AccountHint;
import com.hisaab.domain.Enums.Institution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountHintRepo extends JpaRepository<AccountHint, Long> {
    Optional<AccountHint> findByInstitutionAndLast4(Institution institution, String last4);
}
