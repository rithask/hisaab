package com.hisaab.repo;

import com.hisaab.domain.Enums.SettlementStatus;
import com.hisaab.domain.Person;
import com.hisaab.domain.Settlement;
import com.hisaab.domain.Txn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface SettlementRepo extends JpaRepository<Settlement, Long> {
    List<Settlement> findByPersonOrderBySettledAtDesc(Person person);

    List<Settlement> findByStatusOrderBySettledAtDesc(SettlementStatus status);

    List<Settlement> findByTxn(Txn txn);

    long countByStatus(SettlementStatus status);

    @Query("""
            select coalesce(sum(s.amount), 0) from Settlement s
            where s.person = :person and s.status = :status
            """)
    BigDecimal totalPaid(@Param("person") Person person, @Param("status") SettlementStatus status);

    default BigDecimal totalPaid(Person person) {
        return totalPaid(person, SettlementStatus.CONFIRMED);
    }
}
