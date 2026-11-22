package com.hisaab.settings.service;

import com.hisaab.settings.service.ResetService.Part;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ResetServiceTest {
    @Test
    void clearingTheDataAlsoClearsWhatWasLearnedAndCategoriesNeedTheData() {
        assertThat(ResetService.withNeeded(List.of(Part.DATA))).containsExactlyInAnyOrder(Part.DATA, Part.LEARNED);
        assertThat(ResetService.withNeeded(List.of(Part.CATEGORIES))).containsExactlyInAnyOrder(Part.CATEGORIES, Part.DATA, Part.LEARNED);
        assertThat(ResetService.withNeeded(List.of(Part.LEARNED))).containsExactly(Part.LEARNED);
        assertThat(ResetService.withNeeded(List.of(Part.SETTINGS, Part.CONNECTIONS))).containsExactlyInAnyOrder(Part.SETTINGS, Part.CONNECTIONS);
    }

    @Test
    void eachPartTouchesOnlyItsOwnTables() {
        String learned = String.join("\n", ResetService.statements(Set.of(Part.LEARNED)));
        assertThat(learned).contains("category_rule where source <> 'SYSTEM'", "payee_alias", "sms_template")
                .doesNotContain("delete from txn", "delete from account\n", "delete from category\n", "app_setting");

        String settings = String.join("\n", ResetService.statements(Set.of(Part.SETTINGS)));
        assertThat(settings).contains("feature.%", "owner.name").doesNotContain("txn", "imap", "mail.", "backup.", "oidc");

        String connections = String.join("\n", ResetService.statements(Set.of(Part.CONNECTIONS)));
        assertThat(connections).contains("api_token", "oidc.link.%").doesNotContain("delete from txn", "feature.");
    }

    @Test
    void neverTouchesSignInMailProgressOrBackups() {
        String all = String.join("\n", ResetService.statements(Set.of(Part.values())));
        assertThat(all).doesNotContain("imap.", "mail.", "backup.", "mf_scheme");
    }

    @Test
    void childrenAreDeletedBeforeTheirParents() {
        List<String> sql = ResetService.statements(ResetService.withNeeded(List.of(Part.CATEGORIES)));
        assertThat(sql.indexOf("delete from txn")).isLessThan(sql.indexOf("delete from account"))
                .isLessThan(sql.indexOf("delete from person"));
        assertThat(sql.indexOf("delete from split_share")).isLessThan(sql.indexOf("delete from txn"));
        assertThat(sql.indexOf("delete from learned_example")).isLessThan(sql.indexOf("delete from raw_email"));
        assertThat(sql.indexOf("delete from txn")).isLessThan(sql.indexOf("delete from category"));
    }
}
