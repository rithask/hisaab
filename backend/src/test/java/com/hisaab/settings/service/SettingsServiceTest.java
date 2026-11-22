package com.hisaab.settings.service;

import com.hisaab.settings.model.AppSetting;
import com.hisaab.settings.repository.AppSettingRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SettingsServiceTest {
    @Test
    void readsTheTableOnceThenFromMemory() {
        AppSettingRepository repo = mock(AppSettingRepository.class);
        when(repo.findAll()).thenReturn(List.of(new AppSetting("feature.budgets", "false"), new AppSetting("statement.password.1", "x")));
        SettingsService settingsService = new SettingsService(repo);
        assertThat(settingsService.get("feature.budgets")).contains("false");
        assertThat(settingsService.get("owner.name")).isEmpty();
        assertThat(settingsService.withPrefix("statement.password.")).containsEntry("statement.password.1",
                "x").hasSize(1);
        verify(repo, times(1)).findAll();
    }

    @Test
    void aChangeShowsAtOnceAndForgetReloads() {
        List<AppSetting> table = new ArrayList<>(List.of(new AppSetting("feature.budgets", "true")));
        AppSettingRepository repo = mock(AppSettingRepository.class);
        when(repo.findAll()).thenAnswer(i -> List.copyOf(table));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        SettingsService settingsService = new SettingsService(repo);
        assertThat(settingsService.get("feature.budgets")).contains("true");

        settingsService.set("feature.budgets", "false"); // no transaction here, so it's remembered straight away
        assertThat(settingsService.get("feature.budgets")).contains("false");

        table.clear(); // what Reset does behind its back
        settingsService.forget();
        assertThat(settingsService.get("feature.budgets")).isEmpty();
    }
}
