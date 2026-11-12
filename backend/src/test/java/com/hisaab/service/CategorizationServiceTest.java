package com.hisaab.service;

import com.hisaab.domain.Category;
import com.hisaab.domain.CategoryVote;
import com.hisaab.domain.Enums.CategoryKind;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Txn;
import com.hisaab.repo.CategoryRuleRepo;
import com.hisaab.repo.CategoryVoteRepo;
import com.hisaab.repo.TxnRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CategorizationServiceTest {
    private final List<CategoryVote> store = new ArrayList<>();
    private CategorizationService cats;
    private Category groceries, shopping, food, salary;

    @BeforeEach
    void setUp() {
        CategoryVoteRepo votes = mock(CategoryVoteRepo.class);
        when(votes.save(any())).thenAnswer(i -> {
            CategoryVote v = i.getArgument(0);
            if (!store.contains(v)) store.add(v);
            return v;
        });
        when(votes.findByKeyTypeAndKey(any(), any())).thenAnswer(i -> store.stream()
                .filter(v -> v.getKeyType().equals(i.getArgument(0)) && v.getKey().equals(i.getArgument(1))).toList());
        when(votes.findByKeyType(any())).thenAnswer(i -> store.stream().filter(v -> v.getKeyType().equals(i.getArgument(0))).toList());
        CategoryRuleRepo rules = mock(CategoryRuleRepo.class);
        when(rules.findByMatchTypeAndPattern(any(), any())).thenReturn(Optional.empty());
        when(rules.findByMatchTypeOrderByPriorityDesc(any())).thenReturn(List.of());
        TxnRepo txns = mock(TxnRepo.class);
        when(txns.findSimilar(any(), any(), any())).thenReturn(List.of());
        cats = new CategorizationService(rules, txns, votes);
        groceries = cat(1, "Groceries", CategoryKind.EXPENSE);
        shopping = cat(2, "Shopping", CategoryKind.EXPENSE);
        food = cat(3, "Food & Dining", CategoryKind.EXPENSE);
        salary = cat(4, "Salary", CategoryKind.INCOME);
    }

    private static Category cat(long id, String name, CategoryKind kind) {
        Category c = new Category();
        c.setId(id);
        c.setName(name);
        c.setKind(kind);
        return c;
    }

    private static Txn txn(String payee, String amount, String description) {
        Txn t = new Txn();
        t.setDirection(Direction.DEBIT);
        t.setCounterparty(payee);
        t.setMerchantKey(Names.merchantKey(payee));
        t.setAmount(new BigDecimal(amount));
        t.setDescription(description);
        return t;
    }

    private Category suggested(Txn t) {
        cats.categorize(t);
        return t.getCategory();
    }

    @Test
    void theMajorityWinsSoOneOddChoiceDoesNotFlipAPayee() {
        cats.learn(txn("AMAZON", "900", null), shopping);
        cats.learn(txn("AMAZON", "1500", null), shopping);
        cats.learn(txn("AMAZON", "2000", null), shopping);
        cats.learn(txn("AMAZON", "300", null), groceries);
        Txn next = txn("AMAZON", "1200", null);
        assertThat(suggested(next)).isEqualTo(shopping);
        assertThat(next.isCategorySuggested()).isTrue();
    }

    @Test
    void whenTwoCategoriesAreCloseTheAmountDecides() {
        cats.learn(txn("AMAZON", "250", null), groceries);
        cats.learn(txn("AMAZON", "300", null), groceries);
        cats.learn(txn("AMAZON", "2500", null), shopping);
        cats.learn(txn("AMAZON", "3200", null), shopping);
        assertThat(suggested(txn("AMAZON", "220", null))).isEqualTo(groceries);
        assertThat(suggested(txn("AMAZON", "2800", null))).isEqualTo(shopping);
    }

    @Test
    void aPayeeWhoseWordsMostlyMatchALearnedOneSharesItsCategory() {
        cats.learn(txn("SWIGGY INSTAMART", "400", null), groceries);
        assertThat(suggested(txn("SWIGGY INSTAMART BLR", "350", null))).isEqualTo(groceries);
        assertThat(suggested(txn("SWIGGY DINEOUT", "350", null))).as("only one word in common").isNull();
    }

    @Test
    void peopleNeedAnExactMatch() {
        cats.learn(txn("RAHUL KUMAR", "500", "UPI/P2A/531234567890/RAHUL KUMAR"), food);
        assertThat(suggested(txn("RAHUL KUMAR S", "500", "UPI/P2A/600000000001/RAHUL KUMAR S"))).as("a different person").isNull();
        assertThat(suggested(txn("RAHUL KUMAR", "200", "UPI/P2A/600000000002/RAHUL KUMAR"))).isEqualTo(food);
        // a shop with a similar name is fine
        cats.learn(txn("BLUE TOKAI COFFEE", "300", "UPI/P2M/600000000003/BLUE TOKAI COFFEE"), food);
        assertThat(suggested(txn("BLUE TOKAI COFFEE ROASTERS", "300", "UPI/P2M/600000000004/BLUE TOKAI COFFEE ROASTERS"))).isEqualTo(food);
    }

    @Test
    void theSameUpiIdGetsTheSameCategoryWhateverTheNameSays() {
        cats.learn(txn("PAYTM*STORE 42", "120", "UPI/P2M/600000000005/teashop42@paytm"), food);
        assertThat(suggested(txn("SHARMA TEA CORNER", "80", "UPI/P2M/600000000006/teashop42@paytm"))).isEqualTo(food);
    }

    @Test
    void categoriesOnlyApplyToTheirDirection() {
        cats.learn(txn("ACME LTD", "50000", null), shopping);
        Txn in = txn("ACME LTD", "50000", null);
        in.setDirection(Direction.CREDIT);
        assertThat(suggested(in)).isNull();
    }

    @Test
    void wordsAreComparedAsSets() {
        assertThat(CategorizationService.jaccard(Names.tokens("swiggy instamart blr"), Names.tokens("swiggy instamart"))).isGreaterThanOrEqualTo(0.6);
        assertThat(CategorizationService.toPerson(txn("X", "1", "UPI/P2P/1/X"))).isTrue();
        assertThat(CategorizationService.toPerson(txn("X", "1", "UPI/P2M/1/X"))).isFalse();
        assertThat(CategorizationService.vpaOf(txn("Ravi", "1", "UPI/P2A/1/ravi.k@okaxis"))).isEqualTo("ravi.k@okaxis");
    }

    // ---- payments without a payee: remembered by the wording of their message -----------------------------

    private static Txn fromMessage(String text, Long formatId, String amount) {
        Txn t = txn(null, amount, null);
        com.hisaab.domain.RawEmail m = new com.hisaab.domain.RawEmail();
        m.setBodyText(text);
        if (formatId != null) {
            com.hisaab.domain.SmsTemplate f = new com.hisaab.domain.SmsTemplate();
            f.setId(formatId);
            m.setTemplate(f);
        }
        t.setRawEmail(m);
        return t;
    }

    @Test
    void aPaymentWithNoPayeeIsRememberedByItsWording() {
        Category cash = cat(5, "Cash", CategoryKind.EXPENSE);
        Txn atm = fromMessage("Rs.2000.00 withdrawn at ATM from A/c XX1234 on 03-Nov-26 10:15 am. Avl bal Rs 5,000", null, "2000");
        assertThat(cats.learnsByWording(atm)).isTrue();
        cats.learn(atm, cash);

        Txn next = fromMessage("Rs.500.00 withdrawn at ATM from A/c XX1234 on 11-Dec-26 7:02 pm. Avl bal Rs 4,500", null, "500");
        assertThat(suggested(next)).isEqualTo(cash);
        assertThat(next.isCategorySuggested()).isTrue();
        Txn other = fromMessage("Rs.500.00 debited for annual card fee on A/c XX1234 on 11-Dec-26", null, "500");
        assertThat(suggested(other)).isNull();
    }

    @Test
    void theLearnedFormatCarriesTheCategoryAcrossWordingItMerged() {
        Category charges = cat(6, "Bank charges", CategoryKind.EXPENSE);
        cats.learn(fromMessage("Rs 118 debited from A/c XX1234 towards SMS charges", 7L, "118"), charges);
        // different words, but read by the same learned format
        assertThat(suggested(fromMessage("Rs 59 debited from A/c XX1234 towards debit card fee", 7L, "59"))).isEqualTo(charges);
    }

    @Test
    void wordingOnlyCountsWhenThereIsNoPayee() {
        Category cash = cat(5, "Cash", CategoryKind.EXPENSE);
        cats.learn(fromMessage("Rs 100 paid via UPI on 03-Nov-26", 9L, "100"), cash);
        Txn named = fromMessage("Rs 100 paid via UPI on 04-Nov-26", 9L, "100");
        named.setCounterparty("ZOMATO");
        named.setMerchantKey(Names.merchantKey("ZOMATO"));
        assertThat(cats.learnsByWording(named)).isFalse();
        assertThat(suggested(named)).isNull();
    }

    @Test
    void wordingDropsWhatChangesBetweenMessages() {
        assertThat(CategorizationService.wording("Rs.2,000.00 withdrawn at ATM on 03-Nov-26 10:15 AM, Ref 123456."))
                .isEqualTo("# withdrawn at atm on # ref #");
        assertThat(CategorizationService.wording("Rs 500 on 03 Nov")).isNull();
    }
}
