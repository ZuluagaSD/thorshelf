package dev.thorshelf.app;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Streams an original DS ROM or a ZIP containing exactly one ROM into a new document. */
public final class NdsRomExporter {
    static final long MAX_ROM_BYTES = 512L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = MAX_ROM_BYTES + 64L * 1024 * 1024;
    static final int MAX_ENTRIES = 128;
    private static final int MAX_DIRECTORY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ZIP_TAIL = MAX_DIRECTORY_BYTES + 22 + 65535;
    private static final long MAX_ARCHIVE_BYTES = MAX_TOTAL_BYTES + MAX_DIRECTORY_BYTES;

    public interface Progress {
        boolean isCancelled();
        void onProgress(long copied, long total);
    }

    /** Safe to show to the user: messages never include provider paths or archive member names. */
    public static final class InvalidRomException extends IOException {
        InvalidRomException(String message) { super(message); }
    }

    private NdsRomExporter() {}

    /** The caller owns the streams and must delete its new destination if this method fails. */
    public static long export(InputStream source, OutputStream destination, String sourceName,
            long sourceSize, Progress progress) throws IOException {
        return export(source, destination, sourceName, sourceSize, progress,
                MAX_ROM_BYTES, MAX_TOTAL_BYTES, MAX_ENTRIES);
    }

