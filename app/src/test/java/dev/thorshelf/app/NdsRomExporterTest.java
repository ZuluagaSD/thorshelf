package dev.thorshelf.app;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/** Every ROM in these fixtures is synthetic bytes, never game data. */
public class NdsRomExporterTest {
    @Test public void copiesDirectNdsWithoutChangingInput() throws Exception {
        byte[] original = payload(512);
        assertArrayEquals(original, export(original, "Demo.NDS"));
    }

    @Test public void extractsNestedRomAndChecksOtherMembers() throws Exception {
        byte[] rom = payload(8192);
        byte[] archive = zip(new String[]{"demo/", "demo/README.txt", "demo/Demo.NDS"},
                new byte[][]{new byte[0], payload(64), rom}, false);
        assertArrayEquals(rom, export(archive, "demo.zip"));
    }

    @Test public void supportsStoredEntriesAndZipComments() throws Exception {
        byte[] rom = payload(1024);
        assertArrayEquals(rom, export(zip(new String[]{"Demo.nds"}, new byte[][]{rom}, true), "demo.zip"));
    }

    @Test public void extractsSingleNoIntroNamedRomFromArchiveCatalogZip() throws Exception {
        String title = "Pokemon - Black Version (USA, Europe) (NDSi Enhanced)";
        byte[] rom = payload(1024 * 1024);
        for (boolean stored : new boolean[] {false, true}) {
            byte[] archive = zip(new String[] {title + ".nds"}, new byte[][] {rom}, stored);
            byte[] originalArchive = archive.clone();
            assertArrayEquals(rom, export(archive, title + ".zip"));
            assertArrayEquals(originalArchive, archive);
        }
    }

    @Test public void directoryValidationWorksAfterTailBufferWraps() throws Exception {
        byte[] rom = payload(3 * 1024 * 1024);
        assertArrayEquals(rom, export(zip(new String[]{"Demo.nds"}, new byte[][]{rom}, false), "demo.zip"));
    }

    @Test public void rejectsMultipleRomsAndArchivesWithoutRoms() throws Exception {
        rejects(zip(new String[]{"one.nds", "two.NDS"}, new byte[][]{payload(64), payload(64)}, false),
                "multiple DS ROMs");
        rejects(zip(new String[]{"README.txt"}, new byte[][]{payload(64)}, false), "does not contain");
        rejects(zip(new String[]{"empty.nds"}, new byte[][]{new byte[0]}, false), "empty DS ROM");
    }

    @Test public void rejectsTraversalAbsoluteAndWindowsMemberPaths() throws Exception {
        for (String name : new String[]{"../demo.nds", "a/../../demo.nds", "/demo.nds", "C:/demo.nds",
                "a\\demo.nds", "a//demo.nds", "./demo.nds"}) {
            rejects(zip(new String[]{name}, new byte[][]{payload(64)}, false), "unsafe");
        }
    }

    @Test public void rejectsTruncatedPayloadAndMissingEndRecord() throws Exception {
        byte[] archive = zip(new String[]{"demo.nds"}, new byte[][]{payload(8192)}, false);
        rejects(Arrays.copyOf(archive, archive.length / 2), "damaged or incomplete");
        rejects(Arrays.copyOf(archive, archive.length - 12), "directory");
        int central = signature(archive, 0x02014b50);
        rejects(Arrays.copyOf(archive, central), "directory");
    }

    @Test public void rejectsCorruptRomCrcAndCorruptNonRomCrc() throws Exception {
        for (String member : new String[]{"demo.nds", "README.txt"}) {
            byte[] archive = zip(new String[]{member, "another.txt"}, new byte[][]{payload(256), payload(32)}, true);
            int payloadOffset = 30 + member.length();
            archive[payloadOffset + 8] ^= 1;
            rejects(archive, "damaged or incomplete");
        }
    }

    @Test public void rejectsDirectoryMetadataMismatchAndUnlistedMembers() throws Exception {
        byte[] archive = zip(new String[]{"demo.nds"}, new byte[][]{payload(128)}, false);
        int central = signature(archive, 0x02014b50);
        archive[central + 24] ^= 1;
        rejects(archive, "does not match");
        archive = zip(new String[]{"demo.nds"}, new byte[][]{payload(128)}, false);
        int end = signature(archive, 0x06054b50);
        archive[end + 10] = 2;
        rejects(archive, "directory");
    }

