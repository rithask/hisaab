package com.hisaab.repo;

import com.hisaab.domain.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepo extends JpaRepository<AppSetting, String> {
}
