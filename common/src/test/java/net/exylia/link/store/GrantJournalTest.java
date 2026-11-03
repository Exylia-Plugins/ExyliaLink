package net.exylia.link.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrantJournalTest {

    private static final long DAY = 24L * 60 * 60 * 1000;

    @TempDir
    Path folder;

    @Test
    void aRecordedGrantSurvivesARestart() throws Exception {
        Path file = folder.resolve("grants.log");
        try (GrantJournal journal = new GrantJournal(file)) {
            assertFalse(journal.contains("rg_1"));
            journal.record("rg_1");
            assertTrue(journal.contains("rg_1"));
        }
        try (GrantJournal reopened = new GrantJournal(file)) {
            assertTrue(reopened.contains("rg_1"));
            assertFalse(reopened.contains("rg_2"));
        }
    }

    @Test
    void compactionDropsWhatIsPastNinetyDaysAndDuplicates() throws Exception {
        Path file = folder.resolve("grants.log");
        AtomicLong now = new AtomicLong(1_000 * DAY);
        try (GrantJournal journal = new GrantJournal(file, now::get)) {
            journal.record("old");
            now.addAndGet(10 * DAY);
            journal.record("recent");
            journal.record("recent");
            now.addAndGet(85 * DAY);
            assertFalse(journal.contains("old"), "past the retention even before compaction");
            assertTrue(journal.contains("recent"));
            journal.compact();
            assertEquals(1, journal.size());
        }
        assertEquals(1, Files.readAllLines(file).size());
        try (GrantJournal reopened = new GrantJournal(file, now::get)) {
            assertTrue(reopened.contains("recent"));
        }
    }

    @Test
    void aTornLastLineAndDamagedLinesAreDroppedWithoutLosingTheRest() throws Exception {
        Path file = folder.resolve("grants.log");
        long now = System.currentTimeMillis();
        Files.writeString(file, now + " rg_ok\nnot-a-number rg_bad\n\n" + now + "\n" + now + " rg_half", StandardCharsets.UTF_8);
        try (GrantJournal journal = new GrantJournal(file)) {
            assertTrue(journal.contains("rg_ok"));
            assertFalse(journal.contains("rg_bad"));
            assertFalse(journal.contains("rg_half"), "cut short by a crash: never acknowledged");
            journal.record("rg_next");
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.endsWith("\n"));
        assertEquals(2, text.lines().count(), "rewritten clean, then the new line appended on its own line");
        assertFalse(Files.exists(folder.resolve("grants.log.tmp")));
    }

    @Test
    void aLeftoverTemporaryFileFromACrashedCompactionIsHarmless() throws Exception {
        Path file = folder.resolve("grants.log");
        long now = System.currentTimeMillis();
        Files.writeString(file, now + " rg_1\n");
        Files.writeString(folder.resolve("grants.log.tmp"), "garbage");
        try (GrantJournal journal = new GrantJournal(file)) {
            assertTrue(journal.contains("rg_1"));
        }
    }

    @Test
    void refusesIdsThatWouldBreakTheFormat() throws Exception {
        try (GrantJournal journal = new GrantJournal(folder.resolve("grants.log"))) {
            assertThrows(IllegalArgumentException.class, () -> journal.record("a\nb"));
        }
    }
}
