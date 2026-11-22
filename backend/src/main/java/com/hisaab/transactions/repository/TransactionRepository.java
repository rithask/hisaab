package com.hisaab.transactions.repository;

import com.hisaab.common.model.Direction;
import com.hisaab.transactions.model.TransactionSource;
import com.hisaab.accounts.model.Account;
import com.hisaab.dataapi.model.ApiToken;
import com.hisaab.messages.model.InboxMessage;
import com.hisaab.people.model.Person;
import com.hisaab.transactions.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    /** Candidates for reconciling a statement against what's recorded (adjustments excluded). */
    @Query("""
            select t from Transaction t
            where t.account = :account and t.occurredAt between :from and :to and t.source <> :exclude
            order by t.occurredAt
            """)
    List<Transaction> findForReconcile(@Param("account") Account account, @Param("from") Instant from, @Param("to") Instant to,
                                       @Param("exclude") TransactionSource exclude);

    List<Transaction> findByRawEmail(InboxMessage rawEmail);

    List<Transaction> findByMerchantKeyAndSourceIn(String merchantKey, Collection<TransactionSource> sources);

    Optional<Transaction> findFirstByAccountAndReferenceNoAndAmountAndDirection(Account account, String referenceNo,
                                                                         BigDecimal amount, Direction direction);

    /**
     * The same transaction already recorded through the other channel (SMS vs email). Same account, amount and
     * direction within a time window, but a different source, so two genuine identical purchases reported
     * through the same channel are never merged.
     */
    @Query("""
            select t from Transaction t
            where t.account = :account and t.direction = :direction and t.amount = :amount
              and t.source = :otherSource and t.occurredAt between :from and :to
            order by t.occurredAt
            """)
    List<Transaction> findCrossChannelTwins(@Param("account") Account account, @Param("direction") Direction direction,
                                            @Param("amount") BigDecimal amount, @Param("otherSource") TransactionSource otherSource,
                                            @Param("from") Instant from, @Param("to") Instant to);

    /** Candidates for the other leg of a transfer between my own accounts. */
    @Query("""
            select t from Transaction t
            where t.account <> :account and t.direction = :direction and t.amount = :amount
              and t.linkedTxn is null and t.occurredAt between :from and :to
            order by t.occurredAt
            """)
    List<Transaction> findTransferCandidates(@Param("account") Account account, @Param("direction") Direction direction,
                                             @Param("amount") BigDecimal amount, @Param("from") Instant from,
                                             @Param("to") Instant to);

    @Query("""
            select coalesce(sum(case when t.direction = :credit then t.amount else -t.amount end), 0)
            from Transaction t
            where t.account = :account and t.occurredAt >= :since
            """)
    BigDecimal netSince(@Param("account") Account account, @Param("since") Instant since,
                        @Param("credit") Direction credit);

    /** {@link #netSince} for every account from its opening date, in one query: rows of (account id, net). */
    @Query("""
            select t.account.id, coalesce(sum(case when t.direction = :credit then t.amount else -t.amount end), 0)
            from Transaction t
            where t.account is not null and t.occurredAt >= t.account.openingDate
            group by t.account.id
            """)
    List<Object[]> netSinceOpeningByAccount(@Param("credit") Direction credit);

    default BigDecimal netSince(Account account, Instant since) {
        return netSince(account, since, Direction.CREDIT);
    }

    /** Net (credits - debits) of everything up to and including the given transaction. */
    @Query("""
            select coalesce(sum(case when t.direction = :credit then t.amount else -t.amount end), 0)
            from Transaction t
            where t.account = :account and t.occurredAt >= :since
              and (t.occurredAt < :at or (t.occurredAt = :at and t.id <= :id))
            """)
    BigDecimal netUpTo(@Param("account") Account account, @Param("since") Instant since,
                       @Param("at") Instant at, @Param("id") Long id, @Param("credit") Direction credit);

    default BigDecimal netUpTo(Account account, Instant since, Instant at, Long id) {
        return netUpTo(account, since, at, id, Direction.CREDIT);
    }

    @Query("""
            select coalesce(sum(t.amount), 0) from Transaction t
            where t.account = :account and t.direction = :direction and t.occurredAt >= :since
            """)
    BigDecimal sumSince(@Param("account") Account account, @Param("direction") Direction direction,
                        @Param("since") Instant since);

    @Query("""
            select t from Transaction t
            where t.merchantKey = :key and t.id <> :excludeId and t.transfer = false
              and (t.category is null or t.category.id <> :categoryId)
            """)
    List<Transaction> findSimilar(@Param("key") String merchantKey, @Param("excludeId") Long excludeId,
                                  @Param("categoryId") Long categoryId);

    List<Transaction> findByTwinRawEmail(InboxMessage email);

    Optional<Transaction> findByApiTokenAndRequestKey(ApiToken apiToken, String requestKey);

    /** My share of everything this person paid for. */
    @Query("select coalesce(sum(t.amount), 0) from Transaction t where t.paidBy = :person")
    BigDecimal totalPaidBy(@Param("person") Person person);

    /** {@link #totalPaidBy} for everyone, in one query: rows of (person id, total). */
    @Query("select t.paidBy.id, coalesce(sum(t.amount), 0) from Transaction t where t.paidBy is not null group by t.paidBy.id")
    List<Object[]> totalPaidByPerson();

    List<Transaction> findByPaidByOrderByOccurredAtDesc(Person person);

    boolean existsByPaidBy(Person person);

    /** Recent money in or out of my accounts that no repayment (or "no") is linked to. */
    @Query("""
            select t from Transaction t
            where t.occurredAt >= :since and t.transfer = false and t.paidBy is null and t.account is not null
              and not exists (select s from Settlement s where s.txn = t)
              and not exists (select x from SplitShare x where x.txn = t)
            order by t.occurredAt
            """)
    List<Transaction> findUnsettledSince(@Param("since") Instant since);

    /** Recent money one way that isn't a repayment yet (a "no" doesn't count): for picking one by hand. */
    @Query("""
            select t from Transaction t
            where t.occurredAt >= :since and t.direction = :direction and t.transfer = false and t.paidBy is null
              and t.account is not null
              and not exists (select s from Settlement s where s.txn = t and s.status <> SettlementStatus.REJECTED)
              and not exists (select x from SplitShare x where x.txn = t)
            order by t.occurredAt desc
            """)
    List<Transaction> findOpenSince(@Param("since") Instant since, @Param("direction") Direction direction);
}
