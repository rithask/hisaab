package com.hisaab.investments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MfSchemeRepo extends JpaRepository<MfScheme, String>, JpaSpecificationExecutor<MfScheme> {
}
