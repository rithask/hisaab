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
}
