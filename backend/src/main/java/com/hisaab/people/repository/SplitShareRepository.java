package com.hisaab.people.repository;

import com.hisaab.people.model.Person;
import com.hisaab.people.model.SplitShare;
import com.hisaab.transactions.model.Txn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface SplitShareRepository extends JpaRepository<SplitShare, Long> {
    List<SplitShare> findByTxn(Txn txn);

    /** Every share of these transactions in one query (for a page of rows). */
    List<SplitShare> findByTxnInOrderById(Collection<Txn> txns);

    List<SplitShare> findByPersonOrderByTxnOccurredAtDesc(Person person);

    void deleteByTxn(Txn txn);

    @Query("select coalesce(sum(s.amount), 0) from SplitShare s where s.person = :person")
    BigDecimal totalOwed(@Param("person") Person person);

    /** {@link #totalOwed} for everyone, in one query: rows of (person id, total). */
    @Query("select s.person.id, coalesce(sum(s.amount), 0) from SplitShare s group by s.person.id")
    List<Object[]> totalOwedByPerson();

    @Query("select coalesce(sum(s.amount), 0) from SplitShare s where s.txn = :txn")
    BigDecimal totalForTxn(@Param("txn") Txn txn);
}
