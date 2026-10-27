package dev.thorshelf.app;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/** Storage errors must state what remains to free, without weakening the workspace budget. */
public class PreparationStorageTest {
    private static final long MIB = 1L << 20;
    private static final long GIB = 1L << 30;
    private static final long RESERVE = 64 * MIB;

    @Test public void reportsAdditionalSpaceAlongsideRequiredAndAvailableTotals() throws Exception {
        String message = failure(640 * MIB, 4, 960 * MIB);

        assertEquals("Not enough temporary storage. Free another 1.7 GiB and retry "
                + "(2.6 GiB needed; 960.0 MiB available). The original download is safe.", message);
    }

    @Test public void exactWorkspaceBudgetStillAllowsPreparation() throws Exception {
        checkSpace(640 * MIB, 4, 4 * 640 * MIB + RESERVE);
        checkSpace(GIB, 1, GIB + RESERVE);
    }

    @Test public void oneByteShortfallIsVisibleAndDoesNotRoundToZero() throws Exception {
        String message = failure(GIB, 1, GIB + RESERVE - 1);

        assertTrue(message, message.contains("Free another 1 B and retry"));
    }

    @Test public void fractionalUnitsRoundTheDeficitUpAndAvailableSpaceDown() throws Exception {
        String message = failure(0, 1, RESERVE - 1025);

        assertTrue(message, message.contains("Free another 1.1 KiB and retry"));
        assertTrue(message, message.contains("64.0 MiB needed; 63.9 MiB available"));
    }

    @Test public void negativeUsableSpaceIsReportedAsZero() throws Exception {
        assertEquals("Not enough temporary storage. Free another 64.0 MiB and retry "
                + "(64.0 MiB needed; 0 B available). The original download is safe.", failure(0, 1, -1));
    }

    @Test public void oversizedWorkspaceBudgetSaturatesWithoutWrappingTheDeficit() throws Exception {
        checkSpace(Long.MAX_VALUE, 4, Long.MAX_VALUE);
        String message = failure(Long.MAX_VALUE, 4, Long.MAX_VALUE - 1);

        assertTrue(message, message.contains("Free another 1 B and retry"));
        assertTrue(message, message.contains("8589934592.0 GiB needed; 8589934591.9 GiB available"));
        assertFalse(message, message.contains("-"));
    }

    private static String failure(long inputSize, int copies, long available) throws Exception {
        try {
            checkSpace(inputSize, copies, available);
            throw new AssertionError("Expected insufficient storage");
        } catch (InvocationTargetException error) {
            assertTrue(error.getCause() instanceof IOException);
            return error.getCause().getMessage();
        }
    }

    private static void checkSpace(long inputSize, int copies, long available) throws Exception {
        File directory = new File("synthetic-storage-check") {
            @Override public long getUsableSpace() { return available; }
        };
        Method requireSpace = PreparationService.class.getDeclaredMethod("requireSpace", File.class, long.class, int.class);
        requireSpace.setAccessible(true);
        requireSpace.invoke(null, directory, inputSize, copies);
    }
}
