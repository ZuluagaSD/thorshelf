package dev.thorshelf.app;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Bounds and integrity checks for the CIA files passed to, and produced by, the native engine.
 * The layouts follow Project_CTR's cia.h, es_tmd.h and ncch.h. This checks integrity, not
 * Nintendo signatures or whether a title is supported by a particular emulator.
 */
public final class CiaValidator {
    private static final int HEADER_SIZE = 0x2020;
    private static final int TMD_BODY_SIZE = 0xC4;
    private static final int GROUP_SIZE = 36;
    private static final int GROUP_COUNT = 64;
    private static final int RECORD_SIZE = 48;

    private CiaValidator() {}

    public interface Progress {
        void onProgress(int percent, String message);
        default boolean isCancelled() { return false; }
    }

    public static final class Result {
        public final String titleId;
        public final int titleVersion;
        public final int contentCount;
        public final long totalContentBytes;
        public final boolean encryptedContent;
        /** Included content identities, in TMD order, formatted as index:contentId. */
        public final List<String> contentIdentities;
        // Retained for a verified content export, not inferred from TMD record order.
        final ContentLocation indexZeroContent;
        final List<ContentLocation> includedContents;

        private Result(Parsed parsed, boolean encrypted) {
            titleId = parsed.titleId;
            titleVersion = parsed.titleVersion;
            contentCount = parsed.contents.size();
            totalContentBytes = parsed.totalBytes;
            encryptedContent = encrypted;
            List<String> identities = new ArrayList<>();
            List<ContentLocation> locations = new ArrayList<>();
            ContentLocation indexZero = null;
            for (Content content : parsed.contents) {
                identities.add(String.format(Locale.ROOT, "%04x:%08x", content.index, content.id));
                ContentLocation location = new ContentLocation(content);
                locations.add(location);
                if (content.index == 0) indexZero = location;
            }
            contentIdentities = Collections.unmodifiableList(identities);
            indexZeroContent = indexZero;
            includedContents = Collections.unmodifiableList(locations);
        }
    }

    /** Immutable offsets and expected hash from the parsed content table. */
    static final class ContentLocation {
        final int index;
        final long offset, size;
        private final byte[] hash;
        private ContentLocation(Content content) {
            index = content.index;
            offset = content.offset;
            size = content.size;
            hash = content.hash.clone();
        }
        boolean matchesHash(byte[] actual) { return MessageDigest.isEqual(actual, hash); }
    }

