package dev.thorshelf.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class CxiExporterTest {
    private static final long TITLE = 0x0004000001234500L;
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void extractsIndexZeroRatherThanFirstRecordOrContentIdZero() throws Exception {
        byte[] executable = executable();
        Spec absent = new Spec(6, 0, new byte[21]);
        absent.included = false;
        Fixture fixture = fixture(TITLE, new Spec(7, 41, new byte[19]), absent,
                new Spec(0, 3, executable), new Spec(9, 2, new byte[17]));
        File source = write(fixture.bytes);
        File destination = destination();
        List<Integer> progress = new ArrayList<>();
        assertSame(destination, CxiExporter.export(source, destination,
                (percent, message) -> progress.add(percent)));
        assertArrayEquals(executable, Files.readAllBytes(destination.toPath()));
        assertArrayEquals(fixture.bytes, Files.readAllBytes(source.toPath()));
        assertEquals(Integer.valueOf(100), progress.get(progress.size() - 1));
        for (int i = 1; i < progress.size(); i++) assertTrue(progress.get(i) >= progress.get(i - 1));
    }

    @Test public void hashesTrailingRecordPaddingButExportsOnlyDeclaredNcchBytes() throws Exception {
        byte[] executable = executable();
        byte[] padded = Arrays.copyOf(executable, executable.length + 32);
        Arrays.fill(padded, executable.length, padded.length, (byte) 0xA5);
        File destination = destination();
        CxiExporter.export(write(fixture(TITLE, new Spec(0, 3, padded)).bytes), destination, null);
        assertArrayEquals(executable, Files.readAllBytes(destination.toPath()));
    }

    @Test public void updatesDlcSystemTitlesAndDemosRemainInstallablePackages() throws Exception {
        for (long category : new long[]{0x0004000EL, 0x0004008CL, 0x00040010L, 0x00040002L}) {
            Fixture fixture = fixture((category << 32) | 0x01234500L, new Spec(0, 3, executable()));
            assertFalse(CxiExporter.isBaseApplication(CiaValidator.inspect(write(fixture.bytes))));
            assertRejected(fixture, "Only base-game CIAs");
        }
    }

    @Test public void absentIndexZeroDoesNotExportAnotherExecutable() throws Exception {
        Spec absent = new Spec(0, 3, executable());
        absent.included = false;
        assertRejected(fixture(TITLE, absent, new Spec(1, 0, executable())), "content index 0");
    }

    @Test public void nonNcchAndDataOnlyNcchCannotBecomePlayableFiles() throws Exception {
        assertRejected(fixture(TITLE, new Spec(0, 3, new byte[0x400])), "not an NCCH executable");
        byte[] data = executable();
        data[0x18D] = 1;
        assertRejected(fixture(TITLE, new Spec(0, 3, data)), "not an NCCH executable");
    }

    @Test public void requiresMatchingTitleProgramAndExtendedHeaderIds() throws Exception {
        for (int field : new int[]{0x108, 0x118, 0x400}) {
            byte[] data = executable();
            data[field] ^= 1;
            ncchHashes(data);
            assertRejected(fixture(TITLE, new Spec(0, 3, data)), field == 0x400 ? "does not match" : "do not match");
        }
    }

    @Test public void requiresExecutableExtendedHeaderAndCode() throws Exception {
        byte[] noHeader = executable();
        put(noHeader, 0x180, 0, 4, true);
        assertRejected(fixture(TITLE, new Spec(0, 3, noHeader)), "extended header");
        byte[] noCode = executable();
        noCode[0xA00] = 'x';
        ncchHashes(noCode);
        assertRejected(fixture(TITLE, new Spec(0, 3, noCode)), "ExeFS code");
    }

    @Test public void encryptedOuterAndInnerContentAreRejected() throws Exception {
        Spec encrypted = new Spec(0, 3, executable());
        encrypted.encrypted = true;
        assertRejected(fixture(TITLE, encrypted), "still CIA-encrypted");
        byte[] data = executable();
        data[0x18F] = 0;
        assertRejected(fixture(TITLE, new Spec(0, 3, data)), "still NCCH-encrypted");
    }

    @Test public void verifiesAllIncludedContentsBeforeDiscardingOtherPartitions() throws Exception {
        Fixture fixture = fixture(TITLE, new Spec(0, 3, executable()), new Spec(7, 4, new byte[20]));
        fixture.bytes[fixture.bytes.length - 1] ^= 1;
        assertRejected(fixture, "Content 7 failed");
    }

    @Test public void recomputingCiaHashesDoesNotHideCorruptCodeOrMetadata() throws Exception {
        byte[] data = executable();
        data[0xC01] ^= 1;
        assertRejected(fixture(TITLE, new Spec(0, 3, data)), "NCCH ExeFS file 0 failed");
        Fixture fixture = fixture(TITLE, new Spec(0, 3, executable()));
        fixture.bytes[fixture.body + 0xA4] ^= 1;
        assertRejected(fixture, "metadata groups failed");
    }

    @Test public void outOfBoundsNcchCannotExtractAdjacentContent() throws Exception {
        byte[] data = executable();
        put(data, 0x104, 1000, 4, true);
        assertRejected(fixture(TITLE, new Spec(0, 3, data), new Spec(1, 4, new byte[0x200])), "exceeds its CIA record");
        data = executable();
        put(data, 0x1A0, 1000, 4, true);
        assertRejected(fixture(TITLE, new Spec(0, 3, data)), "region exceeds");
    }

    @Test public void cancellationDuringCopyRemovesPartialOutputAndPreservesSource() throws Exception {
        byte[] data = Arrays.copyOf(executable(), 1024 * 1024);
        put(data, 0x104, data.length / 0x200, 4, true);
        Fixture fixture = fixture(TITLE, new Spec(0, 3, data));
        File source = write(fixture.bytes);
        File destination = destination();
        CiaValidator.Progress progress = new CiaValidator.Progress() {
            boolean cancelled;
            @Override public void onProgress(int percent, String message) {
                if (message.equals("Creating playable CXI")) cancelled = true;
            }
            @Override public boolean isCancelled() { return cancelled; }
        };
        assertThrows(InterruptedIOException.class, () -> CxiExporter.export(source, destination, progress));
        assertFalse(destination.exists());
        assertArrayEquals(fixture.bytes, Files.readAllBytes(source.toPath()));
    }

    @Test public void sourceChangedAfterValidationIsDetectedAndPartialOutputRemoved() throws Exception {
        Fixture fixture = fixture(TITLE, new Spec(0, 3, executable()));
        File source = write(fixture.bytes);
        File destination = destination();
        IOException error = assertThrows(IOException.class, () -> CxiExporter.export(source, destination,
                (percent, message) -> {
                    if (!message.equals("Prepared CIA verified")) return;
                    try (RandomAccessFile file = new RandomAccessFile(source, "rw")) {
                        file.seek(fixture.content + 0xC01);
                        file.write(99);
                    } catch (IOException failure) { throw new AssertionError(failure); }
                }));
        assertTrue(error.getMessage(), error.getMessage().contains("changed during export"));
        assertFalse(destination.exists());
        assertTrue(source.exists());
    }

    @Test public void neverOverwritesSourceOrExistingDestination() throws Exception {
        Fixture fixture = fixture(TITLE, new Spec(0, 3, executable()));
        File source = write(fixture.bytes);
        File existing = write(new byte[]{42, 43});
        assertThrows(IOException.class, () -> CxiExporter.export(source, existing, null));
        assertThrows(IOException.class, () -> CxiExporter.export(source, source, null));
        assertArrayEquals(new byte[]{42, 43}, Files.readAllBytes(existing.toPath()));
        assertArrayEquals(fixture.bytes, Files.readAllBytes(source.toPath()));
    }

    private void assertRejected(Fixture fixture, String message) throws Exception {
        File source = write(fixture.bytes);
        File destination = destination();
        IOException error = assertThrows(IOException.class, () -> CxiExporter.export(source, destination, null));
        assertTrue(error.getMessage(), error.getMessage().contains(message));
        assertFalse(destination.exists());
        assertArrayEquals(fixture.bytes, Files.readAllBytes(source.toPath()));
    }

    private File write(byte[] bytes) throws IOException {
        File file = temporary.newFile();
        Files.write(file.toPath(), bytes);
        return file;
    }

    private File destination() throws IOException { return new File(temporary.newFolder(), "game.cxi"); }

    /** Structurally valid synthetic NCCH, with no game content, console keys or signatures. */
    private static byte[] executable() throws Exception {
        byte[] data = new byte[0xE00];
        data[0x100] = 'N'; data[0x101] = 'C'; data[0x102] = 'C'; data[0x103] = 'H';
        put(data, 0x104, data.length / 0x200, 4, true);
        put(data, 0x108, TITLE, 8, true);
        put(data, 0x112, 2, 2, true);
        put(data, 0x118, TITLE, 8, true);
        put(data, 0x180, 0x400, 4, true);
        data[0x18D] = 3;
        data[0x18F] = 4;
        put(data, 0x400, TITLE, 8, true);
        put(data, 0x1A0, 5, 4, true);
        put(data, 0x1A4, 2, 4, true);
        put(data, 0x1A8, 1, 4, true);
        byte[] codeName = {'.', 'c', 'o', 'd', 'e'};
        System.arraycopy(codeName, 0, data, 0xA00, codeName.length);
        put(data, 0xA0C, 0x200, 4, true);
        Arrays.fill(data, 0xC00, 0xE00, (byte) 0x5A);
        ncchHashes(data);
        return data;
    }

    private static void ncchHashes(byte[] data) throws Exception {
        System.arraycopy(sha(data, 0x200, 0x400), 0, data, 0x160, 32);
        System.arraycopy(sha(data, 0xC00, 0x200), 0, data, 0xBE0, 32);
        System.arraycopy(sha(data, 0xA00, 0x200), 0, data, 0x1C0, 32);
    }

    private static Fixture fixture(long title, Spec... specs) throws Exception {
        Fixture fixture = new Fixture();
        int tmd = 0x2040;
        fixture.body = tmd + 0x140;
        int groups = fixture.body + 0xC4;
        int records = groups + 64 * 36;
        int tmdSize = 0xB04 + specs.length * 48;
        fixture.content = align(tmd + tmdSize, 64);
        int contentSize = 0;
        for (Spec spec : specs) if (spec.included) contentSize = align(contentSize, 16) + spec.data.length;
        byte[] bytes = fixture.bytes = new byte[fixture.content + contentSize];
        put(bytes, 0, 0x2020, 4, true);
        put(bytes, 16, tmdSize, 4, true);
        put(bytes, 24, contentSize, 8, true);
        put(bytes, tmd, 0x10004, 4, false);
        bytes[fixture.body + 0x40] = 1;
        put(bytes, fixture.body + 0x4C, title, 8, false);
        put(bytes, fixture.body + 0x9C, 1057, 2, false);
        put(bytes, fixture.body + 0x9E, specs.length, 2, false);
        put(bytes, groups + 2, specs.length, 2, false);
        int cursor = 0;
        for (int i = 0; i < specs.length; i++) {
            Spec spec = specs[i];
            int record = records + i * 48;
            put(bytes, record, spec.id, 4, false);
            put(bytes, record + 4, spec.index, 2, false);
            put(bytes, record + 6, spec.encrypted ? 1 : 0, 2, false);
            put(bytes, record + 8, spec.data.length, 8, false);
            System.arraycopy(sha(spec.data, 0, spec.data.length), 0, bytes, record + 16, 32);
            if (spec.included) {
                bytes[32 + spec.index / 8] |= (byte) (0x80 >>> (spec.index % 8));
                cursor = align(cursor, 16);
                System.arraycopy(spec.data, 0, bytes, fixture.content + cursor, spec.data.length);
                cursor += spec.data.length;
            }
        }
        System.arraycopy(sha(bytes, records, specs.length * 48), 0, bytes, groups + 4, 32);
        System.arraycopy(sha(bytes, groups, 64 * 36), 0, bytes, fixture.body + 0xA4, 32);
        return fixture;
    }

    private static byte[] sha(byte[] bytes, int offset, int size) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(bytes, offset, size);
        return digest.digest();
    }

    private static int align(int value, int unit) { return (value + unit - 1) & -unit; }
    private static void put(byte[] bytes, int offset, long value, int size, boolean little) {
        for (int i = 0; i < size; i++) bytes[offset + (little ? i : size - 1 - i)] = (byte) (value >>> (i * 8));
    }

    private static final class Spec {
        final int index, id;
        final byte[] data;
        boolean included = true;
        boolean encrypted;
        Spec(int index, int id, byte[] data) { this.index = index; this.id = id; this.data = data; }
    }

    private static final class Fixture {
        byte[] bytes;
        int body, content;
    }
}
