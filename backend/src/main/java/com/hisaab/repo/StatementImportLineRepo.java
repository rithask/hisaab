package com.hisaab.repo;

import com.hisaab.domain.StatementImport;
import com.hisaab.domain.StatementImportLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StatementImportLineRepo extends JpaRepository<StatementImportLine, Long> {
    List<StatementImportLine> findByStatementImportOrderByLineNo(StatementImport statementImport);

    /** Lines of completed imports that were added as, or merged into, these transactions. */
    @org.springframework.data.jpa.repository.Query("""
            select l from StatementImportLine l
            where l.createdTxn in :txns and l.statementImport.status = com.hisaab.domain.Enums.ImportStatus.IMPORTED
            """)
    List<StatementImportLine> findImportedFor(@org.springframework.data.repository.query.Param("txns") java.util.Collection<com.hisaab.domain.Txn> txns);
}
