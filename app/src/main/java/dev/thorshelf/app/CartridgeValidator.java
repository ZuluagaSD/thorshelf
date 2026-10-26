package dev.thorshelf.app;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

/** Verifies the trimmed NCSD cartridge produced by makerom's -nomodtid path.
 * Every included CIA content must occupy its TMD index and retain its full SHA-256.
 * This checks structure and integrity, not Nintendo signatures.
 */
public final class CartridgeValidator {
    private static final int SECTOR = 0x200;
    private static final int FIRST_PARTITION = 0x4000;

    private CartridgeValidator() {}

    public static void validate(File preparedCia, File cartridge, CiaValidator.Progress progress)
            throws IOException {
        checkCancelled(progress);
        if (preparedCia.getCanonicalFile().equals(cartridge.getCanonicalFile())) {
            throw new IOException("The cartridge must differ from the source CIA.");
        }
        CiaValidator.Result title = CiaValidator.validatePrepared(preparedCia,
                new CiaValidator.Progress() {
                    @Override public void onProgress(int percent, String message) {
                        report(progress, percent / 2, message);
                    }
                    @Override public boolean isCancelled() {
                        return progress != null && progress.isCancelled();
                    }
                });
        if (!CxiExporter.isBaseApplication(title)) {
            throw new IOException("Only base-game CIAs can become playable .3ds cartridges.");
        }
        if (title.indexZeroContent == null) {
            throw new IOException("The CIA does not include its main executable (content index 0).");
        }
        CiaValidator.ContentLocation[] contents = new CiaValidator.ContentLocation[8];
        for (CiaValidator.ContentLocation content : title.includedContents) {
            if (content.index >= contents.length) {
                throw new IOException("CIA content index " + content.index
                        + " cannot fit in the cartridge's eight partition slots.");
            }
            contents[content.index] = content;
        }
        try (RandomAccessFile source = new RandomAccessFile(preparedCia, "r");
                RandomAccessFile output = new RandomAccessFile(cartridge, "r")) {
            checkCancelled(progress);
            CxiExporter.executableSize(source, title.indexZeroContent, title.titleId);
            if (output.length() < FIRST_PARTITION) {
                throw new IOException("The .3ds cartridge header is incomplete.");
            }
            byte[] header = read(output, 0, SECTOR);
            if (!magic(header, "NCSD") || (header[0x18C] & 255) != 1
                    || ((header[0x18D] & 255) != 1 && (header[0x18D] & 255) != 2)
                    || header[0x18E] != 0) {
                throw new IOException("The output is not a supported NCSD cartridge image.");
            }
            if (!title.titleId.equals(id(header, 0x108))) {
                throw new IOException("The cartridge title ID does not match its CIA.");
            }
            long capacity = le32(header, 0x104) * SECTOR;
            if (capacity < output.length()) {
                throw new IOException("The cartridge exceeds its declared media capacity.");
            }
            long cursor = FIRST_PARTITION;
            long verified = 0;
            byte[] buffer = new byte[128 * 1024];
            for (int slot = 0; slot < contents.length; slot++) {
                checkCancelled(progress);
                CiaValidator.ContentLocation content = contents[slot];
                long offset = le32(header, 0x120 + slot * 8) * SECTOR;
                long size = le32(header, 0x124 + slot * 8) * SECTOR;
                // makerom's cartridge path has no additional filesystem or encryption layer.
                if (header[0x110 + slot] != 0 || header[0x118 + slot] != 0) {
                    throw new IOException("A cartridge partition uses an unsupported filesystem or encryption type.");
                }
                if (content == null) {
                    if (offset != 0 || size != 0 || !id(header, 0x190 + slot * 8).equals("0000000000000000")) {
                        throw new IOException("The cartridge contains an unexpected partition in slot " + slot + ".");
                    }
                    continue;
                }
                if (size != content.size || offset != cursor || size > output.length() - offset) {
                    throw new IOException("Cartridge partition " + slot + " is missing, overlaps, or exceeds its bounds.");
                }
                byte[] ncch = read(source, content.offset, SECTOR);
                if (!magic(ncch, "NCCH") || (ncch[0x18F] & 4) == 0) {
                    throw new IOException("CIA content " + slot + " is not a decrypted NCCH partition.");
                }
                int version = (ncch[0x112] & 255) | (ncch[0x113] & 255) << 8;
                int unitLog = ncch[0x18E] & 255;
                if (version > 2 || unitLog > 22) {
                    throw new IOException("A cartridge partition uses unsupported NCCH block sizes.");
                }
                long unit = version == 1 ? 1 : 1L << (9 + unitLog);
                long units = le32(ncch, 0x104);
                if (units > Long.MAX_VALUE / unit || units * unit != size) {
                    throw new IOException("A cartridge partition's NCCH size does not match its CIA content.");
                }
                if (!id(ncch, 0x108).equals(id(header, 0x190 + slot * 8))) {
                    throw new IOException("Cartridge partition " + slot + " has an incorrect title ID.");
                }
                if (slot == 0 && !Arrays.equals(Arrays.copyOfRange(ncch, 0x100, SECTOR),
                        read(output, 0x1100, 0x100))) {
                    throw new IOException("The cartridge's copied main header does not match its executable.");
                }
                MessageDigest digest = sha256();
                output.seek(offset);
                long remaining = size;
                while (remaining > 0) {
                    checkCancelled(progress);
                    int count = output.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw new IOException("A cartridge partition was truncated during validation.");
                    digest.update(buffer, 0, count);
                    remaining -= count;
                    verified += count;
                    report(progress, 50 + (int) (verified * 49.0 / title.totalContentBytes),
                            "Verifying .3ds partition " + slot);
                }
                if (!content.matchesHash(digest.digest())) {
                    throw new IOException("Cartridge partition " + slot + " does not match its verified CIA content (SHA-256).");
                }
                cursor = offset + size;
            }
            if (cursor != output.length()) {
                throw new IOException("The trimmed cartridge has unexpected trailing data.");
            }
            checkCancelled(progress);
            report(progress, 100, "Playable .3ds cartridge verified");
            checkCancelled(progress);
        }
    }

    private static boolean magic(byte[] header, String expected) {
        for (int i = 0; i < 4; i++) if (header[0x100 + i] != expected.charAt(i)) return false;
        return true;
    }

    private static byte[] read(RandomAccessFile input, long offset, int count) throws IOException {
        byte[] bytes = new byte[count];
        input.seek(offset);
        input.readFully(bytes);
        return bytes;
    }

    private static long le32(byte[] bytes, int offset) {
        return (bytes[offset] & 255L) | (bytes[offset + 1] & 255L) << 8
                | (bytes[offset + 2] & 255L) << 16 | (bytes[offset + 3] & 255L) << 24;
    }

    private static String id(byte[] bytes, int offset) {
        StringBuilder result = new StringBuilder(16);
        for (int i = offset + 7; i >= offset; i--) {
            result.append(String.format(Locale.ROOT, "%02x", bytes[i] & 255));
        }
        return result.toString();
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void report(CiaValidator.Progress progress, int percent, String message) {
        if (progress != null) progress.onProgress(percent, message);
    }

    private static void checkCancelled(CiaValidator.Progress progress) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || progress != null && progress.isCancelled()) {
            throw new InterruptedIOException("Cartridge validation cancelled.");
        }
    }
}
