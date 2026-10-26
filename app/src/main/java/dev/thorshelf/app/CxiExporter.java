package dev.thorshelf.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Extracts the verified executable NCCH from a base-game CIA for Azahar's game folder. */
public final class CxiExporter {
    private CxiExporter() {}

    /** Updates, DLC, system titles and other packages still need Azahar's CIA installer. */
    public static boolean isBaseApplication(CiaValidator.Result title) {
        return title != null && title.titleId.startsWith("00040000");
    }

    /**
     * Validates the whole decrypted CIA before extracting its included content index zero.
     * The destination must not exist. On any failure only the new partial destination is
     * removed; the source and pre-existing files are never modified.
     */
    public static File export(File preparedCia, File outputCxi, CiaValidator.Progress progress)
            throws IOException {
        checkCancelled(progress);
        if (preparedCia.getCanonicalFile().equals(outputCxi.getCanonicalFile())) {
            throw new IOException("The CXI destination must differ from the source CIA.");
        }
        if (outputCxi.exists()) throw new IOException("The CXI destination already exists.");
        CiaValidator.Result title = CiaValidator.validatePrepared(preparedCia,
                new CiaValidator.Progress() {
                    @Override public void onProgress(int percent, String message) {
                        report(progress, percent * 70 / 100, message);
                    }
                    @Override public boolean isCancelled() {
                        return progress != null && progress.isCancelled();
                    }
                });
        if (!isBaseApplication(title)) {
            throw new IOException("Only base-game CIAs can become playable CXI files. "
                    + "Install updates, DLC and other title types in Azahar instead.");
        }
        CiaValidator.ContentLocation main = title.indexZeroContent;
        if (main == null) throw new IOException("The CIA does not include its main executable (content index 0).");
        boolean created = false;
        boolean complete = false;
        try (RandomAccessFile input = new RandomAccessFile(preparedCia, "r")) {
            checkCancelled(progress);
            long cxiSize = executableSize(input, main, title.titleId);
            if (!outputCxi.createNewFile()) throw new IOException("The CXI destination already exists.");
            created = true;
            MessageDigest digest = sha256();
            byte[] buffer = new byte[128 * 1024];
            input.seek(main.offset);
            long copied = 0;
            int lastPercent = -1;
            try (FileOutputStream output = new FileOutputStream(outputCxi)) {
                // Hash the entire TMD record, including any trailing container padding.
                // The CXI itself ends at the size declared by its NCCH header.
                while (copied < main.size) {
                    checkCancelled(progress);
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, main.size - copied));
                    if (count < 0) throw new IOException("The CIA executable was truncated during export.");
                    digest.update(buffer, 0, count);
                    int written = (int) Math.min(count, Math.max(0, cxiSize - copied));
                    if (written > 0) output.write(buffer, 0, written);
                    copied += count;
                    int percent = 70 + (int) (copied * 29.0 / main.size);
                    if (percent != lastPercent) {
                        report(progress, percent, "Creating playable CXI");
                        lastPercent = percent;
                    }
                }
                checkCancelled(progress);
                if (!main.matchesHash(digest.digest())) {
                    throw new IOException("The CIA executable changed during export or failed its SHA-256 check.");
                }
                output.getFD().sync();
            }
            if (outputCxi.length() != cxiSize) throw new IOException("The exported CXI has an incorrect size.");
            checkCancelled(progress);
            report(progress, 100, "Playable CXI verified");
            checkCancelled(progress);
            complete = true;
            return outputCxi;
        } finally {
            if (created && !complete && outputCxi.exists() && !outputCxi.delete()) {
                throw new IOException("Could not remove the incomplete CXI: " + outputCxi.getName());
            }
        }
    }

    static long executableSize(RandomAccessFile input, CiaValidator.ContentLocation content,
            String titleId) throws IOException {
        if (content.size < 0x200) throw new IOException("The main content is not an NCCH executable.");
        byte[] header = read(input, content.offset, 0x200);
        if (header[0x100] != 'N' || header[0x101] != 'C' || header[0x102] != 'C' || header[0x103] != 'H'
                || (header[0x18D] & 2) == 0) {
            throw new IOException("The main content is not an NCCH executable.");
        }
        if ((header[0x18F] & 4) == 0) throw new IOException("The main executable is still encrypted.");
        if (!titleId.equals(id(header, 0x108)) || !titleId.equals(id(header, 0x118))) {
            throw new IOException("The main executable's title and program IDs do not match its CIA.");
        }
        int version = (header[0x112] & 255) | (header[0x113] & 255) << 8;
        int unitLog = header[0x18E] & 255;
        if (version > 2 || unitLog > 22) throw new IOException("The main executable uses unsupported block sizes.");
        long unit = version == 1 ? 1 : 1L << (9 + unitLog);
        long size = le32(header, 0x104) * unit;
        // CiaValidator already checked multiplication and all NCCH region bounds.
        if (size < 0x600 || size > content.size || le32(header, 0x180) < 0x400) {
            throw new IOException("The main executable's extended header is missing or incomplete.");
        }
        byte[] programId = read(input, content.offset + 0x400, 8);
        if (!titleId.equals(id(programId, 0))) {
            throw new IOException("The executable's extended-header program ID does not match its CIA.");
        }
        long exeFsOffset = le32(header, 0x1A0) * unit;
        long exeFsSize = le32(header, 0x1A4) * unit;
        if (exeFsOffset < 0x200 || exeFsSize < 0x200 || exeFsOffset > size - exeFsSize) {
            throw new IOException("The main executable is missing its ExeFS code.");
        }
        byte[] exeFs = read(input, content.offset + exeFsOffset, 0x200);
        for (int i = 0; i < 10; i++) {
            int entry = i * 16;
            if (exeFs[entry] == '.' && exeFs[entry + 1] == 'c' && exeFs[entry + 2] == 'o'
                    && exeFs[entry + 3] == 'd' && exeFs[entry + 4] == 'e'
                    && exeFs[entry + 5] == 0 && exeFs[entry + 6] == 0 && exeFs[entry + 7] == 0
                    && le32(exeFs, entry + 12) > 0) {
                return size;
            }
        }
        throw new IOException("The main executable is missing its ExeFS code.");
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
        for (int i = offset + 7; i >= offset; i--) result.append(String.format(Locale.ROOT, "%02x", bytes[i] & 255));
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
            throw new InterruptedIOException("CXI export cancelled.");
        }
    }
}
