package com.hisaab.repo;

import com.hisaab.domain.Enums.SettlementDirection;
import com.hisaab.domain.Enums.SettlementStatus;
import com.hisaab.domain.Person;
import com.hisaab.domain.Settlement;
import com.hisaab.domain.Txn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface SettlementRepo extends JpaRepository<Settlement, Long> {
    List<Settlement> findByPersonOrderBySettledAtDesc(Person person);

    List<Settlement> findByStatusOrderBySettledAtDesc(SettlementStatus status);

    List<Settlement> findByTxn(Txn txn);

    /** Every repayment linked to these transactions in one query (for a page of rows). */
    List<Settlement> findByTxnInOrderById(Collection<Txn> txns);

    long countByStatus(SettlementStatus status);

    @Query("""
            select coalesce(sum(s.amount), 0) from Settlement s
            where s.person = :person and s.status = :status and s.direction = :direction
            """)
    BigDecimal total(@Param("person") Person person, @Param("status") SettlementStatus status,
                     @Param("direction") SettlementDirection direction);

    /** {@link #total} for everyone with this status, in one query: rows of (person id, direction, total). */
    @Query("""
            select s.person.id, s.direction, coalesce(sum(s.amount), 0) from Settlement s
            where s.status = :status group by s.person.id, s.direction
            """)
    List<Object[]> totalsByPerson(@Param("status") SettlementStatus status);

    /** What they've paid me back (confirmed). */
    default BigDecimal totalPaid(Person person) {
        return total(person, SettlementStatus.CONFIRMED, SettlementDirection.IN);
    }

    /** What I've paid them back (confirmed). */
    default BigDecimal totalPaidBack(Person person) {
        return total(person, SettlementStatus.CONFIRMED, SettlementDirection.OUT);
    }
}
