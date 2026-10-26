package dev.thorshelf.app;

import android.app.Application;
import android.app.DownloadManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real local streams and a tiny document provider; no network requests or game data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class ExportServiceTest {
    private Application app;
    private File sourceFile;
    private byte[] sourceBytes;
    private Uri sourceUri;
    private long downloadId;
    private FileProvider documents;
    private ServiceController<ExportService> service;
    private CountDownLatch unblockCopy;

    @Before public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();
        sourceBytes = new byte[300 * 1024];
        for (int i = 0; i < sourceBytes.length; i++) sourceBytes[i] = (byte) (i * 31);
        sourceFile = File.createTempFile("synthetic-source", ".bin", app.getCacheDir());
        Files.write(sourceFile.toPath(), sourceBytes);
        DownloadManager manager = app.getSystemService(DownloadManager.class);
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
        downloadId = new DownloadStore(app).enqueue("https://download2.erista.me/test", "synthetic.bin",
                null, null, "https://hshop.erista.me/t/42", true);
        shadowOf(shadowOf(manager).getRequest(downloadId)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        sourceUri = manager.getUriForDownloadedFile(downloadId);
        assertNotNull(sourceUri);
        FileProvider downloads = registerProvider(sourceUri.getAuthority());
        downloads.files.put(sourceUri, sourceFile);
        documents = registerProvider("dev.thorshelf.test.documents");
        // Register actual streams explicitly so these tests also work with different shadow defaults.
        shadowOf(app.getContentResolver()).registerInputStreamSupplier(sourceUri, () -> {
            try { return Files.newInputStream(sourceFile.toPath()); }
            catch (IOException e) { throw new IllegalStateException(e); }
        });
        assertEquals(sourceUri, new DownloadStore(app).completedUri(downloadId));
    }

    @After public void tearDown() throws Exception {
        if (unblockCopy != null) unblockCopy.countDown();
        if (service != null) service.destroy();
        await(() -> !app.getSharedPreferences("export_status", Context.MODE_PRIVATE)
                .getBoolean("in_progress", false));
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void completedCopyMatchesSourceAndKeepsOriginal() throws Exception {
        Uri destination = newDestination("complete");
        File output = documents.files.get(destination);
        launch(destination);
        awaitFinished();

        assertTrue(ExportService.status(app), ExportService.status(app).startsWith("Export complete:"));
        assertArrayEquals(sourceBytes, Files.readAllBytes(output.toPath()));
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile.toPath()));
        assertTrue(documents.deleted.isEmpty());
        assertNotNull(new DownloadStore(app).completedUri(downloadId));
    }

    @Test public void failedWriteRemovesPartialDestinationAndKeepsOriginal() throws Exception {
        Uri destination = newDestination("failure");
        File output = documents.files.get(destination);
        shadowOf(app.getContentResolver()).registerOutputStreamSupplier(destination, () -> {
            try {
                OutputStream stream = Files.newOutputStream(output.toPath());
                return new OutputStream() {
                    int count;
                    @Override public void write(int value) throws IOException {
                        if (count++ >= 32) throw new IOException("Synthetic full destination");
                        stream.write(value);
                    }
                    @Override public void close() throws IOException { stream.close(); }
                };
            } catch (IOException e) { throw new IllegalStateException(e); }
        });
        launch(destination);
        awaitFinished();

        assertTrue(ExportService.status(app), ExportService.status(app).startsWith("Export failed."));
        assertFalse(output.exists());
        assertTrue(documents.deleted.containsKey(destination));
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile.toPath()));
    }

    @Test public void busyExportRejectsAndDeletesOnlyUnusedNewDocument() throws Exception {
        Uri activeDestination = newDestination("active");
        File activeOutput = documents.files.get(activeDestination);
        Uri unusedDestination = newDestination("unused");
        File unusedOutput = documents.files.get(unusedDestination);
        Uri existingDestination = newDestination("existing-busy");
        File existingOutput = documents.files.get(existingDestination);
        byte[] existingBytes = new byte[]{7, 8, 9};
        Files.write(existingOutput.toPath(), existingBytes);
        CountDownLatch reading = new CountDownLatch(1);
        unblockCopy = new CountDownLatch(1);
        shadowOf(app.getContentResolver()).registerInputStreamSupplier(sourceUri, () -> {
            try {
                InputStream stream = Files.newInputStream(sourceFile.toPath());
                return new FilterInputStream(stream) {
                    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                        reading.countDown();
                        try {
                            if (!unblockCopy.await(5, TimeUnit.SECONDS)) throw new IOException("Test gate timed out");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                        return super.read(buffer, offset, length);
                    }
                };
            } catch (IOException e) { throw new IllegalStateException(e); }
        });
        launch(activeDestination);
        assertTrue("Copy never reached its source stream", reading.await(5, TimeUnit.SECONDS));
        try {
            ExportService.start(app, downloadId, unusedDestination, "unused.bin");
            fail("Expected a busy export to be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("already running"));
        }
        try {
            ExportService.startNds(app, downloadId, existingDestination, "existing.nds");
            fail("Expected a busy export to be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("already running"));
        }
        await(() -> !unusedOutput.exists());
        assertTrue(activeOutput.exists());
        assertFalse(documents.deleted.containsKey(activeDestination));
        unblockCopy.countDown();
        awaitFinished();
        assertArrayEquals(sourceBytes, Files.readAllBytes(activeOutput.toPath()));
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile.toPath()));
        assertArrayEquals(existingBytes, Files.readAllBytes(existingOutput.toPath()));
        assertFalse(documents.deleted.containsKey(existingDestination));
    }

    @Test public void ndsExportExtractsTrackedZipAndKeepsOriginalArchive() throws Exception {
        byte[] rom = sourceBytes.clone();
        replaceSource("synthetic.zip", NdsRomExporterTest.zip(new String[]{"demo/README.txt", "demo/Demo.nds"},
                new byte[][]{new byte[]{1, 2, 3}, rom}, false));
        Uri destination = newDestination("nds-extracted");
        File output = documents.files.get(destination);
        launchNds(destination, "synthetic.nds");
        awaitFinished();

        assertTrue(ExportService.status(app), ExportService.status(app).startsWith("Export complete:"));
        assertArrayEquals(rom, Files.readAllBytes(output.toPath()));
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile.toPath()));
        assertTrue(documents.deleted.isEmpty());
    }

    @Test public void ndsExportCopiesDirectRom() throws Exception {
        replaceSource("synthetic.nds", sourceBytes);
        Uri destination = newDestination("nds-direct");
        File output = documents.files.get(destination);
        launchNds(destination, "synthetic.nds");
        awaitFinished();

        assertTrue(ExportService.status(app), ExportService.status(app).startsWith("Export complete:"));
        assertArrayEquals(sourceBytes, Files.readAllBytes(output.toPath()));
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile.toPath()));
    }

    @Test public void invalidMultiRomArchiveRemovesPartialNdsDestination() throws Exception {
        replaceSource("synthetic.zip", NdsRomExporterTest.zip(new String[]{"one.nds", "two.nds"},
                new byte[][]{sourceBytes, new byte[]{1, 2, 3}}, false));
        Uri destination = newDestination("nds-multiple");
        File output = documents.files.get(destination);
        launchNds(destination, "synthetic.nds");
        awaitFinished();

        assertTrue(ExportService.status(app), ExportService.status(app).contains("multiple DS ROMs"));
        assertFalse(output.exists());
        assertTrue(documents.deleted.containsKey(destination));
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile.toPath()));
    }

    @Test public void ndsExportDoesNotTrustCallerNameToReclassifyCia() throws Exception {
        replaceSource("synthetic.cia", sourceBytes);
        Uri destination = newDestination("nds-wrong-type");
        File output = documents.files.get(destination);
        launchNds(destination, "renamed.nds");
        awaitFinished();

        assertTrue(ExportService.status(app), ExportService.status(app).contains("completed .nds"));
        assertFalse(output.exists());
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile.toPath()));
    }

    @Test public void ndsExportNeverOverwritesExistingDestination() throws Exception {
        replaceSource("synthetic.nds", sourceBytes);
        Uri destination = newDestination("nds-existing");
        File output = documents.files.get(destination);
        byte[] existing = new byte[]{9, 8, 7};
        Files.write(output.toPath(), existing);
        launchNds(destination, "synthetic.nds");
        awaitFinished();

        assertTrue(ExportService.status(app), ExportService.status(app).contains("already contains data"));
        assertArrayEquals(existing, Files.readAllBytes(output.toPath()));
        assertFalse(documents.deleted.containsKey(destination));
    }

    @Test public void recoveryPreservesAnUnverifiedDestinationWithExistingContent() throws Exception {
        Uri destination = newDestination("recovery-existing");
        File output = documents.files.get(destination);
        byte[] existing = new byte[]{5, 6, 7};
        Files.write(output.toPath(), existing);
        app.getSharedPreferences("export_status", Context.MODE_PRIVATE).edit()
                .putBoolean("in_progress", true).putString("destination", destination.toString())
                .putBoolean("destination_owned", false).commit();
        ExportService.status(app);
        awaitFinished();

        assertArrayEquals(existing, Files.readAllBytes(output.toPath()));
        assertFalse(documents.deleted.containsKey(destination));
    }

    @Test public void recoveryRemovesOnlyAPartialDocumentOwnedByTheExport() throws Exception {
        Uri destination = newDestination("recovery-partial");
        File output = documents.files.get(destination);
        Files.write(output.toPath(), new byte[]{5, 6, 7});
        app.getSharedPreferences("export_status", Context.MODE_PRIVATE).edit()
                .putBoolean("in_progress", true).putString("destination", destination.toString())
                .putBoolean("destination_owned", true).commit();
        ExportService.status(app);
        awaitFinished();

        assertFalse(output.exists());
        assertTrue(documents.deleted.containsKey(destination));
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile.toPath()));
    }

    @Test public void cancelledNdsExportDeletesPartialDocumentAndKeepsArchive() throws Exception {
        replaceSource("synthetic.zip", NdsRomExporterTest.zip(new String[]{"demo.nds"},
                new byte[][]{sourceBytes}, false));
        Uri destination = newDestination("nds-cancelled");
        File output = documents.files.get(destination);
        CountDownLatch reading = new CountDownLatch(1);
        unblockCopy = new CountDownLatch(1);
        shadowOf(app.getContentResolver()).registerInputStreamSupplier(sourceUri, () -> {
            try {
                return new FilterInputStream(Files.newInputStream(sourceFile.toPath())) {
                    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                        reading.countDown();
                        try {
                            if (!unblockCopy.await(5, TimeUnit.SECONDS)) throw new IOException("Test gate timed out");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                        return super.read(buffer, offset, length);
                    }
                };
            } catch (IOException e) { throw new IllegalStateException(e); }
        });
        launchNds(destination, "synthetic.nds");
        assertTrue("Export never reached its source stream", reading.await(5, TimeUnit.SECONDS));
        service.destroy();
        service = null;
        awaitFinished();

        assertTrue(ExportService.status(app), ExportService.status(app).contains("interrupted"));
        assertFalse(output.exists());
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile.toPath()));
    }

    private void replaceSource(String name, byte[] bytes) throws IOException {
        sourceBytes = bytes;
        Files.write(sourceFile.toPath(), bytes);
        DownloadManager manager = app.getSystemService(DownloadManager.class);
        downloadId = new DownloadStore(app).enqueue("https://download2.erista.me/test", name,
                null, null, "https://hshop.erista.me/t/42", true);
        shadowOf(shadowOf(manager).getRequest(downloadId)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        sourceUri = manager.getUriForDownloadedFile(downloadId);
        FileProvider downloads = registerProvider(sourceUri.getAuthority());
        downloads.files.put(sourceUri, sourceFile);
        shadowOf(app.getContentResolver()).registerInputStreamSupplier(sourceUri, () -> {
            try { return Files.newInputStream(sourceFile.toPath()); }
            catch (IOException e) { throw new IllegalStateException(e); }
        });
    }

    private Uri newDestination(String id) throws IOException {
        Uri uri = Uri.parse("content://dev.thorshelf.test.documents/document/" + id);
        File file = File.createTempFile("synthetic-destination", ".bin", app.getCacheDir());
        documents.files.put(uri, file);
        shadowOf(app.getContentResolver()).registerOutputStreamSupplier(uri, () -> {
            try { return Files.newOutputStream(file.toPath()); }
            catch (IOException e) { throw new IllegalStateException(e); }
        });
        return uri;
    }

    private void launch(Uri destination) {
        ExportService.start(app, downloadId, destination, "synthetic.bin");
        launchPending();
    }

    private void launchNds(Uri destination, String name) {
        ExportService.startNds(app, downloadId, destination, name);
        launchPending();
    }

    private void launchPending() {
        Intent intent = shadowOf(app).getNextStartedService();
        assertNotNull(intent);
        service = Robolectric.buildService(ExportService.class, intent).create().startCommand(0, 1);
    }

    private void awaitFinished() throws Exception {
        await(() -> !app.getSharedPreferences("export_status", Context.MODE_PRIVATE)
                .getBoolean("in_progress", false));
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue("Asynchronous export did not finish within five seconds", condition.getAsBoolean());
    }

    private FileProvider registerProvider(String authority) {
        FileProvider provider = new FileProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = authority;
        info.exported = true;
        info.grantUriPermissions = true;
        provider.attachInfo(app, info);
        ShadowContentResolver.registerProviderInternal(authority, provider);
        return provider;
    }

    public static final class FileProvider extends ContentProvider {
        final Map<Uri, File> files = new ConcurrentHashMap<>();
        final Map<Uri, Boolean> deleted = new ConcurrentHashMap<>();
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return "application/octet-stream"; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            String[] columns = projection == null ? new String[]{OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            File file = files.get(uri);
            if (file != null && file.exists()) {
                Object[] row = new Object[columns.length];
                for (int i = 0; i < columns.length; i++) {
                    row[i] = OpenableColumns.SIZE.equals(columns[i]) ? file.length() : file.getName();
                }
                cursor.addRow(row);
            }
            return cursor;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            File file = files.get(uri);
            if (file == null) throw new FileNotFoundException();
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            if (!"android:deleteDocument".equals(method)) throw new UnsupportedOperationException(method);
            Uri uri = null;
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                if (value instanceof Uri) { uri = (Uri) value; break; }
            }
            if (uri == null) throw new IllegalArgumentException("Missing document URI");
            File file = files.get(uri);
            if (file != null && file.exists() && !file.delete()) throw new IllegalStateException("Could not delete fixture");
            deleted.put(uri, true);
            return new Bundle();
        }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