    /** Fast structural preflight. Encrypted payloads are intentionally not hashed here. */
    public static Result inspect(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            Parsed parsed = parse(input);
            boolean encrypted = false;
            for (Content content : parsed.contents) {
                encrypted |= content.encrypted;
                if (!content.encrypted) {
                    byte[] ncch = ncchHeader(input, content);
                    encrypted |= ncch != null && (ncch[0x18F] & 4) == 0;
                }
            }
            return new Result(parsed, encrypted);
        }
    }

    /** Verifies every included payload and the plaintext NCCH protected regions before export. */
    public static Result validatePrepared(File file, Progress progress) throws IOException {
        checkCancelled(progress);
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            Parsed parsed = parse(input);
            verifyMetadata(parsed);
            Counter counter = new Counter(parsed.totalBytes, progress);
            counter.report("Checking prepared CIA");
            for (Content content : parsed.contents) {
                checkCancelled(progress);
                if (content.encrypted) {
                    throw invalid("Content " + content.index + " is still CIA-encrypted.");
                }
                verifyHash(input, content.offset, content.size, content.hash,
                        "Content " + content.index, counter, true);
                byte[] ncch = ncchHeader(input, content);
                if (ncch != null) verifyNcch(input, content, ncch, counter);
            }
            checkCancelled(progress);
            if (progress != null) progress.onProgress(100, "Prepared CIA verified");
            return new Result(parsed, false);
        }
    }

    private static Parsed parse(RandomAccessFile input) throws IOException {
        long fileSize = input.length();
        if (fileSize < HEADER_SIZE) throw invalid("The CIA header is missing or incomplete.");
        byte[] header = read(input, 0, HEADER_SIZE);
        if (le32(header, 0) != HEADER_SIZE || le16(header, 4) != 0) {
            throw invalid("This is not a supported CIA file.");
        }
        if (le16(header, 6) != 0) throw invalid("Only standard CIA containers are supported.");

        long certSize = le32(header, 8);
        long ticketSize = le32(header, 12);
        long tmdSize = le32(header, 16);
        long footerSize = le32(header, 20);
        long contentSize = unsigned64(header, 24, true);
        long certOffset = align(HEADER_SIZE, 64);
        long ticketOffset = align(end(certOffset, certSize), 64);
        long tmdOffset = align(end(ticketOffset, ticketSize), 64);
        long contentOffset = align(end(tmdOffset, tmdSize), 64);
        long contentEnd = end(contentOffset, contentSize);
        long expectedEnd = footerSize == 0 ? contentEnd : end(align(contentEnd, 64), footerSize);
        if (expectedEnd > fileSize) throw invalid("The CIA is incomplete: a section exceeds the file.");
        if (contentSize == 0 || tmdSize < 4) throw invalid("The CIA has no title metadata or content.");

        int signatureSize = signatureSize(read(input, tmdOffset, 4));
        int recordsOffset = signatureSize + TMD_BODY_SIZE + GROUP_COUNT * GROUP_SIZE;
        if (tmdSize < recordsOffset) throw invalid("The CIA title metadata is incomplete.");
        byte[] metadata = read(input, tmdOffset, recordsOffset);
        if ((metadata[signatureSize + 0x40] & 255) != 1) {
            throw invalid("This CIA uses an unsupported title metadata version.");
        }
        int recordCount = be16(metadata, signatureSize + 0x9E);
        if (recordCount == 0) throw invalid("The CIA title metadata contains no content records.");
        int metadataSize = recordsOffset + recordCount * RECORD_SIZE;
        if (metadataSize > tmdSize) throw invalid("The CIA content table is incomplete.");
        metadata = read(input, tmdOffset, metadataSize);

        Parsed parsed = new Parsed();
        parsed.metadata = metadata;
        parsed.signatureSize = signatureSize;
        parsed.recordsOffset = recordsOffset;
        parsed.recordCount = recordCount;
        parsed.titleId = hex(metadata, signatureSize + 0x4C, 8);
        parsed.titleVersion = be16(metadata, signatureSize + 0x9C);
        boolean[] seen = new boolean[65536];
        long cursor = 0;
        for (int i = 0; i < recordCount; i++) {
            int record = recordsOffset + i * RECORD_SIZE;
            int index = be16(metadata, record + 4);
            if (seen[index]) throw invalid("The CIA has duplicate content indices.");
            seen[index] = true;
            long size = unsigned64(metadata, record + 8, false);
            if (!included(header, index)) continue;
            if (size == 0) throw invalid("The CIA contains an empty content record.");
            cursor = align(cursor, 16);
            long next = end(cursor, size);
            if (next > contentSize) throw invalid("The CIA content records exceed the content section.");
            Content content = new Content();
            content.id = be32(metadata, record);
            content.index = index;
            content.size = size;
            content.offset = end(contentOffset, cursor);
            content.encrypted = (be16(metadata, record + 6) & 1) != 0;
            content.hash = Arrays.copyOfRange(metadata, record + 16, record + 48);
            parsed.contents.add(content);
            parsed.totalBytes = end(parsed.totalBytes, size);
            cursor = next;
        }
        for (int index = 0; index < seen.length; index++) {
            if (included(header, index) && !seen[index]) {
                throw invalid("The CIA content mask refers to a missing record.");
            }
        }
        if (parsed.contents.isEmpty()) throw invalid("The CIA contains no included content.");
        if (contentSize > align(cursor, 16)) {
            throw invalid("The CIA has content bytes not described by its title metadata.");
        }
        return parsed;
    }

    private static void verifyMetadata(Parsed parsed) throws IOException {
        byte[] metadata = parsed.metadata;
        int groupsOffset = parsed.signatureSize + TMD_BODY_SIZE;
        requireHash(hash(metadata, groupsOffset, GROUP_COUNT * GROUP_SIZE),
                Arrays.copyOfRange(metadata, parsed.signatureSize + 0xA4, parsed.signatureSize + 0xC4),
                "Title metadata groups");
        boolean[] covered = new boolean[parsed.recordCount];
        for (int i = 0; i < GROUP_COUNT; i++) {
            int group = groupsOffset + i * GROUP_SIZE;
            int first = be16(metadata, group);
            int count = be16(metadata, group + 2);
            if (count == 0) continue;
            if (first + count > parsed.recordCount) throw invalid("A title metadata group exceeds its content table.");
            for (int j = first; j < first + count; j++) {
                if (covered[j]) throw invalid("Title metadata groups overlap.");
                covered[j] = true;
            }
            requireHash(hash(metadata, parsed.recordsOffset + first * RECORD_SIZE, count * RECORD_SIZE),
                    Arrays.copyOfRange(metadata, group + 4, group + GROUP_SIZE), "Title metadata content table");
        }
        for (boolean item : covered) if (!item) throw invalid("The title metadata has an unhashed content record.");
    }

    private static byte[] ncchHeader(RandomAccessFile input, Content content) throws IOException {
        if (content.size < 0x104) return null;
        byte[] magic = read(input, content.offset + 0x100, 4);
        if (magic[0] != 'N' || magic[1] != 'C' || magic[2] != 'C' || magic[3] != 'H') return null;
        if (content.size < 0x200) throw invalid("An NCCH content header is incomplete.");
        return read(input, content.offset, 0x200);
    }

    private static void verifyNcch(RandomAccessFile input, Content content, byte[] header,
            Counter counter) throws IOException {
        if ((header[0x18F] & 4) == 0) throw invalid("Content " + content.index + " is still NCCH-encrypted.");
        int version = le16(header, 0x112);
        int unitLog = header[0x18E] & 255;
        if (version > 2 || unitLog > 22) throw invalid("An NCCH content has unsupported block sizes.");
        long unit = version == 1 ? 1 : 1L << (9 + unitLog);
        long ncchSize = multiply(le32(header, 0x104), unit);
        if (ncchSize < 0x200 || ncchSize > content.size) throw invalid("An NCCH content exceeds its CIA record.");

        long exheaderSize = le32(header, 0x180);
        if (exheaderSize != 0) {
            regionBounds(0x200, exheaderSize, ncchSize);
            verifyHash(input, content.offset + 0x200, exheaderSize,
                    Arrays.copyOfRange(header, 0x160, 0x180), "NCCH extended header", counter, false);
        }
        // Plain and logo regions are not encrypted; bounds still must be valid.
        regionBounds(multiply(le32(header, 0x190), unit), multiply(le32(header, 0x194), unit), ncchSize);
        verifyRegion(input, content, header, 0x198, 0x130, unit, ncchSize, "NCCH logo", counter, true);
        verifyRegion(input, content, header, 0x1A0, 0x1C0, unit, ncchSize, "NCCH ExeFS", counter, false);
        verifyRegion(input, content, header, 0x1B0, 0x1E0, unit, ncchSize, "NCCH RomFS", counter, false);
    }

    private static void verifyRegion(RandomAccessFile input, Content content, byte[] header,
            int fieldsOffset, int hashOffset, long unit, long ncchSize, String label,
            Counter counter, boolean entireRegion) throws IOException {
        long offset = multiply(le32(header, fieldsOffset), unit);
        long size = multiply(le32(header, fieldsOffset + 4), unit);
        long protectedSize = entireRegion ? size : multiply(le32(header, fieldsOffset + 8), unit);
        regionBounds(offset, size, ncchSize);
        if (protectedSize > size) throw invalid(label + " hash region exceeds its content.");
        if (size == 0) return;
        if (protectedSize == 0) throw invalid(label + " has no protected hash region.");
        verifyHash(input, end(content.offset, offset), protectedSize,
                Arrays.copyOfRange(header, hashOffset, hashOffset + 32), label, counter, false);
        if (fieldsOffset == 0x1A0) {
            // ExeFS headers and banner/icon can use a different key from .code. Checking
            // only the protected header would accept partially decrypted executables.
            if (size < 0x200 || protectedSize < 0x200) throw invalid("The NCCH ExeFS header is incomplete.");
            verifyExeFsFiles(input, end(content.offset, offset), size, counter);
        }
    }

    private static void verifyExeFsFiles(RandomAccessFile input, long offset, long size,
            Counter counter) throws IOException {
        byte[] header = read(input, offset, 0x200);
        for (int i = 0; i < 10; i++) {
            int entry = i * 16;
            long fileOffset = end(0x200, le32(header, entry + 8));
            long fileSize = le32(header, entry + 12);
            if (fileSize == 0) continue;
            if (end(fileOffset, fileSize) > size) throw invalid("An NCCH ExeFS file exceeds its content bounds.");
            // ExeFS stores the ten SHA-256 values in reverse entry order.
            int hashOffset = 0x1E0 - i * 32;
            verifyHash(input, end(offset, fileOffset), fileSize,
                    Arrays.copyOfRange(header, hashOffset, hashOffset + 32),
                    "NCCH ExeFS file " + i, counter, false);
        }
    }

    private static void regionBounds(long offset, long size, long limit) throws IOException {
        if (size != 0 && (offset < 0x200 || end(offset, size) > limit)) {
            throw invalid("An NCCH region exceeds its content bounds.");
        }
    }

    private static void verifyHash(RandomAccessFile input, long offset, long size, byte[] expected,
            String label, Counter counter, boolean countBytes) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[128 * 1024];
        input.seek(offset);
        long remaining = size;
        while (remaining > 0) {
            checkCancelled(counter.progress);
            int count = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
            if (count < 0) throw invalid(label + " was truncated during validation.");
            digest.update(buffer, 0, count);
            remaining -= count;
            if (countBytes) counter.processed += count;
            counter.report("Verifying " + label);
        }
        requireHash(digest.digest(), expected, label);
    }

    private static void requireHash(byte[] actual, byte[] expected, String label) throws IOException {
        if (!MessageDigest.isEqual(actual, expected)) throw invalid(label + " failed its SHA-256 integrity check.");
    }

    private static byte[] hash(byte[] bytes, int offset, int count) {
        MessageDigest digest = sha256();
        digest.update(bytes, offset, count);
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void checkCancelled(Progress progress) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || progress != null && progress.isCancelled()) {
            throw new InterruptedIOException("CIA validation cancelled.");
        }
    }

    private static byte[] read(RandomAccessFile input, long offset, int count) throws IOException {
        byte[] data = new byte[count];
        input.seek(offset);
        input.readFully(data);
        return data;
    }

    private static int signatureSize(byte[] data) throws IOException {
        switch ((int) be32(data, 0)) {
            case 0x10000: case 0x10003: return 0x240;
            case 0x10001: case 0x10004: return 0x140;
            case 0x10002: case 0x10005: return 0x80;
            default: throw invalid("The title metadata signature format is unsupported.");
        }
    }

    private static boolean included(byte[] header, int index) {
        return (header[32 + index / 8] & (0x80 >>> (index % 8))) != 0;
    }

    private static int le16(byte[] data, int offset) {
        return (data[offset] & 255) | (data[offset + 1] & 255) << 8;
    }

    private static int be16(byte[] data, int offset) {
        return (data[offset] & 255) << 8 | (data[offset + 1] & 255);
    }

    private static long le32(byte[] data, int offset) {
        return (data[offset] & 255L) | (data[offset + 1] & 255L) << 8
                | (data[offset + 2] & 255L) << 16 | (data[offset + 3] & 255L) << 24;
    }

    private static long be32(byte[] data, int offset) {
        return (data[offset] & 255L) << 24 | (data[offset + 1] & 255L) << 16
                | (data[offset + 2] & 255L) << 8 | (data[offset + 3] & 255L);
    }

    private static long unsigned64(byte[] data, int offset, boolean littleEndian) throws IOException {
        int top = littleEndian ? offset + 7 : offset;
        if ((data[top] & 0x80) != 0) throw invalid("A CIA size exceeds the supported file range.");
        long value = 0;
        for (int i = 0; i < 8; i++) value = value << 8 | (data[littleEndian ? offset + 7 - i : offset + i] & 255L);
        return value;
    }

    private static long end(long offset, long size) throws IOException {
        if (offset < 0 || size < 0 || offset > Long.MAX_VALUE - size) throw invalid("A CIA size overflows its file range.");
        return offset + size;
    }

    private static long align(long value, int alignment) throws IOException {
        return end(value, alignment - 1) & ~(alignment - 1L);
    }

    private static long multiply(long value, long factor) throws IOException {
        if (value > Long.MAX_VALUE / factor) throw invalid("An NCCH size overflows its file range.");
        return value * factor;
    }

    private static String hex(byte[] bytes, int start, int count) {
        StringBuilder result = new StringBuilder(count * 2);
        for (int i = start; i < start + count; i++) result.append(String.format(Locale.ROOT, "%02x", bytes[i] & 255));
        return result.toString();
    }

    private static IOException invalid(String message) { return new IOException(message); }

    private static final class Parsed {
        String titleId;
        int titleVersion, signatureSize, recordsOffset, recordCount;
        long totalBytes;
        byte[] metadata;
        final List<Content> contents = new ArrayList<>();
    }

    private static final class Content {
        int index;
        long id, offset, size;
        boolean encrypted;
        byte[] hash;
    }

    private static final class Counter {
        final long total;
        final Progress progress;
        long processed;
        int lastPercent = -1;
        String lastMessage = "";
        Counter(long total, Progress progress) { this.total = total; this.progress = progress; }
        void report(String message) {
            if (progress == null) return;
            int percent = (int) Math.min(99, processed * 100.0 / total);
            if (percent != lastPercent || !message.equals(lastMessage)) {
                lastPercent = percent;
                lastMessage = message;
                progress.onProgress(percent, message);
            }
        }
    }
}
