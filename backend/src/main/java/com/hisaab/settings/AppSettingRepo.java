package com.hisaab.settings;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepo extends JpaRepository<AppSetting, String> {
}
