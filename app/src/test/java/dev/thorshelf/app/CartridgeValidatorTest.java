package dev.thorshelf.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class CartridgeValidatorTest {
    private static final long TITLE = 0x0004000001234500L;
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void verifiesRealContainerWithSparseIndicesUnorderedRecordsAndNonzeroMainContentId() throws Exception {
        Spec[] contents = {new Spec(7, 0, dataPartition(7)), new Spec(0, 3, executable()),
                new Spec(2, 99, dataPartition(2))};
        byte[] cia = cia(TITLE, contents);
        byte[] cartridge = cartridge(contents);
        File source = write(cia);
        File output = write(cartridge);
        List<Integer> progress = new ArrayList<>();
        CartridgeValidator.validate(source, output, (percent, message) -> progress.add(percent));
        assertArrayEquals(cia, Files.readAllBytes(source.toPath()));
        assertArrayEquals(cartridge, Files.readAllBytes(output.toPath()));
        assertEquals(Integer.valueOf(100), progress.get(progress.size() - 1));
        for (int i = 1; i < progress.size(); i++) assertTrue(progress.get(i) >= progress.get(i - 1));
    }

    @Test public void trimmedCartridgeMayBeSmallerThanDeclaredCapacity() throws Exception {
        Spec[] contents = {new Spec(0, 3, executable())};
        byte[] output = cartridge(contents);
        put(output, 0x104, (128L * 1024 * 1024) / 0x200, 4, true);
        CartridgeValidator.validate(write(cia(TITLE, contents)), write(output), null);
    }

    @Test public void rejectsRenamedNcchInvalidFlagsAndWrongTitle() throws Exception {
        Spec[] contents = {new Spec(0, 3, executable())};
        reject(cia(TITLE, contents), executable(), "header is incomplete");
        for (int field : new int[]{0x100, 0x108, 0x18C, 0x18D, 0x18E, 0x110, 0x118}) {
            byte[] output = cartridge(contents);
            output[field] = (byte) (field == 0x18D ? 0 : output[field] ^ 2);
            reject(cia(TITLE, contents), output, null);
        }
    }

    @Test public void rejectsMissingUnexpectedOverlappingAndOutOfBoundsPartitions() throws Exception {
        Spec[] contents = {new Spec(0, 3, executable()), new Spec(2, 88, dataPartition(2))};
        byte[] cia = cia(TITLE, contents);
        byte[] output = cartridge(contents);
        put(output, 0x134, 0, 4, true); // Existing slot two has no size.
        reject(cia, output, "bounds");
        output = cartridge(contents);
        put(output, 0x128, 0x20, 4, true); // An unexpected slot one.
        put(output, 0x12C, 1, 4, true);
        reject(cia, output, "unexpected partition");
        output = cartridge(contents);
        put(output, 0x130, 0x20, 4, true); // Slot two overlaps the executable.
        reject(cia, output, "bounds");
        output = cartridge(contents);
        put(output, 0x130, 0xFFFFFFFFL, 4, true);
        reject(cia, output, "bounds");
        output = cartridge(contents);
        output[0x198] = 1; // Absent slot one still claims a title ID.
        reject(cia, output, "unexpected partition");
    }

    @Test public void partitionSlotsAreTmdIndicesNotContentIdsOrRecordOrder() throws Exception {
        Spec[] contents = {new Spec(2, 0, dataPartition(2)), new Spec(0, 3, executable())};
        byte[] output = cartridge(contents);
        byte[] mainEntry = Arrays.copyOfRange(output, 0x120, 0x128);
        System.arraycopy(output, 0x130, output, 0x120, 8);
        System.arraycopy(mainEntry, 0, output, 0x130, 8);
        reject(cia(TITLE, contents), output, "bounds");
    }

    @Test public void rejectsTruncationUnexpectedTailAndInsufficientMediaCapacity() throws Exception {
        Spec[] contents = {new Spec(0, 3, executable())};
        byte[] cia = cia(TITLE, contents);
        byte[] output = cartridge(contents);
        reject(cia, Arrays.copyOf(output, output.length - 1), "bounds");
        reject(cia, Arrays.copyOf(output, output.length + 1), "trailing data");
        put(output, 0x104, 1, 4, true);
        reject(cia, output, "media capacity");
    }

    @Test public void everyPartitionByteIncludingSupplementalHeaderMustMatchSource() throws Exception {
        Spec[] contents = {new Spec(0, 3, executable()), new Spec(2, 88, dataPartition(2))};
        byte[] cia = cia(TITLE, contents);
        int supplemental = 0x4000 + contents[0].bytes.length;
        for (int offset : new int[]{0x4000, 0x4000 + 0xC01, supplemental, supplemental + 0x108,
                supplemental + 0x118, supplemental + 0x18F, supplemental + 0x200}) {
            byte[] output = cartridge(contents);
            output[offset] ^= 1;
            reject(cia, output, "SHA-256");
        }
    }

    @Test public void cartridgePartitionIdAndCopiedMainHeaderMustBeCorrect() throws Exception {
        Spec[] contents = {new Spec(0, 3, executable()), new Spec(1, 88, dataPartition(1))};
        byte[] output = cartridge(contents);
        output[0x198] ^= 1;
        reject(cia(TITLE, contents), output, "incorrect title ID");
        output = cartridge(contents);
        output[0x1100] ^= 1;
        reject(cia(TITLE, contents), output, "copied main header");
    }

    @Test public void rejectsNonBaseTitlesMissingMainAndOutOfRangeSourceIndices() throws Exception {
        Spec main = new Spec(0, 3, executable());
        for (long category : new long[]{0x0004000EL, 0x0004008CL, 0x00040010L, 0x00040002L}) {
            reject(cia(category << 32 | 0x01234500L, main), cartridge(main), "Only base-game");
        }
        reject(cia(TITLE, new Spec(1, 3, dataPartition(1))), cartridge(main), "content index 0");
        reject(cia(TITLE, main, new Spec(8, 9, dataPartition(8))), cartridge(main), "eight partition slots");
    }

    @Test public void rejectsNonNcchSupplementalContentAndRecordPadding() throws Exception {
        Spec main = new Spec(0, 3, executable());
        Spec bad = new Spec(1, 88, new byte[0x400]);
        reject(cia(TITLE, main, bad), cartridge(main, bad), "not a decrypted NCCH");
        Spec padded = new Spec(1, 88, Arrays.copyOf(dataPartition(1), 0x600));
        reject(cia(TITLE, main, padded), cartridge(main, padded), "NCCH size");
    }

    @Test public void sourceIntegrityAndMainExecutableChecksCannotBeBypassed() throws Exception {
        byte[] code = executable();
        code[0xC00] ^= 1;
        Spec main = new Spec(0, 3, code);
        reject(cia(TITLE, main), cartridge(main), "ExeFS file 0 failed");
        code = executable();
        code[0x108] ^= 1;
        main = new Spec(0, 3, code);
        reject(cia(TITLE, main), cartridge(main), "do not match its CIA");
        Spec encrypted = new Spec(1, 99, dataPartition(1));
        encrypted.bytes[0x18F] = 0;
        main = new Spec(0, 3, executable());
        reject(cia(TITLE, main, encrypted), cartridge(main, encrypted), "still NCCH-encrypted");
    }

    @Test public void cancellationDuringCartridgeHashDoesNotModifyEitherFile() throws Exception {
        byte[] mainBytes = Arrays.copyOf(executable(), 1024 * 1024);
        put(mainBytes, 0x104, mainBytes.length / 0x200, 4, true);
        Spec main = new Spec(0, 3, mainBytes);
        byte[] cia = cia(TITLE, main);
        byte[] cartridge = cartridge(main);
        File source = write(cia);
        File output = write(cartridge);
        CiaValidator.Progress progress = new CiaValidator.Progress() {
            boolean cancelled;
            @Override public void onProgress(int percent, String message) {
                if (message.startsWith("Verifying .3ds partition")) cancelled = true;
            }
            @Override public boolean isCancelled() { return cancelled; }
        };
        assertThrows(InterruptedIOException.class, () -> CartridgeValidator.validate(source, output, progress));
        assertArrayEquals(cia, Files.readAllBytes(source.toPath()));
        assertArrayEquals(cartridge, Files.readAllBytes(output.toPath()));
    }

    private void reject(byte[] cia, byte[] cartridge, String expected) throws Exception {
        File source = write(cia);
        File output = write(cartridge);
        IOException error = assertThrows(IOException.class, () -> CartridgeValidator.validate(source, output, null));
        if (expected != null) assertTrue(error.getMessage(), error.getMessage().contains(expected));
        assertArrayEquals(cia, Files.readAllBytes(source.toPath()));
        assertArrayEquals(cartridge, Files.readAllBytes(output.toPath()));
    }

    private File write(byte[] bytes) throws IOException {
        File file = temporary.newFile();
        Files.write(file.toPath(), bytes);
        return file;
    }

    /** Synthetic contents have no games, keys or signatures. */
    private static byte[] executable() throws Exception {
        byte[] bytes = ncch(0xE00, TITLE);
        bytes[0x18D] = 3;
        put(bytes, 0x180, 0x400, 4, true);
        put(bytes, 0x400, TITLE, 8, true);
        put(bytes, 0x1A0, 5, 4, true);
        put(bytes, 0x1A4, 2, 4, true);
        put(bytes, 0x1A8, 1, 4, true);
        System.arraycopy(new byte[]{'.', 'c', 'o', 'd', 'e'}, 0, bytes, 0xA00, 5);
        put(bytes, 0xA0C, 0x200, 4, true);
        Arrays.fill(bytes, 0xC00, 0xE00, (byte) 0x5A);
        System.arraycopy(sha(bytes, 0x200, 0x400), 0, bytes, 0x160, 32);
        System.arraycopy(sha(bytes, 0xC00, 0x200), 0, bytes, 0xBE0, 32);
        System.arraycopy(sha(bytes, 0xA00, 0x200), 0, bytes, 0x1C0, 32);
        return bytes;
    }

    private static byte[] dataPartition(int index) {
        byte[] bytes = ncch(0x400, 0x0005000001234500L + index);
        bytes[0x18D] = 1;
        bytes[0x200] = (byte) index;
        return bytes;
    }

    private static byte[] ncch(int size, long partitionId) {
        byte[] bytes = new byte[size];
        System.arraycopy(new byte[]{'N', 'C', 'C', 'H'}, 0, bytes, 0x100, 4);
        put(bytes, 0x104, size / 0x200, 4, true);
        put(bytes, 0x108, partitionId, 8, true);
        put(bytes, 0x112, 2, 2, true);
        put(bytes, 0x118, TITLE, 8, true);
        bytes[0x18F] = 4;
        return bytes;
    }

    private static byte[] cartridge(Spec... contents) {
        int size = 0x4000;
        for (Spec content : contents) size += content.bytes.length;
        byte[] bytes = new byte[size];
        System.arraycopy(new byte[]{'N', 'C', 'S', 'D'}, 0, bytes, 0x100, 4);
        put(bytes, 0x104, 0x40000, 4, true); // 128 MiB media capacity; file is trimmed.
        put(bytes, 0x108, TITLE, 8, true);
        bytes[0x18C] = 1;
        bytes[0x18D] = 1;
        int cursor = 0x4000;
        for (int slot = 0; slot < 8; slot++) for (Spec content : contents) {
            if (content.index != slot) continue;
            put(bytes, 0x120 + slot * 8, cursor / 0x200, 4, true);
            put(bytes, 0x124 + slot * 8, content.bytes.length / 0x200, 4, true);
            System.arraycopy(content.bytes, 0x108, bytes, 0x190 + slot * 8, 8);
            System.arraycopy(content.bytes, 0, bytes, cursor, content.bytes.length);
            if (slot == 0) System.arraycopy(content.bytes, 0x100, bytes, 0x1100, 0x100);
            cursor += content.bytes.length;
        }
        return bytes;
    }

    private static byte[] cia(long title, Spec... contents) throws Exception {
        int tmd = 0x2040;
        int body = tmd + 0x140;
        int groups = body + 0xC4;
        int records = groups + 64 * 36;
        int tmdSize = 0xB04 + contents.length * 48;
        int data = (tmd + tmdSize + 63) & -64;
        int size = 0;
        for (Spec content : contents) size += content.bytes.length;
        byte[] bytes = new byte[data + size];
        put(bytes, 0, 0x2020, 4, true);
        put(bytes, 16, tmdSize, 4, true);
        put(bytes, 24, size, 8, true);
        put(bytes, tmd, 0x10004, 4, false);
        bytes[body + 0x40] = 1;
        put(bytes, body + 0x4C, title, 8, false);
        put(bytes, body + 0x9E, contents.length, 2, false);
        put(bytes, groups + 2, contents.length, 2, false);
        int cursor = data;
        for (int i = 0; i < contents.length; i++) {
            Spec content = contents[i];
            int record = records + i * 48;
            put(bytes, record, content.id, 4, false);
            put(bytes, record + 4, content.index, 2, false);
            put(bytes, record + 8, content.bytes.length, 8, false);
            System.arraycopy(sha(content.bytes, 0, content.bytes.length), 0, bytes, record + 16, 32);
            bytes[32 + content.index / 8] |= (byte) (0x80 >>> (content.index % 8));
            System.arraycopy(content.bytes, 0, bytes, cursor, content.bytes.length);
            cursor += content.bytes.length;
        }
        System.arraycopy(sha(bytes, records, contents.length * 48), 0, bytes, groups + 4, 32);
        System.arraycopy(sha(bytes, groups, 64 * 36), 0, bytes, body + 0xA4, 32);
        return bytes;
    }

    private static byte[] sha(byte[] bytes, int offset, int size) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(bytes, offset, size);
        return digest.digest();
    }

    private static void put(byte[] bytes, int offset, long value, int size, boolean little) {
        for (int i = 0; i < size; i++) bytes[offset + (little ? i : size - 1 - i)] = (byte) (value >>> (i * 8));
    }

    private static final class Spec {
        final int index, id;
        final byte[] bytes;
        Spec(int index, int id, byte[] bytes) { this.index = index; this.id = id; this.bytes = bytes; }
    }
}
