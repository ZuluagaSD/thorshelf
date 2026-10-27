package dev.thorshelf.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;

/** Minimal device/emulator smoke runner without a separate test framework dependency. */
public final class NativePreparationInstrumentation extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    private int passed;
    private boolean seedDemo;
    private boolean testCompletion;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        seedDemo = arguments != null && "true".equalsIgnoreCase(arguments.getString("seed_demo"));
        testCompletion = arguments != null && "true".equalsIgnoreCase(arguments.getString("test_completion"));
        start();
    }

    @Override public void onStart() {
        Bundle results = new Bundle();
        if (testCompletion) {
            try {
                long id = AutomaticCompletionSmoke.run(getTargetContext(), this);
                results.putLong("demo_download_id", id);
                results.putString("stream", "PASS automatic completion: encrypted synthetic CIA became one verified cartridge in the selected folder; original removed; saved history retained; repeat trigger created no duplicate. Download ID " + id + ". No network request was made.\n");
                finish(Activity.RESULT_OK, results);
            } catch (Throwable failure) {
                results.putString("stream", "Automatic completion failed: " + failure + "\n" + android.util.Log.getStackTraceString(failure));
                results.putString("failure", failure.toString());
                finish(Activity.RESULT_CANCELED, results);
            }
            return;
        }
        if (seedDemo) {
            try {
                long id = SyntheticDemoSeeder.seed(getTargetContext());
                results.putLong("demo_download_id", id);
                results.putString("stream", "Created locally generated Synthetic cartridge demo (not a game).cia; download ID " + id + ". No network request was made.\n");
                finish(Activity.RESULT_OK, results);
            } catch (Throwable failure) {
                results.putString("stream", "Demo setup failed: " + failure + "\n" + android.util.Log.getStackTraceString(failure));
                results.putString("failure", failure.toString());
                finish(Activity.RESULT_CANCELED, results);
            }
            return;
        }
        File root = new File(getTargetContext().getFilesDir(), "native-smoke");
        try {
            delete(root);
            require(root.mkdirs(), "Cannot create test workspace");
            testPrepare(root, "ncch-encrypted", false, false);
            testPrepare(root, "cia-and-ncch-encrypted", true, false);
            testPrepare(root, "mixed-sparse-content", false, true);
            testCartridge(root, "plaintext-cartridge", false);
            testCartridge(root, "encrypted-cia-to-cartridge", true);
            testCartridgeRejections(root);
            testCartridgeCancellation(root);
            testCorruptOriginal(root);
            testCancellation(root);
            testMalformed(root);
            results.putInt("passed", passed);
            results.putString("stream", report + "\nPASS: " + passed + " native preparation scenarios.\n");
            finish(Activity.RESULT_OK, results);
        } catch (Throwable failure) {
            results.putString("stream", report + "\nFAIL: " + failure + "\n" + android.util.Log.getStackTraceString(failure));
            results.putString("failure", failure.toString());
            finish(Activity.RESULT_CANCELED, results);
        } finally {
            delete(root);
        }
    }

    private void testPrepare(File root, String name, boolean outer, boolean mixed) throws Exception {
        File directory = new File(root, name);
        require(directory.mkdirs(), "Cannot create case workspace");
        File input = new File(directory, "synthetic-input.cia");
        File output = new File(directory, "synthetic-output.cia");
        byte[] source = SyntheticCia.create(outer, mixed);
        Files.write(input.toPath(), source);
        CiaValidator.Result original = CiaValidator.inspect(input);
        require(original.encryptedContent, name + ": fixture must be encrypted");
        NativePreparation.prepare(input, output, new File(directory, "scratch"),
                NativePreparation.bundledSeedDatabase(getTargetContext()),
                (percent, message) -> { });
        CiaValidator.Result prepared = CiaValidator.validatePrepared(output, null);
        require(!prepared.encryptedContent, name + ": output still encrypted");
        require(original.titleId.equals(prepared.titleId), name + ": title ID changed");
        require(original.titleVersion == prepared.titleVersion, name + ": title version changed");
        require(original.contentIdentities.equals(prepared.contentIdentities), name + ": content identities changed");
        require(Arrays.equals(source, Files.readAllBytes(input.toPath())), name + ": original modified");
        byte[] bytes = Files.readAllBytes(output.toPath());
        int contentStart = SyntheticCia.contentOffset(bytes);
        int expectedCount = mixed ? 2 : 1;
        require(prepared.contentCount == expectedCount, name + ": lost content");
        for (int i = 0; i < expectedCount; i++) {
            int payloadOffset = contentStart + i * SyntheticCia.CONTENT_LENGTH + SyntheticCia.EXEFS_OFFSET + 0x200;
            byte[] actual = Arrays.copyOfRange(bytes, payloadOffset, payloadOffset + SyntheticCia.PAYLOAD.length);
            require(Arrays.equals(SyntheticCia.PAYLOAD, actual), name + ": actual payload did not decrypt");
        }
        require(!new File(directory, "scratch/work").exists(), name + ": native workspace leaked");
        report.append("PASS ").append(name).append(" (payload bytes, hashes, title/version, source preservation)\n");
        passed++;
    }

    private void testCancellation(File root) throws Exception {
        File input = new File(root, "cancel-source.cia");
        File output = new File(root, "cancel-output.cia");
        Files.write(input.toPath(), SyntheticCia.create(false, false));
        boolean[] cancelled = {false};
        try {
            NativePreparation.prepare(input, output, new File(root, "cancel-work"),
                    NativePreparation.bundledSeedDatabase(getTargetContext()), new NativePreparation.Progress() {
                        @Override public void onProgress(int percent, String message) {
                            cancelled[0] = true;
                            NativePreparation.cancel();
                        }
                        @Override public boolean isCancelled() { return cancelled[0]; }
                    });
            throw new AssertionError("Cancellation unexpectedly succeeded");
        } catch (InterruptedIOException expected) {
            require(!output.exists(), "Cancellation left output behind");
            require(!new File(root, "cancel-work/work").exists(), "Cancellation left native scratch behind");
        }
        report.append("PASS native cancellation removes output and scratch\n");
        passed++;
    }

    private void testCartridge(File root, String name, boolean encrypted) throws Exception {
        File directory = new File(root, name);
        require(directory.mkdirs(), "Cannot create cartridge workspace");
        File input = new File(directory, "synthetic-input.cia");
        File decrypted = encrypted ? new File(directory, "verified.cia") : input;
        File output = new File(directory, "synthetic-output.3ds");
        byte[] original = SyntheticCia.createCartridge(encrypted);
        Files.write(input.toPath(), original);
        File seed = NativePreparation.bundledSeedDatabase(getTargetContext());
        if (encrypted) {
            NativePreparation.prepare(input, decrypted, new File(directory, "decrypt"), seed,
                    (percent, message) -> { });
        }
        CiaValidator.Result title = CiaValidator.validatePrepared(decrypted, null);
        require(title.contentCount == 2, name + ": fixture lost its supplemental data");
        byte[] prepared = Files.readAllBytes(decrypted.toPath());
        NativePreparation.prepareCartridge(decrypted, output, new File(directory, "cartridge"),
                seed, (percent, message) -> { });
        CartridgeValidator.validate(decrypted, output, null);
        byte[] image = Files.readAllBytes(output.toPath());
        ByteBuffer header = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        require(image[0x100] == 'N' && image[0x101] == 'C'
                && image[0x102] == 'S' && image[0x103] == 'D', name + ": not a genuine NCSD image");
        require(header.getLong(0x108) == SyntheticCia.TITLE_ID, name + ": wrong media ID");
        require(image[0x18E] == 0, name + ": unexpected cartridge media units");
        int[] slots = {0, 2};
        int contentOffset = SyntheticCia.contentOffset(prepared);
        for (int i = 0; i < slots.length; i++) {
            int slot = slots[i];
            int offset = header.getInt(0x120 + slot * 8) * 512;
            int size = header.getInt(0x124 + slot * 8) * 512;
            require(size == SyntheticCia.CONTENT_LENGTH, name + ": changed partition size");
            require(header.getLong(0x190 + slot * 8) == SyntheticCia.TITLE_ID + slot,
                    name + ": changed supplemental partition ID");
            require(Arrays.equals(Arrays.copyOfRange(prepared,
                            contentOffset + i * SyntheticCia.CONTENT_LENGTH,
                            contentOffset + (i + 1) * SyntheticCia.CONTENT_LENGTH),
                    Arrays.copyOfRange(image, offset, offset + size)),
                    name + ": changed verified NCCH bytes in slot " + slot);
        }
        require(header.getInt(0x128) == 0 && header.getInt(0x12C) == 0,
                name + ": sparse partition was moved into slot 1");
        require(image.length == 0x4000 + 2 * SyntheticCia.CONTENT_LENGTH,
                name + ": image has unnecessary cartridge capacity padding");
        require(Arrays.equals(original, Files.readAllBytes(input.toPath())), name + ": original modified");
        require(Arrays.equals(prepared, Files.readAllBytes(decrypted.toPath())), name + ": verified CIA modified");
        require(!new File(directory, "cartridge/work").exists(), name + ": native workspace leaked");
        report.append("PASS ").append(name)
                .append(" (NCSD layout, sparse CFA, exact payloads and IDs, source preservation, trimmed size)\n");
        passed++;
    }

    private void testCartridgeRejections(File root) throws Exception {
        File directory = new File(root, "cartridge-rejections");
        require(directory.mkdirs(), "Cannot create rejection workspace");
        File input = new File(directory, "input.cia");
        File output = new File(directory, "output.3ds");
        File scratch = new File(directory, "scratch");
        File seed = NativePreparation.bundledSeedDatabase(getTargetContext());
        byte[] update = SyntheticCia.createUpdate();
        Files.write(input.toPath(), update);
        CiaValidator.validatePrepared(input, null);
        try {
            NativePreparation.prepareCartridge(input, output, scratch, seed, null);
            throw new AssertionError("Update unexpectedly became a cartridge");
        } catch (IOException expected) {
            require(expected.getMessage().contains("Only base-game"), "Update rejection was unclear: " + expected);
            require(!output.exists(), "Update rejection left output behind");
            require(Arrays.equals(update, Files.readAllBytes(input.toPath())), "Update was modified");
        }
        Files.write(input.toPath(), SyntheticCia.createCartridge(true));
        try {
            NativePreparation.prepareCartridge(input, output, scratch, seed, null);
            throw new AssertionError("Unverified encrypted input unexpectedly became a cartridge");
        } catch (IOException expected) {
            require(expected.getMessage().contains("decrypted and verified"), "Encrypted rejection was unclear");
            require(!output.exists(), "Encrypted rejection left output behind");
        }
        Files.write(input.toPath(), SyntheticCia.createCartridge(false));
        byte[] existing = new byte[]{9, 8, 7, 6};
        Files.write(output.toPath(), existing);
        try {
            NativePreparation.prepareCartridge(input, output, scratch, seed, null);
            throw new AssertionError("Existing cartridge was overwritten");
        } catch (IOException expected) {
            require(Arrays.equals(existing, Files.readAllBytes(output.toPath())), "Existing output changed");
        }
        report.append("PASS cartridge rejects updates, unprepared input and existing destinations\n");
        passed++;
    }

    private void testCartridgeCancellation(File root) throws Exception {
        File input = new File(root, "cancel-cartridge-source.cia");
        File output = new File(root, "cancel-cartridge-output.3ds");
        byte[] source = SyntheticCia.createCartridge(false);
        Files.write(input.toPath(), source);
        boolean[] cancelled = {false};
        try {
            NativePreparation.prepareCartridge(input, output, new File(root, "cancel-cartridge-work"),
                    NativePreparation.bundledSeedDatabase(getTargetContext()), new NativePreparation.Progress() {
                        @Override public void onProgress(int percent, String message) {
                            cancelled[0] = true;
                            NativePreparation.cancel();
                        }
                        @Override public boolean isCancelled() { return cancelled[0]; }
                    });
            throw new AssertionError("Cartridge cancellation unexpectedly succeeded");
        } catch (InterruptedIOException expected) {
            require(!output.exists(), "Cartridge cancellation left output behind");
            require(!new File(root, "cancel-cartridge-work/work").exists(), "Cartridge cancellation left scratch behind");
            require(Arrays.equals(source, Files.readAllBytes(input.toPath())), "Cancellation modified the source");
        }
        report.append("PASS native cartridge cancellation preserves source and removes partial output\n");
        passed++;
    }

    private void testCorruptOriginal(File root) throws Exception {
        File input = new File(root, "corrupt-source.cia");
        File output = new File(root, "corrupt-output.cia");
        byte[] damaged = SyntheticCia.create(false, false);
        // Damage payload outside the ExeFS protected header without repairing the original TMD.
        damaged[SyntheticCia.contentOffset(damaged) + SyntheticCia.EXEFS_OFFSET + 0x240] ^= 0x55;
        Files.write(input.toPath(), damaged);
        try {
            NativePreparation.prepare(input, output, new File(root, "corrupt-work"),
                    NativePreparation.bundledSeedDatabase(getTargetContext()), (percent, message) -> { });
            throw new AssertionError("Corrupt original input unexpectedly succeeded");
        } catch (IOException expected) {
            require(expected.getMessage().contains("original content checksums"), "Corruption was not caught by original TMD: " + expected);
            require(!output.exists(), "Corrupt original left output behind");
            require(Arrays.equals(damaged, Files.readAllBytes(input.toPath())), "Corrupt original was modified");
            require(!new File(root, "corrupt-work/work").exists(), "Corrupt original left native scratch behind");
        }
        report.append("PASS original TMD detects damaged encrypted payload before rebuilding\n");
        passed++;
    }

    private void testMalformed(File root) throws Exception {
        File input = new File(root, "bad-source.cia");
        File output = new File(root, "bad-output.cia");
        Files.write(input.toPath(), new byte[1024]);
        try {
            NativePreparation.prepare(input, output, new File(root, "bad-work"),
                    NativePreparation.bundledSeedDatabase(getTargetContext()), (percent, message) -> { });
            throw new AssertionError("Malformed input unexpectedly succeeded");
        } catch (IOException expected) {
            require(!output.exists(), "Malformed input left output behind");
            require(!new File(root, "bad-work/work").exists(), "Malformed input left native scratch behind");
        }
        report.append("PASS malformed input fails without output or scratch\n");
        passed++;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void delete(File path) {
        File[] children = path.listFiles();
        if (children != null) for (File child : children) delete(child);
        path.delete();
    }
}
