package com.hisaab.accounts.repository;

import com.hisaab.accounts.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findByLast4AndArchivedFalse(String last4);

    List<Account> findByArchivedFalseOrderByTypeAscNameAsc();

    List<Account> findAllByOrderByArchivedAscTypeAscNameAsc();
}
