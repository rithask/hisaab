package com.hisaab.mail;

import com.hisaab.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StatementStorageTest {
    @TempDir
    Path root;

    private StatementStorage storage() {
        return new StatementStorage(new AppProperties(null, null, new AppProperties.Storage(root.toString()), null, null));
    }

    @Test
    void storesPathsRelativeToTheStorageFolder() throws Exception {
        var s = storage();
        Path saved = s.save(null, java.time.Instant.parse("2026-11-01T00:00:00Z"), "stmt.pdf", new byte[]{1});
        String stored = s.toStored(saved);
        assertFalse(Path.of(stored).isAbsolute());
        assertEquals(saved, s.resolve(stored));
    }

    @Test
    void absolutePathsFromAnotherMachineAreReRooted() {
        Path p = storage().resolve("/Users/someone/app/backend/./data/statements/unassigned/2026-11_x.pdf");
        assertEquals(root.resolve("statements/unassigned/2026-11_x.pdf"), p);
    }

    @Test
    void pathsOutsideStorageAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> storage().resolve("../../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> storage().resolve("/etc/passwd"));
    }
}
