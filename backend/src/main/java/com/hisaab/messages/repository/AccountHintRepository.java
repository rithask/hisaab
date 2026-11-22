package com.hisaab.messages.repository;

import com.hisaab.common.model.Institution;
import com.hisaab.messages.model.AccountHint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountHintRepository extends JpaRepository<AccountHint, Long> {
    Optional<AccountHint> findByInstitutionAndLast4(Institution institution, String last4);
}
