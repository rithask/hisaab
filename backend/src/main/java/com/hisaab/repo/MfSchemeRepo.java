package com.hisaab.repo;

import com.hisaab.domain.MfScheme;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MfSchemeRepo extends JpaRepository<MfScheme, String>, JpaSpecificationExecutor<MfScheme> {
}
