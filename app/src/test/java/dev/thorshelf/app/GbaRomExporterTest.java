package dev.thorshelf.app;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import static org.junit.Assert.*;

public class GbaRomExporterTest {
    private final byte[] bytes = new byte[]{4, 8, 15, 16, 23, 42};

    @Test public void copiesMatchingRomWithKnownOrUnknownProviderSize() throws Exception {
        for (long providerSize : new long[]{bytes.length, -1}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertEquals(bytes.length, GbaRomExporter.export(new ByteArrayInputStream(bytes), out,
                    providerSize, GbaTestData.sha1(bytes).toUpperCase(java.util.Locale.ROOT), bytes.length, null));
            assertArrayEquals(bytes, out.toByteArray());
        }
    }

    @Test public void rejectsSameLengthCorruptionByChecksum() throws Exception {
        byte[] corrupted = bytes.clone();
        corrupted[2] ^= 1;
        String checksum = GbaTestData.sha1(bytes);
        GbaRomExporter.InvalidRomException error = assertThrows(GbaRomExporter.InvalidRomException.class,
                () -> GbaRomExporter.export(new ByteArrayInputStream(corrupted), new ByteArrayOutputStream(),
                        bytes.length, checksum, bytes.length, null));
        assertTrue(error.getMessage().contains("checksum"));
    }

    @Test public void rejectsInvalidIdentityBeforeWriting() throws Exception {
        for (String checksum : new String[]{null, "", "00", "z".repeat(40)}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThrows(GbaRomExporter.InvalidRomException.class,
                    () -> GbaRomExporter.export(new ByteArrayInputStream(bytes), out, -1, checksum, bytes.length, null));
            assertEquals(0, out.size());
        }
        for (long size : new long[]{-1, 0, GbaRomExporter.MAX_ROM_BYTES + 1}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThrows(GbaRomExporter.InvalidRomException.class,
                    () -> GbaRomExporter.export(new ByteArrayInputStream(bytes), out, -1, "a".repeat(40), size, null));
            assertEquals(0, out.size());
        }
    }

    @Test public void rejectsProviderLengthMismatchBeforeWriting() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String checksum = GbaTestData.sha1(bytes);
        assertThrows(GbaRomExporter.InvalidRomException.class,
                () -> GbaRomExporter.export(new ByteArrayInputStream(bytes), out,
                        bytes.length + 1, checksum, bytes.length, null));
        assertEquals(0, out.size());
    }

    @Test public void rejectsShortEmptyAndOversizedStreamsEvenWithoutProviderSize() throws Exception {
        String checksum = GbaTestData.sha1(bytes);
        for (byte[] invalid : new byte[][]{new byte[0], Arrays.copyOf(bytes, 3), Arrays.copyOf(bytes, 10)}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThrows(GbaRomExporter.InvalidRomException.class,
                    () -> GbaRomExporter.export(new ByteArrayInputStream(invalid), out,
                            -1, checksum, bytes.length, null));
            assertTrue(out.size() <= bytes.length);
        }
    }

    @Test public void cancellationAfterAChunkStopsBeforeAcceptingCopy() throws Exception {
        byte[] large = new byte[300 * 1024];
        String checksum = GbaTestData.sha1(large);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GbaRomExporter.Progress progress = new GbaRomExporter.Progress() {
            boolean cancelled;
            @Override public boolean isCancelled() { return cancelled; }
            @Override public void onProgress(long copied, long total) { cancelled = true; }
        };
        assertThrows(InterruptedIOException.class, () -> GbaRomExporter.export(new ByteArrayInputStream(large),
                out, large.length, checksum, large.length, progress));
        assertTrue(out.size() > 0);
        assertTrue(out.size() < large.length);
    }
}