    @Test public void rejectsOversizedRomBeforeWritingPastLimit() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] archive = zip(new String[]{"demo.nds"}, new byte[][]{payload(2048)}, false);
        try {
            NdsRomExporter.export(new ByteArrayInputStream(archive), output, "demo.zip", archive.length,
                    null, 1024, 4096, 10);
            fail("Expected an output limit");
        } catch (NdsRomExporter.InvalidRomException expected) {
            assertTrue(expected.getMessage().contains("size limit"));
            assertTrue(output.size() <= 1024);
        }
    }

    @Test public void enforcesLimitsForSkippedMembersAndEntryCount() throws Exception {
        byte[] archive = zip(new String[]{"demo.nds", "huge.txt"}, new byte[][]{payload(512), payload(2048)}, false);
        rejectsWithLimits(archive, 1024, 1536, 10, "size limit");
        archive = zip(new String[]{"demo.nds", "one.txt", "two.txt"},
                new byte[][]{payload(128), new byte[0], new byte[0]}, false);
        rejectsWithLimits(archive, 1024, 4096, 2, "too many");
    }

    @Test public void cancellationStopsDuringExtraction() throws Exception {
        String title = "Pokemon - White Version (USA, Europe) (NDSi Enhanced)";
        byte[] archive = zip(new String[]{title + ".nds"}, new byte[][]{payload(256 * 1024)}, false);
        AtomicBoolean cancelled = new AtomicBoolean();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            NdsRomExporter.export(new ByteArrayInputStream(archive), output, title + ".zip", archive.length,
                    new NdsRomExporter.Progress() {
                        public boolean isCancelled() { return cancelled.get(); }
                        public void onProgress(long copied, long total) { cancelled.set(true); }
                    });
            fail("Expected cancellation");
        } catch (InterruptedIOException expected) {
            assertTrue(output.size() < 256 * 1024);
        }
    }

    @Test public void rejectsWrongSourceTypeTruncatedDirectRomAndEmptyInput() throws Exception {
        byte[] bytes = payload(512);
        try {
            export(bytes, "demo.cia");
            fail("Expected a source format error");
        } catch (NdsRomExporter.InvalidRomException expected) {
            assertTrue(expected.getMessage().contains("completed .nds"));
        }
        try {
            NdsRomExporter.export(new ByteArrayInputStream(bytes), new ByteArrayOutputStream(),
                    "demo.nds", bytes.length + 1, null);
            fail("Expected a truncated source error");
        } catch (NdsRomExporter.InvalidRomException expected) {
            assertTrue(expected.getMessage().contains("incomplete"));
        }
        try {
            export(new byte[0], "empty.nds");
            fail("Expected an empty source error");
        } catch (NdsRomExporter.InvalidRomException expected) {
            assertTrue(expected.getMessage().contains("empty"));
        }
        rejects(payload(64), "directory");
    }

    private static byte[] export(byte[] source, String name) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NdsRomExporter.export(new ByteArrayInputStream(source), output, name, source.length, null);
        return output.toByteArray();
    }

    private static void rejects(byte[] archive, String message) throws IOException {
        try {
            export(archive, "demo.zip");
            fail("Expected archive rejection: " + message);
        } catch (NdsRomExporter.InvalidRomException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
    }

    private static void rejectsWithLimits(byte[] archive, long maxRom, long maxTotal, int maxEntries,
            String message) throws IOException {
        try {
            NdsRomExporter.export(new ByteArrayInputStream(archive), new ByteArrayOutputStream(),
                    "demo.zip", archive.length, null, maxRom, maxTotal, maxEntries);
            fail("Expected resource limit");
        } catch (NdsRomExporter.InvalidRomException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
    }

    static byte[] zip(String[] names, byte[][] payloads, boolean stored) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.setComment("Synthetic fixture");
            for (int i = 0; i < names.length; i++) {
                ZipEntry entry = new ZipEntry(names[i]);
                if (stored) {
                    CRC32 crc = new CRC32();
                    crc.update(payloads[i]);
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(payloads[i].length);
                    entry.setCompressedSize(payloads[i].length);
                    entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry);
                zip.write(payloads[i]);
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return bytes;
    }

    private static int signature(byte[] bytes, int value) {
        for (int i = 0; i <= bytes.length - 4; i++) {
            int found = (bytes[i] & 255) | (bytes[i + 1] & 255) << 8
                    | (bytes[i + 2] & 255) << 16 | (bytes[i + 3] & 255) << 24;
            if (found == value) return i;
        }
        throw new AssertionError("Signature not found");
    }
}
