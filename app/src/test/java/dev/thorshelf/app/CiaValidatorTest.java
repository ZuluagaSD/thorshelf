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

public class CiaValidatorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void validatesNcchAndDataContentsWithMonotonicProgress() throws Exception {
        Fixture fixture = fixture(new Spec(0, ncchWithExeFs()), new Spec(7, new byte[23]));
        List<Integer> progress = new ArrayList<>();
        CiaValidator.Result result = CiaValidator.validatePrepared(write(fixture.bytes),
                (percent, message) -> progress.add(percent));
        assertEquals("0004000001234500", result.titleId);
        assertEquals(1057, result.titleVersion);
        assertEquals(2, result.contentCount);
        assertEquals(1047, result.totalContentBytes);
        assertEquals(Arrays.asList("0000:00000064", "0007:0000006b"), result.contentIdentities);
        assertFalse(result.encryptedContent);
        assertEquals(Integer.valueOf(100), progress.get(progress.size() - 1));
        for (int i = 1; i < progress.size(); i++) assertTrue(progress.get(i) >= progress.get(i - 1));
    }

    @Test public void sparseMaskSkipsAbsentDlcIncludingItsEncryptionFlag() throws Exception {
        Spec absent = new Spec(9, new byte[4096]);
        absent.included = false;
        absent.encrypted = true;
        Fixture fixture = fixture(new Spec(2, new byte[17]), absent, new Spec(65534, new byte[40]));
        CiaValidator.Result result = CiaValidator.validatePrepared(write(fixture.bytes), null);
        assertEquals(2, result.contentCount);
        assertEquals(57, result.totalContentBytes);
        assertFalse(result.encryptedContent);
    }

    @Test public void hashesAllGroupsAndNotOnlyFirstGroup() throws Exception {
        Fixture fixture = fixture(new Spec(1, new byte[16]), new Spec(2, new byte[16]));
        put16(fixture.bytes, fixture.groups + 2, 1, false);
        put16(fixture.bytes, fixture.groups + 36, 1, false);
        put16(fixture.bytes, fixture.groups + 38, 1, false);
        metadataHashes(fixture);
        assertEquals(2, CiaValidator.validatePrepared(write(fixture.bytes), null).contentCount);
        fixture.bytes[fixture.groups + 40] ^= 1;
        rootHash(fixture);
        assertInvalid(fixture, "content table");
    }

    @Test public void encryptedCiaCanBeInspectedButCannotBeMarkedPrepared() throws Exception {
        Spec encrypted = new Spec(0, new byte[512]);
        encrypted.encrypted = true;
        Fixture fixture = fixture(encrypted);
        assertTrue(CiaValidator.inspect(write(fixture.bytes)).encryptedContent);
        assertInvalid(fixture, "still CIA-encrypted");
    }

    @Test public void checksInnerEncryptionOnEveryIncludedContent() throws Exception {
        byte[] encryptedNcch = ncchWithExeFs();
        encryptedNcch[0x18F] = 0;
        Fixture fixture = fixture(new Spec(0, new byte[32]), new Spec(3, encryptedNcch));
        assertTrue(CiaValidator.inspect(write(fixture.bytes)).encryptedContent);
        assertInvalid(fixture, "still NCCH-encrypted");
    }

    @Test public void payloadDamageFailsContentHash() throws Exception {
        Fixture fixture = fixture(new Spec(0, new byte[512]));
        fixture.bytes[fixture.content + 17] = 1;
        assertFalse(CiaValidator.inspect(write(fixture.bytes)).encryptedContent);
        assertInvalid(fixture, "Content 0 failed");
    }

    @Test public void recalculateOuterHashesCannotHideIncorrectNcchDecryption() throws Exception {
        byte[] damagedNcch = ncchWithExeFs();
        damagedNcch[0x200] ^= 1;
        Fixture fixture = fixture(new Spec(0, damagedNcch));
        assertInvalid(fixture, "NCCH ExeFS failed");
    }

    @Test public void verifiesExtendedHeaderAndRomFsProtectedHashes() throws Exception {
        byte[] content = ncchWithExeFs();
        content = Arrays.copyOf(content, 0xA00);
        put32(content, 0x104, 5, true);
        put32(content, 0x180, 0x400, true);
        put32(content, 0x1A0, 3, true);
        put32(content, 0x1B0, 4, true);
        put32(content, 0x1B4, 1, true);
        put32(content, 0x1B8, 1, true);
        System.arraycopy(sha(content, 0x200, 0x400), 0, content, 0x160, 32);
        System.arraycopy(sha(content, 0x600, 0x200), 0, content, 0x1C0, 32);
        System.arraycopy(sha(content, 0x800, 0x200), 0, content, 0x1E0, 32);
        CiaValidator.validatePrepared(write(fixture(new Spec(0, content)).bytes), null);
        content[0x810] ^= 1;
        assertInvalid(fixture(new Spec(0, content)), "NCCH RomFS failed");
        content[0x810] ^= 1;
        content[0x240] ^= 1;
        assertInvalid(fixture(new Spec(0, content)), "NCCH extended header failed");
    }

    @Test public void malformedNcchRegionCannotReadOutsideContent() throws Exception {
        byte[] content = ncchWithExeFs();
        put32(content, 0x1A0, 100, true);
        assertInvalid(fixture(new Spec(0, content)), "NCCH region exceeds");
        put32(content, 0x1A0, 1, true);
        put32(content, 0x1A8, 2, true);
        assertInvalid(fixture(new Spec(0, content)), "hash region exceeds");
    }

    @Test public void checksExeFsPayloadDespiteValidHeaderAndOuterContentHashes() throws Exception {
        byte[] content = ncchWithExecutable();
        CiaValidator.validatePrepared(write(fixture(new Spec(0, content)).bytes), null);
        content[0x410] ^= 1;
        // Rebuilding the outer CIA updates its content hash but leaves the authentic
        // expected executable hash inside the correctly decrypted ExeFS header.
        assertInvalid(fixture(new Spec(0, content)), "NCCH ExeFS file 0 failed");
    }

    @Test public void exeFsFilesCannotExtendOutsideTheirOwnRegion() throws Exception {
        byte[] content = ncchWithExecutable();
        put32(content, 0x208, 0x200, true);
        System.arraycopy(sha(content, 0x200, 0x200), 0, content, 0x1C0, 32);
        assertInvalid(fixture(new Spec(0, content)), "ExeFS file exceeds");
    }

    @Test public void unreasonableNcchBlockExponentFailsWithoutOverflow() throws Exception {
        byte[] content = ncchWithExeFs();
        content[0x18E] = (byte) 255;
        assertInvalid(fixture(new Spec(0, content)), "unsupported block sizes");
    }

    @Test public void incorrectTmdRootAndRecordHashesAreRejected() throws Exception {
        Fixture fixture = fixture(new Spec(0, new byte[32]));
        fixture.bytes[fixture.body + 0xA4] ^= 1;
        assertInvalid(fixture, "metadata groups failed");
        fixture = fixture(new Spec(0, new byte[32]));
        fixture.bytes[fixture.records + 16] ^= 1;
        assertInvalid(fixture, "content table failed");
    }

    @Test public void missingOrOverlappingTmdGroupCoverageIsRejected() throws Exception {
        Fixture fixture = fixture(new Spec(0, new byte[32]), new Spec(1, new byte[32]));
        put16(fixture.bytes, fixture.groups + 2, 1, false);
        metadataHashes(fixture);
        assertInvalid(fixture, "unhashed content record");
        put16(fixture.bytes, fixture.groups + 36, 0, false);
        put16(fixture.bytes, fixture.groups + 38, 2, false);
        metadataHashes(fixture);
        assertInvalid(fixture, "groups overlap");
    }

    @Test public void duplicateIndicesAndMaskWithoutRecordAreRejected() throws Exception {
        Fixture duplicate = fixture(new Spec(2, new byte[16]), new Spec(2, new byte[16]));
        assertInspectInvalid(duplicate, "duplicate content indices");
        Fixture missing = fixture(new Spec(0, new byte[16]));
        missing.bytes[32] |= 0x40;
        assertInspectInvalid(missing, "mask refers to a missing record");
    }

    @Test public void unsignedSizesAndSectionAdditionCannotOverflow() throws Exception {
        Fixture negative = fixture(new Spec(0, new byte[16]));
        negative.bytes[31] = (byte) 0x80;
        assertInspectInvalid(negative, "supported file range");
        Fixture overflow = fixture(new Spec(0, new byte[16]));
        put64(overflow.bytes, 24, Long.MAX_VALUE, true);
        assertInspectInvalid(overflow, "overflows its file range");
        Fixture record = fixture(new Spec(0, new byte[16]));
        record.bytes[record.records + 8] = (byte) 0x80;
        assertInspectInvalid(record, "supported file range");
    }

    @Test public void truncatedHeadersTablesAndPayloadsAreRejected() throws Exception {
        IOException header = assertThrows(IOException.class,
                () -> CiaValidator.inspect(write(new byte[100])));
        assertTrue(header.getMessage().contains("header"));
        Fixture truncated = fixture(new Spec(0, new byte[16]));
        truncated.bytes = Arrays.copyOf(truncated.bytes, truncated.bytes.length - 1);
        assertInspectInvalid(truncated, "section exceeds");
        Fixture table = fixture(new Spec(0, new byte[16]));
        put16(table.bytes, table.body + 0x9E, 65535, false);
        assertInspectInvalid(table, "content table is incomplete");
    }

    @Test public void inconsistentContentSizeDoesNotHideAdditionalData() throws Exception {
        Fixture tooSmall = fixture(new Spec(0, new byte[32]));
        put64(tooSmall.bytes, 24, 16, true);
        assertInspectInvalid(tooSmall, "records exceed");
        Fixture tooLarge = fixture(new Spec(0, new byte[32]));
        tooLarge.bytes = Arrays.copyOf(tooLarge.bytes, tooLarge.bytes.length + 32);
        put64(tooLarge.bytes, 24, 64, true);
        assertInspectInvalid(tooLarge, "not described");
    }

    @Test public void cancellationIsCheckedBetweenStreamingChunks() throws Exception {
        Fixture fixture = fixture(new Spec(0, new byte[1024 * 1024]));
        CiaValidator.Progress progress = new CiaValidator.Progress() {
            boolean cancelled;
            @Override public void onProgress(int percent, String message) { cancelled = percent > 0; }
            @Override public boolean isCancelled() { return cancelled; }
        };
        assertThrows(InterruptedIOException.class,
                () -> CiaValidator.validatePrepared(write(fixture.bytes), progress));
    }

    private File write(byte[] bytes) throws IOException {
        File file = temporary.newFile();
        Files.write(file.toPath(), bytes);
        return file;
    }

    private void assertInvalid(Fixture fixture, String expected) throws Exception {
        IOException error = assertThrows(IOException.class,
                () -> CiaValidator.validatePrepared(write(fixture.bytes), null));
        assertTrue(error.getMessage(), error.getMessage().contains(expected));
    }

    private void assertInspectInvalid(Fixture fixture, String expected) throws Exception {
        IOException error = assertThrows(IOException.class,
                () -> CiaValidator.inspect(write(fixture.bytes)));
        assertTrue(error.getMessage(), error.getMessage().contains(expected));
    }

    private static byte[] ncchWithExeFs() throws Exception {
        byte[] content = new byte[0x400];
        content[0x100] = 'N'; content[0x101] = 'C'; content[0x102] = 'C'; content[0x103] = 'H';
        put32(content, 0x104, 2, true);
        put16(content, 0x112, 2, true);
        content[0x18F] = 4;
        put32(content, 0x1A0, 1, true);
        put32(content, 0x1A4, 1, true);
        put32(content, 0x1A8, 1, true);
        System.arraycopy(sha(content, 0x200, 0x200), 0, content, 0x1C0, 32);
        return content;
    }

    private static byte[] ncchWithExecutable() throws Exception {
        byte[] content = Arrays.copyOf(ncchWithExeFs(), 0x600);
        put32(content, 0x104, 3, true);
        put32(content, 0x1A4, 2, true);
        byte[] name = {'.', 'c', 'o', 'd', 'e'};
        System.arraycopy(name, 0, content, 0x200, name.length);
        put32(content, 0x20C, 0x200, true);
        Arrays.fill(content, 0x400, content.length, (byte) 0x5A);
        System.arraycopy(sha(content, 0x400, 0x200), 0, content, 0x3E0, 32);
        System.arraycopy(sha(content, 0x200, 0x200), 0, content, 0x1C0, 32);
        return content;
    }

    /** Only synthetic bytes; no certificate, ticket, copyrighted game content or console keys. */
    private static Fixture fixture(Spec... contents) throws Exception {
        Fixture fixture = new Fixture();
        int tmd = 0x2040;
        fixture.body = tmd + 0x140;
        fixture.groups = fixture.body + 0xC4;
        fixture.records = fixture.groups + 64 * 36;
        int tmdSize = 0xB04 + contents.length * 48;
        fixture.content = align(tmd + tmdSize, 64);
        int contentSize = 0;
        for (Spec spec : contents) if (spec.included) contentSize = align(contentSize, 16) + spec.data.length;
        fixture.bytes = new byte[fixture.content + contentSize];
        byte[] bytes = fixture.bytes;
        put32(bytes, 0, 0x2020, true);
        put32(bytes, 16, tmdSize, true);
        put64(bytes, 24, contentSize, true);
        put32(bytes, tmd, 0x10004, false);
        bytes[fixture.body + 0x40] = 1;
        put64(bytes, fixture.body + 0x4C, 0x0004000001234500L, false);
        put16(bytes, fixture.body + 0x9C, 1057, false);
        put16(bytes, fixture.body + 0x9E, contents.length, false);
        put16(bytes, fixture.groups + 2, contents.length, false);
        int cursor = 0;
        for (int i = 0; i < contents.length; i++) {
            Spec spec = contents[i];
            int record = fixture.records + i * 48;
            put32(bytes, record, 100L + spec.index, false);
            put16(bytes, record + 4, spec.index, false);
            put16(bytes, record + 6, spec.encrypted ? 1 : 0, false);
            put64(bytes, record + 8, spec.data.length, false);
            System.arraycopy(sha(spec.data, 0, spec.data.length), 0, bytes, record + 16, 32);
            if (spec.included) {
                bytes[32 + spec.index / 8] |= (byte) (0x80 >>> (spec.index % 8));
                cursor = align(cursor, 16);
                System.arraycopy(spec.data, 0, bytes, fixture.content + cursor, spec.data.length);
                cursor += spec.data.length;
            }
        }
        metadataHashes(fixture);
        return fixture;
    }

    private static void metadataHashes(Fixture fixture) throws Exception {
        for (int i = 0; i < 64; i++) {
            int group = fixture.groups + i * 36;
            int first = (fixture.bytes[group] & 255) * 256 + (fixture.bytes[group + 1] & 255);
            int count = (fixture.bytes[group + 2] & 255) * 256 + (fixture.bytes[group + 3] & 255);
            if (count == 0) continue;
            System.arraycopy(sha(fixture.bytes, fixture.records + first * 48, count * 48),
                    0, fixture.bytes, group + 4, 32);
        }
        rootHash(fixture);
    }

    private static void rootHash(Fixture fixture) throws Exception {
        System.arraycopy(sha(fixture.bytes, fixture.groups, 64 * 36), 0, fixture.bytes, fixture.body + 0xA4, 32);
    }

    private static byte[] sha(byte[] bytes, int offset, int size) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(bytes, offset, size);
        return digest.digest();
    }

    private static int align(int value, int alignment) { return (value + alignment - 1) & -alignment; }
    private static void put16(byte[] bytes, int offset, long value, boolean little) { put(bytes, offset, value, 2, little); }
    private static void put32(byte[] bytes, int offset, long value, boolean little) { put(bytes, offset, value, 4, little); }
    private static void put64(byte[] bytes, int offset, long value, boolean little) { put(bytes, offset, value, 8, little); }
    private static void put(byte[] bytes, int offset, long value, int size, boolean little) {
        for (int i = 0; i < size; i++) bytes[offset + (little ? i : size - 1 - i)] = (byte) (value >>> (i * 8));
    }

    private static final class Spec {
        final int index;
        final byte[] data;
        boolean included = true;
        boolean encrypted;
        Spec(int index, byte[] data) { this.index = index; this.data = data; }
    }

    private static final class Fixture {
        byte[] bytes;
        int body, groups, records, content;
    }
}
