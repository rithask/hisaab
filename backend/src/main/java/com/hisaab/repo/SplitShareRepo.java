package com.hisaab.repo;

import com.hisaab.domain.Person;
import com.hisaab.domain.SplitShare;
import com.hisaab.domain.Txn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface SplitShareRepo extends JpaRepository<SplitShare, Long> {
    List<SplitShare> findByTxn(Txn txn);

    List<SplitShare> findByPersonOrderByTxnOccurredAtDesc(Person person);

    void deleteByTxn(Txn txn);

    @Query("select coalesce(sum(s.amount), 0) from SplitShare s where s.person = :person")
    BigDecimal totalOwed(@Param("person") Person person);

    @Query("select coalesce(sum(s.amount), 0) from SplitShare s where s.txn = :txn")
    BigDecimal totalForTxn(@Param("txn") Txn txn);
}
