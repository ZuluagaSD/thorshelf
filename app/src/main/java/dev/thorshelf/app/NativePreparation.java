package dev.thorshelf.app;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;

/** Source-built ctrtool/makerom bridge. Call from a worker, never the UI thread. */
public final class NativePreparation {
    private static final Object ENGINE_LOCK = new Object();
    private static volatile boolean loaded;

    private NativePreparation() { }

    public interface Progress {
        void onProgress(int percent, String message);
        default boolean isCancelled() { return false; }
    }

    /** Installs the pinned, bundled database locally. This never requests network access. */
    public static synchronized File bundledSeedDatabase(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), "native");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create the preparation data folder.");
        }
        File database = new File(directory, "seeddb.bin");
        File temporary = new File(directory, "seeddb.bin.tmp");
        try (InputStream input = context.getAssets().open("seeddb.bin");
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }
        if (!temporary.renameTo(database)) {
            temporary.delete();
            throw new IOException("Cannot install the preparation seed database.");
        }
        return database;
    }

    /**
     * Writes a separate decrypted CIA. The caller must validate the CIA before publishing it.
     * Calls are serialized because the native tools have process-wide state. Cancellation is
     * cooperative between native stages; a large extraction/rebuild may take time to stop.
     */
    public static void prepare(File input, File output, File workDir, File seedDatabase,
                               Progress progress) throws IOException {
        prepare(input, output, workDir, seedDatabase, progress, false);
    }

    /**
     * Packages a validated, decrypted base-game CIA as a genuine NCSD cartridge image.
     * The caller must verify all output partitions against the CIA before publishing it.
     * Updates, DLC and other title types must continue through the CIA installer.
     */
    public static void prepareCartridge(File input, File output, File workDir, File seedDatabase,
                                        Progress progress) throws IOException {
        prepare(input, output, workDir, seedDatabase, progress, true);
    }

    private static void prepare(File input, File output, File workDir, File seedDatabase,
                                Progress progress, boolean cartridge) throws IOException {
        synchronized (ENGINE_LOCK) {
            checkCancelled(progress);
            if (!input.isFile() || input.length() == 0) {
                throw new IOException("The downloaded CIA is missing or empty.");
            }
            if (input.getCanonicalFile().equals(output.getCanonicalFile())) {
                throw new IOException("The prepared copy must be separate from the original CIA.");
            }
            if (output.exists() && (cartridge || output.length() != 0)) {
                throw new IOException("The prepared output already exists; choose a new output file.");
            }
            if (cartridge) {
                CiaValidator.Result source = CiaValidator.inspect(input);
                if (!source.titleId.startsWith("00040000")) {
                    throw new IOException("Only base-game CIAs can become 3DS cartridge images. "
                            + "Install updates, DLC and other title types in Azahar instead.");
                }
                if (source.encryptedContent) {
                    throw new IOException("The CIA must be decrypted and verified before creating a 3DS image.");
                }
                if (source.indexZeroContent == null) {
                    throw new IOException("The CIA is missing its main content (index 0).");
                }
                for (String identity : source.contentIdentities) {
                    if (Integer.parseInt(identity.substring(0, 4), 16) > 7) {
                        throw new IOException("This CIA has content indexes that a 3DS cartridge cannot contain.");
                    }
                }
            }
            if (seedDatabase == null || !seedDatabase.isFile()) {
                throw new IOException("The local seed database is unavailable.");
            }
            if (!workDir.isDirectory() && !workDir.mkdirs()) {
                throw new IOException("Cannot create the preparation workspace.");
            }
            File parent = output.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Cannot create the prepared library folder.");
            }
            load();
            Progress forwarding = new Progress() {
                @Override public void onProgress(int percent, String message) {
                    if (progress != null && progress.isCancelled()) nativeCancel();
                    if (progress != null) progress.onProgress(Math.max(0, Math.min(99, percent)), message);
                }
            };
            boolean success = false;
            try (ParcelFileDescriptor in = ParcelFileDescriptor.open(input,
                         ParcelFileDescriptor.MODE_READ_ONLY);
                 ParcelFileDescriptor out = ParcelFileDescriptor.open(output,
                         ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE
                                 | ParcelFileDescriptor.MODE_TRUNCATE)) {
                int result = nativeDecryptCia(in.getFd(), out.getFd(),
                        seedDatabase.getAbsolutePath(), workDir.getAbsolutePath(),
                        input.getName(), cartridge, forwarding, null, null);
                checkCancelled(progress);
                if (result == 13) throw new InterruptedIOException("Preparation cancelled.");
                // Upstream code 11 reports no extracted partitions but produces no output.
                // It must not be treated as an already-decrypted success.
                if (result != 0 && result != 10) throw new IOException(errorMessage(result));
                if (!output.isFile() || output.length() == 0) {
                    throw new IOException("Preparation returned an empty file.");
                }
                if (cartridge) {
                    try (RandomAccessFile image = new RandomAccessFile(output, "r")) {
                        image.seek(0x100);
                        if (image.length() <= 0x4000 || image.readInt() != 0x4E435344) {
                            throw new IOException("Preparation did not produce a 3DS cartridge image.");
                        }
                    }
                }
                success = true;
            } finally {
                if (!success) output.delete();
            }
        }
    }

    public static void cancel() {
        if (loaded) nativeCancel();
    }

    private static void load() throws IOException {
        if (loaded) return;
        try {
            System.loadLibrary("cia3ds");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            throw new IOException("The preparation engine does not support this device architecture.", error);
        }
    }

    private static void checkCancelled(Progress progress) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || (progress != null && progress.isCancelled())) {
            throw new InterruptedIOException("Preparation cancelled.");
        }
    }

    private static String errorMessage(int result) {
        return switch (result) {
            case 1 -> "Cannot create the native workspace. Check free storage.";
            case 2 -> "Cannot read the downloaded CIA. Check storage and the source file.";
            case 4, 8 -> "Cannot save the prepared file. Check free storage.";
            case 5 -> "Cannot extract this CIA. It may be incomplete or require unavailable keys or a seed.";
            case 6, 11 -> "The CIA contains no supported 3DS content partitions.";
            case 7 -> "Cannot rebuild this title. Check free storage and the source file.";
            case 9 -> "Cannot decrypt this CIA. It may be corrupt or require an unavailable seed.";
            case 12 -> "This file is not a supported CIA, or it is corrupt.";
            case 14 -> "The downloaded CIA failed its original content checksums. Download it again before preparing it.";
            default -> "The preparation engine failed (code " + result + ").";
        };
    }

    private static native int nativeDecryptCia(int inputFd, int outputFd, String seedDatabase,
            String workDir, String originalName, boolean wantCci, Progress progress,
            Object logCallback, Object seedFetcherCallback);
    private static native void nativeCancel();
}
