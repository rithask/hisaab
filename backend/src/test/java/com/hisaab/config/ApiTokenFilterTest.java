package com.hisaab.config;

import com.hisaab.dataapi.model.TokenJob;
import com.hisaab.dataapi.model.ApiToken;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTokenFilterTest {
    @Test
    void eachEndpointTakesOnlyItsJob() {
        assertThat(ApiTokenFilter.jobsFor("POST", "/api/v1/sms")).containsExactly(TokenJob.SEND_SMS);
        assertThat(ApiTokenFilter.jobsFor("GET", "/api/v1/transactions")).containsExactly(TokenJob.READ_TRANSACTIONS);
        assertThat(ApiTokenFilter.jobsFor("GET", "/api/v1/transactions/812")).containsExactly(TokenJob.READ_TRANSACTIONS);
        assertThat(ApiTokenFilter.jobsFor("POST", "/api/v1/transactions")).containsExactly(TokenJob.ADD_TRANSACTIONS);
        assertThat(ApiTokenFilter.jobsFor("GET", "/api/v1/balances")).containsExactly(TokenJob.READ_BALANCES);
        assertThat(ApiTokenFilter.jobsFor("GET", "/api/v1/summary")).containsExactly(TokenJob.READ_TOTALS);
        assertThat(ApiTokenFilter.jobsFor("GET", "/api/v1/accounts")).doesNotContain(TokenJob.SEND_SMS);
        assertThat(ApiTokenFilter.jobsFor("GET", "/api/v1/categories")).doesNotContain(TokenJob.SEND_SMS, TokenJob.READ_BALANCES);
    }

    @Test
    void nothingChangesDataExceptAddingTransactions() {
        for (String m : new String[]{"PUT", "PATCH", "DELETE"}) {
            assertThat(ApiTokenFilter.jobsFor(m, "/api/v1/transactions/812")).isEmpty();
            assertThat(ApiTokenFilter.jobsFor(m, "/api/v1/transactions")).isEmpty();
        }
        assertThat(ApiTokenFilter.jobsFor("POST", "/api/v1/transactions/812")).isEmpty();
        assertThat(ApiTokenFilter.jobsFor("POST", "/api/v1/balances")).isEmpty();
        assertThat(ApiTokenFilter.jobsFor("GET", "/api/v1/settings")).isEmpty();
        assertThat(ApiTokenFilter.jobsFor("GET", "/api/v1/sms")).isEmpty();
    }

    @Test
    void limitsAllowOnlyWhatWasChosen() {
        ApiToken t = new ApiToken();
        assertThat(t.allowsCategory(5L)).isTrue();
        assertThat(t.allowsCategory(null)).isTrue();
        t.setCategoryIds(ApiToken.join(Set.of("5")));
        assertThat(t.allowsCategory(5L)).isTrue();
        assertThat(t.allowsCategory(6L)).isFalse();
        assertThat(t.allowsCategory(null)).isFalse();
        t.setCategoryIds("5,none");
        assertThat(t.allowsCategory(null)).isTrue();
        // every chosen category gone: nothing, never everything
        t.setCategoryIds("");
        assertThat(t.allowsCategory(5L)).isFalse();
        t.setAccountIds("3");
        assertThat(t.allowsAccount(3L)).isTrue();
        assertThat(t.allowsAccount(4L)).isFalse();
    }
}
