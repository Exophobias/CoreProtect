package net.coreprotect.consumer.process;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class EntityUuidConflictReporterTest {
    @Test void warnsImmediatelyThenSummarizesBothKindsWithoutRepeatingEmptyWarnings() {
        AtomicLong clock = new AtomicLong();
        List<String> warnings = new ArrayList<>();
        EntityUuidConflictReporter reporter = new EntityUuidConflictReporter(clock::get, warnings::add);
        UUID first = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID last = UUID.fromString("22222222-2222-2222-2222-222222222222");
        reporter.flush();
        assertTrue(warnings.isEmpty());
        reporter.record(false, first);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("1 entity interaction record(s)"));
        reporter.record(false, first);
        reporter.record(true, last);
        clock.set(TimeUnit.MINUTES.toNanos(5) - 1);
        reporter.flush();
        assertEquals(1, warnings.size());
        clock.incrementAndGet();
        reporter.flush();
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(1).contains("1 entity interaction record(s) and 1 entity removal record(s)"));
        assertTrue(warnings.get(1).contains(last.toString()));
        reporter.flush();
        assertEquals(2, warnings.size());
    }
}
