package com.hisaab.accounts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountRepo extends JpaRepository<Account, Long> {
    List<Account> findByLast4AndArchivedFalse(String last4);

    List<Account> findByArchivedFalseOrderByTypeAscNameAsc();

    List<Account> findAllByOrderByArchivedAscTypeAscNameAsc();
}
