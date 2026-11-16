package com.hisaab.service;

import com.hisaab.domain.Person;
import com.hisaab.repo.PersonRepo;
import com.hisaab.repo.SettlementRepo;
import com.hisaab.repo.SplitShareRepo;
import com.hisaab.repo.TxnRepo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SplitServiceTest {
    private final SplitShareRepo shares = mock(SplitShareRepo.class);
    private final SettlementRepo settlements = mock(SettlementRepo.class);
    private final TxnRepo txns = mock(TxnRepo.class);
    private final SplitService splits = new SplitService(shares, settlements, mock(PersonRepo.class), txns);
    private final Person rahul = new Person();

    private void given(String owed, String paidForMe, String paid, String paidBack) {
        when(shares.totalOwed(rahul)).thenReturn(new BigDecimal(owed));
        when(txns.totalPaidBy(rahul)).thenReturn(new BigDecimal(paidForMe));
        when(settlements.totalPaid(rahul)).thenReturn(new BigDecimal(paid));
        when(settlements.totalPaidBack(rahul)).thenReturn(new BigDecimal(paidBack));
    }

    @Test
    void whatTheyPaidForMeComesOffWhatTheyOwe() {
        // I paid ₹1,000 for clothes (his share ₹500); he paid ₹400 for petrol (my share ₹200)
        given("500", "200", "0", "0");
        assertThat(splits.outstanding(rahul)).isEqualByComparingTo("300");
        // then he sends the difference
        given("500", "200", "300", "0");
        assertThat(splits.outstanding(rahul)).isEqualByComparingTo("0");
    }

    @Test
    void theBalanceGoesBelowZeroWhenIOweThemAndPayingBackClearsIt() {
        given("0", "150", "0", "0");
        assertThat(splits.outstanding(rahul)).isEqualByComparingTo("-150");
        given("0", "150", "0", "150");
        assertThat(splits.outstanding(rahul)).isEqualByComparingTo("0");
    }
}
