package com.hisaab.investments.repository;

import com.hisaab.investments.model.MfScheme;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MfSchemeRepository extends JpaRepository<MfScheme, String>, JpaSpecificationExecutor<MfScheme> {
}