    // Adjustable bounds keep resource-limit tests small and deterministic.
    static long export(InputStream source, OutputStream destination, String sourceName,
            long sourceSize, Progress progress, long maxRom, long maxTotal, int maxEntries)
            throws IOException {
        checkCancelled(progress);
        String name = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".nds")) {
            if (sourceSize > maxRom) throw invalid("The DS ROM exceeds the 512 MiB export limit.");
            long copied = copyRom(source, destination, sourceSize, maxRom, progress);
            if (sourceSize >= 0 && copied != sourceSize) {
                throw invalid("The DS ROM is incomplete. Download it again before exporting.");
            }
            return copied;
        }
        if (!name.endsWith(".zip")) {
            throw invalid("Choose a completed .nds download or a ZIP containing one DS ROM.");
        }
        if (sourceSize > MAX_ARCHIVE_BYTES) throw invalid("The DS archive exceeds the export size limit.");
        return extract(source, destination, sourceSize, progress, maxRom, maxTotal, maxEntries);
    }

    private static long copyRom(InputStream source, OutputStream destination, long size,
            long limit, Progress progress) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long copied = 0;
        int count;
        while (true) {
            checkCancelled(progress);
            count = source.read(buffer);
            if (count == -1) break;
            if (count > limit - copied) throw invalid("The DS ROM exceeds the 512 MiB export limit.");
            checkCancelled(progress);
            destination.write(buffer, 0, count);
            copied += count;
            report(progress, copied, size);
        }
        checkCancelled(progress);
        if (copied == 0) throw invalid("The DS ROM is empty. Download it again before exporting.");
        return copied;
    }

    private static long extract(InputStream source, OutputStream destination, long sourceSize,
            Progress progress, long maxRom, long maxTotal, int maxEntries) throws IOException {
        TailInputStream tracked = new TailInputStream(source, progress);
        List<Member> members = new ArrayList<>();
        Set<String> names = new HashSet<>();
        long total = 0;
        long romBytes = 0;
        int romCount = 0;
        byte[] buffer = new byte[128 * 1024];
        // ZipInputStream checks the local CRC and data descriptor as each member finishes.
        // Preserve caller ownership while still releasing its inflater on every exit.
        try (ZipInputStream zip = new ZipInputStream(new FilterInputStream(tracked) {
            @Override public void close() {}
        })) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkCancelled(progress);
                if (members.size() >= maxEntries) throw invalid("The DS archive contains too many files.");
                String name = entry.getName();
                if (!safeMemberName(name) || !names.add(name)) {
                    throw invalid("The DS archive contains an unsafe or duplicate file path.");
                }
                boolean rom = !entry.isDirectory() && name.toLowerCase(Locale.ROOT).endsWith(".nds");
                if (rom && ++romCount > 1) {
                    throw invalid("The ZIP contains multiple DS ROMs. Choose a ZIP with exactly one .nds file.");
                }
                if (entry.getSize() > maxTotal - total || rom && entry.getSize() > maxRom) {
                    throw invalid("The DS archive exceeds the export size limit.");
                }
                long memberBytes = 0;
                int count;
                while (true) {
                    checkCancelled(progress);
                    count = zip.read(buffer);
                    if (count == -1) break;
                    if (count > maxTotal - total || rom && count > maxRom - memberBytes) {
                        throw invalid("The DS archive exceeds the export size limit.");
                    }
                    checkCancelled(progress);
                    if (rom) destination.write(buffer, 0, count);
                    total += count;
                    memberBytes += count;
                    report(progress, rom ? memberBytes : romBytes, rom ? entry.getSize() : -1);
                }
                zip.closeEntry();
                if (entry.isDirectory() && memberBytes != 0) {
                    throw invalid("The DS archive contains an invalid folder entry.");
                }
                if (rom) {
                    romBytes = memberBytes;
                    if (romBytes == 0) throw invalid("The ZIP contains an empty DS ROM.");
                }
                members.add(new Member(entry, memberBytes));
            }
            // ZipInputStream stops at the central directory and otherwise accepts a ZIP
            // truncated after the local records. Consume and verify the directory + end record.
            while (tracked.read(buffer) != -1) checkCancelled(progress);
            if (sourceSize >= 0 && tracked.count != sourceSize) {
                throw invalid("The DS archive is incomplete. Download it again before exporting.");
            }
            validateDirectory(tracked, members);
        } catch (java.util.zip.ZipException | java.io.EOFException e) {
            throw invalid("The DS archive is damaged or incomplete. Download it again before exporting.");
        }
        checkCancelled(progress);
        if (romCount != 1) throw invalid("The ZIP does not contain a .nds file.");
        return romBytes;
    }

    private static boolean safeMemberName(String name) {
        if (name == null || name.isEmpty() || name.length() > 1024 || name.startsWith("/")
                || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) return false;
        String[] parts = name.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(".") || parts[i].equals("..")
                    || parts[i].isEmpty() && i != parts.length - 1) return false;
        }
        return true;
    }

    private static void validateDirectory(TailInputStream input, List<Member> members) throws IOException {
        byte[] tail = input.tail();
        int end = -1;
        for (int offset = tail.length - 22; offset >= Math.max(0, tail.length - 65557); offset--) {
            if (u32(tail, offset) == 0x06054b50L && offset + 22 + u16(tail, offset + 20) == tail.length) {
                end = offset;
                break;
            }
        }
        if (end < 0 || u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0
                || u16(tail, end + 8) != members.size() || u16(tail, end + 10) != members.size()) {
            throw invalid("The ZIP directory is missing, damaged, or uses an unsupported format.");
        }
        long length = u32(tail, end + 12);
        long offset = u32(tail, end + 16);
        long tailStart = input.count - tail.length;
        if (length > MAX_DIRECTORY_BYTES || offset < tailStart || offset + length != tailStart + end) {
            throw invalid("The ZIP directory is missing, damaged, or too large.");
        }
        int position = (int) (offset - tailStart);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < members.size(); i++) {
            if (position > end - 46 || u32(tail, position) != 0x02014b50L) {
                throw invalid("The ZIP directory is incomplete.");
            }
            int nameLength = u16(tail, position + 28);
            int extraLength = u16(tail, position + 30);
            int commentLength = u16(tail, position + 32);
            int next = position + 46 + nameLength + extraLength + commentLength;
            if (next > end || u16(tail, position + 34) != 0 || (u16(tail, position + 8) & 1) != 0) {
                throw invalid("The ZIP directory is damaged or uses an unsupported format.");
            }
            String name = new String(tail, position + 46, nameLength, StandardCharsets.UTF_8);
            Member member = null;
            for (Member candidate : members) if (candidate.name.equals(name)) member = candidate;
            if (member == null || !seen.add(name) || member.size != u32(tail, position + 24)
                    || member.compressedSize != u32(tail, position + 20)
                    || member.crc != u32(tail, position + 16) || member.method != u16(tail, position + 10)
                    || u32(tail, position + 42) >= offset) {
                throw invalid("The ZIP directory does not match its files.");
            }
            position = next;
        }
        if (position != end) throw invalid("The ZIP directory contains unexpected data.");
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 255) | (data[offset + 1] & 255) << 8;
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 255L) | (data[offset + 1] & 255L) << 8
                | (data[offset + 2] & 255L) << 16 | (data[offset + 3] & 255L) << 24;
    }

    private static final class Member {
        final String name;
        final long size;
        final long compressedSize;
        final long crc;
        final int method;
        Member(ZipEntry entry, long size) {
            name = entry.getName();
            this.size = size;
            compressedSize = entry.getCompressedSize();
            crc = entry.getCrc();
            method = entry.getMethod();
        }
    }

    private static final class TailInputStream extends FilterInputStream {
        final byte[] retained = new byte[MAX_ZIP_TAIL];
        final Progress progress;
        long count;
        int next;
        TailInputStream(InputStream source, Progress progress) { super(source); this.progress = progress; }
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            checkCancelled(progress);
            int read = in.read(bytes, offset, length);
            if (read <= 0) return read;
            if (read > MAX_ARCHIVE_BYTES - count) throw invalid("The DS archive exceeds the export size limit.");
            count += read;
            int keep = Math.min(read, retained.length);
            int start = offset + read - keep;
            int first = Math.min(keep, retained.length - next);
            System.arraycopy(bytes, start, retained, next, first);
            System.arraycopy(bytes, start + first, retained, 0, keep - first);
            next = (next + keep) % retained.length;
            return read;
        }
        byte[] tail() {
            int size = (int) Math.min(count, retained.length);
            byte[] result = new byte[size];
            int start = count < retained.length ? 0 : next;
            int first = Math.min(size, retained.length - start);
            System.arraycopy(retained, start, result, 0, first);
            System.arraycopy(retained, 0, result, first, size - first);
            return result;
        }
    }

    private static void checkCancelled(Progress progress) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || progress != null && progress.isCancelled()) {
            throw new InterruptedIOException("DS export cancelled.");
        }
    }

    private static void report(Progress progress, long copied, long total) {
        if (progress != null) progress.onProgress(copied, total);
    }

    private static InvalidRomException invalid(String message) { return new InvalidRomException(message); }
}
