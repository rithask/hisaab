package com.hisaab.statements.repository;

import com.hisaab.statements.model.StatementImport;
import com.hisaab.statements.model.StatementImportLine;
import com.hisaab.transactions.model.Txn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface StatementImportLineRepository extends JpaRepository<StatementImportLine, Long> {
    List<StatementImportLine> findByStatementImportOrderByLineNo(StatementImport statementImport);

    /** Lines of completed imports that were added as, or merged into, these transactions. */
    @org.springframework.data.jpa.repository.Query("""
            select l from StatementImportLine l
            where l.createdTxn in :txns and l.statementImport.status = ImportStatus.IMPORTED
            """)
    List<StatementImportLine> findImportedFor(@org.springframework.data.repository.query.Param("txns") Collection<Txn> txns);
}
