package com.hisaab.repo;

import com.hisaab.domain.Account;
import com.hisaab.domain.Enums.Direction;
import com.hisaab.domain.Txn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TxnRepo extends JpaRepository<Txn, Long>, JpaSpecificationExecutor<Txn> {

    /** Candidates for reconciling a statement against what's recorded (adjustments excluded). */
    @Query("""
            select t from Txn t
            where t.account = :account and t.occurredAt between :from and :to and t.source <> :exclude
            order by t.occurredAt
            """)
    List<Txn> findForReconcile(@Param("account") Account account, @Param("from") Instant from, @Param("to") Instant to,
                               @Param("exclude") com.hisaab.domain.Enums.TxnSource exclude);

    List<Txn> findByRawEmail(com.hisaab.domain.RawEmail rawEmail);

    List<Txn> findByMerchantKeyAndSourceIn(String merchantKey, java.util.Collection<com.hisaab.domain.Enums.TxnSource> sources);

    Optional<Txn> findFirstByAccountAndReferenceNoAndAmountAndDirection(Account account, String referenceNo,
                                                                         BigDecimal amount, Direction direction);

    /**
     * The same transaction already recorded through the other channel (SMS vs email). Same account, amount and
     * direction within a time window, but a different source, so two genuine identical purchases reported
     * through the same channel are never merged.
     */
    @Query("""
            select t from Txn t
            where t.account = :account and t.direction = :direction and t.amount = :amount
              and t.source = :otherSource and t.occurredAt between :from and :to
            order by t.occurredAt
            """)
    List<Txn> findCrossChannelTwins(@Param("account") Account account, @Param("direction") Direction direction,
                                    @Param("amount") BigDecimal amount, @Param("otherSource") com.hisaab.domain.Enums.TxnSource otherSource,
                                    @Param("from") Instant from, @Param("to") Instant to);

    /** Candidates for the other leg of a transfer between my own accounts. */
    @Query("""
            select t from Txn t
            where t.account <> :account and t.direction = :direction and t.amount = :amount
              and t.linkedTxn is null and t.occurredAt between :from and :to
            order by t.occurredAt
            """)
    List<Txn> findTransferCandidates(@Param("account") Account account, @Param("direction") Direction direction,
                                     @Param("amount") BigDecimal amount, @Param("from") Instant from,
                                     @Param("to") Instant to);

    @Query("""
            select coalesce(sum(case when t.direction = :credit then t.amount else -t.amount end), 0)
            from Txn t
            where t.account = :account and t.occurredAt >= :since
            """)
    BigDecimal netSince(@Param("account") Account account, @Param("since") Instant since,
                        @Param("credit") Direction credit);

    default BigDecimal netSince(Account account, Instant since) {
        return netSince(account, since, Direction.CREDIT);
    }

    /** Net (credits - debits) of everything up to and including the given transaction. */
    @Query("""
            select coalesce(sum(case when t.direction = :credit then t.amount else -t.amount end), 0)
            from Txn t
            where t.account = :account and t.occurredAt >= :since
              and (t.occurredAt < :at or (t.occurredAt = :at and t.id <= :id))
            """)
    BigDecimal netUpTo(@Param("account") Account account, @Param("since") Instant since,
                       @Param("at") Instant at, @Param("id") Long id, @Param("credit") Direction credit);

    default BigDecimal netUpTo(Account account, Instant since, Instant at, Long id) {
        return netUpTo(account, since, at, id, Direction.CREDIT);
    }

    @Query("""
            select coalesce(sum(t.amount), 0) from Txn t
            where t.account = :account and t.direction = :direction and t.occurredAt >= :since
            """)
    BigDecimal sumSince(@Param("account") Account account, @Param("direction") Direction direction,
                        @Param("since") Instant since);

    @Query("""
            select t from Txn t
            where t.merchantKey = :key and t.id <> :excludeId and t.transfer = false
              and (t.category is null or t.category.id <> :categoryId)
            """)
    List<Txn> findSimilar(@Param("key") String merchantKey, @Param("excludeId") Long excludeId,
                          @Param("categoryId") Long categoryId);

    List<Txn> findByTwinRawEmail(com.hisaab.domain.RawEmail email);
}
