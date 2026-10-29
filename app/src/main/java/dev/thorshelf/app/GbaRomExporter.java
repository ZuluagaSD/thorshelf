package dev.thorshelf.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Streams a raw GBA ROM, accepting it only when its catalog size and SHA-1 match. */
public final class GbaRomExporter {
    static final long MAX_ROM_BYTES = 64L * 1024 * 1024;

    public interface Progress {
        boolean isCancelled();
        void onProgress(long copied, long total);
    }

    /** Messages are safe for display and never contain provider paths. */
    public static final class InvalidRomException extends IOException {
        InvalidRomException(String message) { super(message); }
    }

    private GbaRomExporter() { }

    static boolean hasExpectedIdentity(String sha1, long size) {
        return sha1 != null && sha1.matches("(?i)[0-9a-f]{40}") && size > 0 && size <= MAX_ROM_BYTES;
    }

    /** The caller owns both streams and must remove its newly created document on failure. */
    public static long export(InputStream source, OutputStream destination, long sourceSize,
            String expectedSha1, long expectedSize, Progress progress) throws IOException {
        checkCancelled(progress);
        if (!hasExpectedIdentity(expectedSha1, expectedSize)) {
            throw new InvalidRomException("The GBA download has no valid catalog checksum. Download it again before saving.");
        }
        if (sourceSize >= 0 && sourceSize != expectedSize) {
            throw new InvalidRomException("The GBA ROM size does not match the catalog. Download it again before saving.");
        }
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-1"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        byte[] buffer = new byte[128 * 1024];
        long copied = 0;
        while (true) {
            checkCancelled(progress);
            int count = source.read(buffer);
            if (count == -1) break;
            if (count > expectedSize - copied) {
                throw new InvalidRomException("The GBA ROM exceeds its catalog size. Download it again before saving.");
            }
            checkCancelled(progress);
            destination.write(buffer, 0, count);
            digest.update(buffer, 0, count);
            copied += count;
            if (progress != null) progress.onProgress(copied, expectedSize);
        }
        checkCancelled(progress);
        if (copied != expectedSize) {
            throw new InvalidRomException("The GBA ROM is incomplete. Download it again before saving.");
        }
        StringBuilder actual = new StringBuilder(40);
        for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 255));
        if (!expectedSha1.equalsIgnoreCase(actual.toString())) {
            throw new InvalidRomException("The GBA ROM does not match the catalog checksum. Download it again before saving.");
        }
        checkCancelled(progress);
        return copied;
    }

    private static void checkCancelled(Progress progress) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || progress != null && progress.isCancelled()) {
            throw new InterruptedIOException("GBA export cancelled.");
        }
    }
}
