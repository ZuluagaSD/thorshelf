package dev.thorshelf.app;

import android.app.Application;
import android.app.DownloadManager;
import android.content.Context;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
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
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Tests streams, service cancellation, and document ownership without JNI or game files. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class PreparationServiceTest {
    private Application app;
    private File sourceFile;
    private byte[] sourceBytes;
    private byte[] preparedBytes;
    private long downloadId;
    private FolderProvider documents;
    private ServiceController<PreparationService> service;
    private PreparationService.Pipeline productionPipeline;
    private CountDownLatch unblock;

    @Before public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();
        productionPipeline = PreparationService.pipeline;
        sourceBytes = new byte[300 * 1024];
        preparedBytes = new byte[256 * 1024];
        for (int i = 0; i < sourceBytes.length; i++) sourceBytes[i] = (byte) (i * 31);
        for (int i = 0; i < preparedBytes.length; i++) preparedBytes[i] = (byte) (i * 17);
        sourceFile = File.createTempFile("synthetic-cia-source", ".cia", app.getCacheDir());
        Files.write(sourceFile.toPath(), sourceBytes);
        DownloadManager manager = app.getSystemService(DownloadManager.class);
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
        downloadId = new DownloadStore(app).enqueue("https://download2.erista.me/test", "synthetic.cia",
                null, null, "https://hshop.erista.me/t/42", true);
        shadowOf(shadowOf(manager).getRequest(downloadId)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        Uri sourceUri = manager.getUriForDownloadedFile(downloadId);
        assertNotNull(sourceUri);
        registerProvider(sourceUri.getAuthority()).files.put(sourceUri, sourceFile);
        documents = new FolderProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = FolderProvider.AUTHORITY;
        info.exported = true;
        info.grantUriPermissions = true;
        documents.attachInfo(app, info);
        ShadowContentResolver.registerProviderInternal(info.authority, documents);
        shadowOf(app.getContentResolver()).registerInputStreamSupplier(sourceUri, () -> {
            try { return Files.newInputStream(sourceFile.toPath()); }
            catch (IOException error) { throw new IllegalStateException(error); }
        });
        PreparationService.pipeline = (context, input, output, scratch, progress) -> {
            assertArrayEquals(sourceBytes, Files.readAllBytes(input.toPath()));
            progress.update(65, "Preparing CIA");
            Files.write(output.toPath(), preparedBytes);
            return new PreparationService.PreparedOutput(output, false);
        };
    }

    @After public void tearDown() throws Exception {
        if (unblock != null) unblock.countDown();
        if (service != null) service.destroy();
        await(() -> !app.getSharedPreferences("preparation_status", Context.MODE_PRIVATE)
                .getBoolean("in_progress", false));
        shadowOf(Looper.getMainLooper()).idle();
        PreparationService.pipeline = productionPipeline;
    }

    @Test public void savesPreparedBytesAndPreservesOriginal() throws Exception {
        launch(documents.tree);
        awaitFinished();

        assertTrue(PreparationService.status(app), PreparationService.isReady(app));
        assertEquals(100, PreparationService.progress(app));
        assertFalse(PreparationService.requiresInstall(app));
        assertTrue(PreparationService.status(app).toLowerCase().contains("refresh"));
        assertEquals("synthetic - Azahar.3ds", documents.createdName);
        assertEquals("application/octet-stream", documents.createdMime);
        assertEquals(documents.folder, documents.createdParent);
        assertArrayEquals(preparedBytes, Files.readAllBytes(documents.createdFile().toPath()));
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile.toPath()));
        assertTrue(documents.deleted.isEmpty());
        assertWorkspaceEmpty();
    }

    @Test public void outputWriteFailureDeletesPartialCopyAndKeepsOriginal() throws Exception {
        documents.failWrite = true;
        launch(documents.tree);
        awaitFinished();

        assertFalse(PreparationService.isReady(app));
        assertTrue(PreparationService.status(app), PreparationService.status(app).contains("Could not save"));
        assertFalse(documents.createdFile().exists());
        assertFolderPreserved();
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile.toPath()));
        assertWorkspaceEmpty();
    }

    @Test public void outputCloseFailureCannotReportSuccess() throws Exception {
        documents.failClose = true;
        launch(documents.tree);
        awaitFinished();

        assertFalse(PreparationService.isReady(app));
        assertFalse(documents.createdFile().exists());
        assertFolderPreserved();
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile.toPath()));
    }

    @Test public void existingDestinationIsNeverOverwrittenOrDeleted() throws Exception {
        Uri destination = newDestination("existing");
        File output = documents.files.get(destination);
        byte[] existing = new byte[]{42, 11, 34, 66};
        Files.write(output.toPath(), existing);
        documents.createResult = destination;
        launch(documents.tree);
        awaitFinished();

        assertFalse(PreparationService.isReady(app));
        assertTrue(PreparationService.status(app), PreparationService.status(app).contains("existing file"));
        assertArrayEquals(existing, Files.readAllBytes(output.toPath()));
        assertFalse(documents.deleted.containsKey(destination));
        assertFolderPreserved();
    }

    @Test public void busyPreparationNeverCreatesOrDeletesAnUnusedFolder() throws Exception {
        Uri unusedDestination = DocumentsContract.buildTreeDocumentUri(FolderProvider.AUTHORITY, "unused-folder");
        CountDownLatch preparing = gatePipeline();
        launch(documents.tree);
        assertTrue("Pipeline did not start", preparing.await(5, TimeUnit.SECONDS));
        assertTrue(PreparationService.isRunning(app));
        try {
            PreparationService.start(app, downloadId, unusedDestination, "unused.cia");
            fail("Expected busy preparation to be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("already"));
        }
        assertEquals(0, documents.createCalls);
        assertTrue(documents.deleted.isEmpty());
        unblock.countDown();
        awaitFinished();
        assertEquals(1, documents.createCalls);
        assertArrayEquals(preparedBytes, Files.readAllBytes(documents.createdFile().toPath()));
        assertFolderPreserved();
    }

    @Test public void cancellationClearsWorkspaceAndOutputWithoutRemovingSource() throws Exception {
        CountDownLatch preparing = gatePipeline();
        launch(documents.tree);
        assertTrue("Pipeline did not start", preparing.await(5, TimeUnit.SECONDS));
        PreparationService.cancel(app);
        Intent cancellation = shadowOf(app).getNextStartedService();
        assertNotNull(cancellation);
        service.get().onStartCommand(cancellation, 0, 2);
        unblock.countDown();
        awaitFinished();

        assertFalse(PreparationService.isReady(app));
        assertTrue(PreparationService.status(app), PreparationService.status(app).contains("cancelled"));
        assertEquals(0, documents.createCalls);
        assertTrue(documents.deleted.isEmpty());
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile.toPath()));
        assertWorkspaceEmpty();
    }

    @Test public void malformedCiaIsRejectedBeforeAnyPreparedDocumentIsWritten() throws Exception {
        PreparationService.pipeline = productionPipeline;
        launch(documents.tree);
        awaitFinished();

        assertFalse(PreparationService.isReady(app));
        assertTrue(PreparationService.status(app), PreparationService.status(app).contains("not a complete, supported CIA"));
        assertEquals(0, documents.createCalls);
        assertTrue(documents.deleted.isEmpty());
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile.toPath()));
        assertWorkspaceEmpty();
    }

    @Test public void interruptedProcessRecoversOnlyItsSavedDestinationAndTemporaryFiles() throws Exception {
        Uri interrupted = newDestination("interrupted");
        Uri unrelated = newDestination("unrelated");
        Files.write(documents.files.get(interrupted).toPath(), preparedBytes);
        Files.write(documents.files.get(unrelated).toPath(), new byte[]{99});
        File abandoned = new File(app.getFilesDir(), "preparation/abandoned");
        assertTrue(abandoned.mkdirs());
        Files.write(new File(abandoned, "scratch.cia").toPath(), new byte[]{1, 2, 3});
        app.getSharedPreferences("preparation_status", Context.MODE_PRIVATE).edit()
                .putBoolean("in_progress", true).putBoolean("destination_owned", true)
                .putString("destination", interrupted.toString()).putString("folder", documents.tree.toString())
                .putString("token", "abandoned").commit();
        PreparationService.status(app);
        awaitFinished();

        assertFalse(PreparationService.isReady(app));
        assertTrue(PreparationService.status(app).contains("interrupted"));
        assertFalse(documents.files.get(interrupted).exists());
        assertArrayEquals(new byte[]{99}, Files.readAllBytes(documents.files.get(unrelated).toPath()));
        assertFolderPreserved();
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile.toPath()));
        assertWorkspaceEmpty();
    }

    @Test public void supplementalContentCreatesCiaAndExplainsInstallation() throws Exception {
        PreparationService.pipeline = (context, input, output, scratch, progress) -> {
            Files.write(output.toPath(), preparedBytes);
            return new PreparationService.PreparedOutput(output, true);
        };
        launch(documents.tree);
        awaitFinished();

        assertTrue(PreparationService.status(app), PreparationService.isReady(app));
        assertTrue(PreparationService.requiresInstall(app));
        assertTrue(PreparationService.status(app).contains("Install CIA"));
        assertEquals("synthetic - Azahar.cia", documents.createdName);
        assertArrayEquals(preparedBytes, Files.readAllBytes(documents.createdFile().toPath()));
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile.toPath()));
    }

    @Test public void olderReadyStatusDefaultsToInstallCia() {
        app.getSharedPreferences("preparation_status", Context.MODE_PRIVATE).edit()
                .putBoolean("ready", true).remove("requires_install").commit();
        assertTrue(PreparationService.requiresInstall(app));
    }

    @Test public void interruptedUnownedEmptyDocumentIsPreserved() throws Exception {
        Uri unrelated = newDestination("unowned");
        savedInterruptedDestination(unrelated, false);
        PreparationService.status(app);
        awaitFinished();

        assertTrue(documents.files.get(unrelated).exists());
        assertTrue(documents.deleted.isEmpty());
    }

    @Test public void interruptedFolderUriCannotBeDeletedEvenWithStaleOwnership() throws Exception {
        savedInterruptedDestination(documents.tree, true);
        PreparationService.status(app);
        awaitFinished();
        assertFolderPreserved();
        assertTrue(documents.deleted.isEmpty());
    }

    @Test public void providerReturningFolderInsteadOfChildCannotDeleteOrWriteFolder() throws Exception {
        documents.createResult = documents.folder;
        launch(documents.tree);
        awaitFinished();

        assertFalse(PreparationService.isReady(app));
        assertFolderPreserved();
        assertTrue(documents.deleted.isEmpty());
        assertEquals(0, documents.openedFolders);
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceFile.toPath()));
    }

    @Test public void plainDocumentUriIsRejectedBeforeStartingPreparation() throws Exception {
        Uri plain = DocumentsContract.buildDocumentUri(FolderProvider.AUTHORITY, "plain-document");
        try {
            PreparationService.start(app, downloadId, plain, "synthetic.cia");
            fail("Expected a folder tree URI to be required");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("folder"));
        }
        assertFalse(PreparationService.isRunning(app));
        assertNull(shadowOf(app).getNextStartedService());
        assertTrue(documents.deleted.isEmpty());
    }

    private void savedInterruptedDestination(Uri destination, boolean owned) {
        app.getSharedPreferences("preparation_status", Context.MODE_PRIVATE).edit()
                .putBoolean("in_progress", true).putBoolean("destination_owned", owned)
                .putString("destination", destination.toString()).putString("folder", documents.tree.toString())
                .putString("token", "abandoned").commit();
    }

    private void assertFolderPreserved() {
        assertFalse("Tree URI must never be deleted", documents.deleted.containsKey(documents.tree));
        assertFalse("Folder document must never be deleted", documents.deleted.containsKey(documents.folder));
    }

    private CountDownLatch gatePipeline() {
        CountDownLatch preparing = new CountDownLatch(1);
        unblock = new CountDownLatch(1);
        PreparationService.pipeline = (context, input, output, scratch, progress) -> {
            preparing.countDown();
            try {
                if (!unblock.await(5, TimeUnit.SECONDS)) throw new IOException("Test gate timed out");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Cancelled");
            }
            if (progress.isCancelled()) throw new InterruptedIOException("Cancelled");
            Files.write(output.toPath(), preparedBytes);
            return new PreparationService.PreparedOutput(output, false);
        };
        return preparing;
    }

    private Uri newDestination(String id) throws IOException {
        return documents.child(id);
    }

    private void launch(Uri destination) {
        PreparationService.start(app, downloadId, destination, "synthetic.cia");
        Intent intent = shadowOf(app).getNextStartedService();
        assertNotNull(intent);
        service = Robolectric.buildService(PreparationService.class, intent).create().startCommand(0, 1);
    }

    private void awaitFinished() throws Exception {
        await(() -> !app.getSharedPreferences("preparation_status", Context.MODE_PRIVATE)
                .getBoolean("in_progress", false));
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void assertWorkspaceEmpty() {
        File root = new File(app.getFilesDir(), "preparation");
        File[] children = root.listFiles();
        assertTrue("Temporary CIA files remain", children == null || children.length == 0);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue("Asynchronous preparation did not finish within five seconds", condition.getAsBoolean());
    }

    private ExportServiceTest.FileProvider registerProvider(String authority) {
        ExportServiceTest.FileProvider provider = new ExportServiceTest.FileProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = authority;
        info.exported = true;
        info.grantUriPermissions = true;
        provider.attachInfo(app, info);
        ShadowContentResolver.registerProviderInternal(authority, provider);
        return provider;
    }

    /** Models SAF createDocument so an output is created only after preparation succeeds. */
    public static final class FolderProvider extends ContentProvider {
        static final String AUTHORITY = "dev.thorshelf.test.preparation";
        final Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "roms");
        final Uri folder = DocumentsContract.buildDocumentUriUsingTree(tree, "roms");
        final Map<Uri, File> files = new ConcurrentHashMap<>();
        final Map<Uri, Boolean> deleted = new ConcurrentHashMap<>();
        volatile Uri createdUri;
        volatile Uri createdParent;
        volatile Uri createResult;
        volatile String createdName;
        volatile String createdMime;
        volatile int createCalls;
        volatile int openedFolders;
        volatile boolean failWrite;
        volatile boolean failClose;

        @Override public boolean onCreate() { return true; }

        @Override public String getType(Uri uri) {
            return isFolder(uri) ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream";
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            String[] columns = projection == null ? new String[]{OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            File file = files.get(uri);
            if (isFolder(uri) || (file != null && file.exists())) {
                Object[] row = new Object[columns.length];
                for (int i = 0; i < columns.length; i++) {
                    switch (columns[i]) {
                        case OpenableColumns.SIZE: row[i] = isFolder(uri) ? 0 : file.length(); break;
                        case DocumentsContract.Document.COLUMN_MIME_TYPE: row[i] = getType(uri); break;
                        case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                            row[i] = isFolder(uri) ? "roms" : DocumentsContract.getDocumentId(uri); break;
                        case DocumentsContract.Document.COLUMN_FLAGS:
                            row[i] = isFolder(uri) ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                                    : DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
                            break;
                        default: row[i] = isFolder(uri) ? "n3ds" : file.getName();
                    }
                }
                cursor.addRow(row);
            }
            return cursor;
        }

        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (isFolder(uri)) {
                openedFolders++;
                throw new FileNotFoundException("Cannot open a folder as a file");
            }
            File file = files.get(uri);
            if (file == null) throw new FileNotFoundException();
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        }

        @Override public Bundle call(String method, String arg, Bundle extras) {
            Uri uri = extras.getParcelable("uri");
            if ("android:createDocument".equals(method)) {
                createCalls++;
                createdParent = uri;
                createdName = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                createdMime = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE);
                if (!folder.equals(uri)) throw new IllegalArgumentException("Expected selected tree's folder document");
                try {
                    createdUri = createResult == null ? child("created-" + createCalls) : createResult;
                } catch (IOException error) { throw new IllegalStateException(error); }
                Bundle result = new Bundle();
                result.putParcelable("uri", createdUri);
                return result;
            }
            if ("android:deleteDocument".equals(method)) {
                if (uri == null) throw new IllegalArgumentException("Missing document URI");
                File file = files.get(uri);
                if (file != null && file.exists() && !file.delete()) throw new IllegalStateException("Could not delete fixture");
                deleted.put(uri, true);
                return new Bundle();
            }
            throw new UnsupportedOperationException(method);
        }

        Uri child(String id) throws IOException {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, "roms/" + id);
            File file = File.createTempFile("synthetic-prepared", ".bin", getContext().getCacheDir());
            files.put(uri, file);
            shadowOf(getContext().getContentResolver()).registerOutputStreamSupplier(uri, () -> {
                try {
                    OutputStream stream = Files.newOutputStream(file.toPath());
                    return new OutputStream() {
                        int count;
                        @Override public void write(int value) throws IOException {
                            if (failWrite && count++ >= 32) throw new IOException("Synthetic full storage");
                            stream.write(value);
                        }
                        @Override public void write(byte[] buffer, int offset, int length) throws IOException {
                            if (failWrite) super.write(buffer, offset, length);
                            else stream.write(buffer, offset, length);
                        }
                        @Override public void close() throws IOException {
                            stream.close();
                            if (failClose) throw new IOException("Synthetic remote commit failure");
                        }
                    };
                } catch (IOException error) { throw new IllegalStateException(error); }
            });
            return uri;
        }

        File createdFile() {
            assertNotNull("Provider was not asked to create an output", createdUri);
            File file = files.get(createdUri);
            assertNotNull("Output is not a file", file);
            return file;
        }

        private boolean isFolder(Uri uri) { return tree.equals(uri) || folder.equals(uri); }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
