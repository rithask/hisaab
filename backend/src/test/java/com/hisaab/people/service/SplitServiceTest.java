package com.hisaab.people.service;

import com.hisaab.common.model.Direction;
import com.hisaab.people.model.SettlementDirection;
import com.hisaab.people.model.SettlementStatus;
import com.hisaab.accounts.model.Account;
import com.hisaab.people.model.Person;
import com.hisaab.people.model.Settlement;
import com.hisaab.people.repository.PersonRepository;
import com.hisaab.people.repository.SettlementRepository;
import com.hisaab.people.repository.SplitShareRepository;
import com.hisaab.transactions.model.Transaction;
import com.hisaab.transactions.repository.TransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SplitServiceTest {
    private final SplitShareRepository shares = mock(SplitShareRepository.class);
    private final SettlementRepository settlements = mock(SettlementRepository.class);
    private final TransactionRepository txns = mock(TransactionRepository.class);
    private final PersonRepository people = mock(PersonRepository.class);
    // events go straight to the service, as Spring does within a transaction
    private final SplitService[] self = new SplitService[1];
    private final SplitService splits = self[0] = new SplitService(shares, settlements, people, txns,
            e -> self[0].onBalancesChanged((BalancesChangedEvent) e));
    private final Person rahul = new Person();

    {
        rahul.setName("Rahul");
        when(people.findAll()).thenReturn(List.of(rahul));
        when(settlements.total(any(), eq(SettlementStatus.SUGGESTED), any())).thenReturn(BigDecimal.ZERO);
        when(settlements.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static Transaction money(Direction direction, String amount, String from) {
        Transaction transaction = new Transaction();
        transaction.setDirection(direction);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setCounterparty(from);
        transaction.setAccount(new Account());
        transaction.setOccurredAt(Instant.now());
        return transaction;
    }

    private Settlement suggested() {
        var captor = org.mockito.ArgumentCaptor.forClass(Settlement.class);
        verify(settlements).save(captor.capture());
        return captor.getValue();
    }

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

    @Test
    void theDifferenceFromSomeoneWithAnotherUpiNameIsOnlyMatchedOnceTheNameIsKnown() {
        given("500", "200", "0", "0"); // he owes ₹300
        Transaction upi = money(Direction.CREDIT, "250", "MUHAMMED FAZ");
        splits.suggestSettlement(upi);
        verify(settlements, never()).save(any()); // neither the name nor the amount says it's him
        // linking one payment by hand remembers the sender's name...
        Transaction first = money(Direction.CREDIT, "50", "MUHAMMED FAZ");
        var r = splits.record(rahul, SettlementDirection.IN, new BigDecimal("50"), null, first, null);
        assertThat(r.rememberedName()).isEqualTo("MUHAMMED FAZ");
        assertThat(rahul.getUpiName()).isEqualTo("MUHAMMED FAZ");
        // ...and isn't replaced by the next one
        assertThat(splits.record(rahul, SettlementDirection.IN, new BigDecimal("10"), null, money(Direction.CREDIT, "10", "SOMEONE ELSE"), null).rememberedName()).isNull();
    }

    @Test
    void moneyOutIsSuggestedAsPayingBackSomeoneIOwe() {
        given("0", "150", "0", "0"); // I owe him ₹150
        splits.suggestSettlement(money(Direction.DEBIT, "150", "UPI 4392"));
        Settlement settlement = suggested();
        assertThat(settlement.getDirection()).isEqualTo(SettlementDirection.OUT);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.SUGGESTED);
        assertThat(settlement.getAmount()).isEqualByComparingTo("150");
    }

    @Test
    void moneyInFromThemIsMatchedByNameWhateverTheAmount() {
        given("500", "200", "0", "0");
        splits.suggestSettlement(money(Direction.CREDIT, "1000", "RAHUL"));
        Settlement settlement = suggested();
        assertThat(settlement.getDirection()).isEqualTo(SettlementDirection.IN);
        assertThat(settlement.getAmount()).isEqualByComparingTo("300"); // only what's open
    }

    @Test
    void moneyOutIsLeftAloneWhenIOweNobody() {
        given("500", "0", "0", "0");
        splits.suggestSettlement(money(Direction.DEBIT, "500", "RAHUL"));
        verify(settlements, never()).save(any());
    }

    @Test
    void savingAPersonLooksAgainAtRecentMoney() {
        when(people.save(any())).thenAnswer(i -> i.getArgument(0));
        splits.savePerson(rahul);
        verify(txns).findUnsettledSince(any());
    }
}
