package com.hisaab.settings;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SettingsServiceTest {
    @Test
    void readsTheTableOnceThenFromMemory() {
        AppSettingRepo repo = mock(AppSettingRepo.class);
        when(repo.findAll()).thenReturn(List.of(new AppSetting("feature.budgets", "false"), new AppSetting("statement.password.1", "x")));
        SettingsService s = new SettingsService(repo);
        assertThat(s.get("feature.budgets")).contains("false");
        assertThat(s.get("owner.name")).isEmpty();
        assertThat(s.withPrefix("statement.password.")).containsEntry("statement.password.1", "x").hasSize(1);
        verify(repo, times(1)).findAll();
    }

    @Test
    void aChangeShowsAtOnceAndForgetReloads() {
        List<AppSetting> table = new ArrayList<>(List.of(new AppSetting("feature.budgets", "true")));
        AppSettingRepo repo = mock(AppSettingRepo.class);
        when(repo.findAll()).thenAnswer(i -> List.copyOf(table));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        SettingsService s = new SettingsService(repo);
        assertThat(s.get("feature.budgets")).contains("true");

        s.set("feature.budgets", "false"); // no transaction here, so it's remembered straight away
        assertThat(s.get("feature.budgets")).contains("false");

        table.clear(); // what Reset does behind its back
        s.forget();
        assertThat(s.get("feature.budgets")).isEmpty();
    }
}
