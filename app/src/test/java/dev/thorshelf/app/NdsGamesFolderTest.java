package dev.thorshelf.app;

import android.app.Application;
import android.app.DownloadManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class NdsGamesFolderTest {
    private Application app;
    private FolderProvider documents;
    private long downloadId;
    private File source;
    private byte[] archive;
    private final byte[] rom = {4, 8, 15, 16, 23, 42};
    private ServiceController<ExportService> service;

    @Before public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();
        documents = new FolderProvider();
        register(documents, FolderProvider.AUTHORITY);
        app.getContentResolver().takePersistableUriPermission(documents.tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        archive = NdsRomExporterTest.zip(new String[]{"demo/Demo.nds"}, new byte[][]{rom}, false);
        source = File.createTempFile("nds-games-folder-source", ".zip", app.getCacheDir());
        Files.write(source.toPath(), archive);
        DownloadManager manager = app.getSystemService(DownloadManager.class);
        manager.enqueue(new DownloadManager.Request(Uri.parse("https://example.invalid/unrelated")));
        downloadId = new DownloadStore(app).enqueueNds(
                "https://github.com/owner/game/releases/download/v1/Demo.zip", "Demo.zip",
                null, NdsCatalog.HOME, false);
        shadowOf(shadowOf(manager).getRequest(downloadId)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        Uri sourceUri = manager.getUriForDownloadedFile(downloadId);
        ExportServiceTest.FileProvider downloads = new ExportServiceTest.FileProvider();
        register(downloads, sourceUri.getAuthority());
        downloads.files.put(sourceUri, source);
        shadowOf(app.getContentResolver()).registerInputStreamSupplier(sourceUri, () -> {
            try { return Files.newInputStream(source.toPath()); }
            catch (IOException error) { throw new IllegalStateException(error); }
        });
    }

    @After public void tearDown() throws Exception {
        if (service == null) {
            Intent pending = shadowOf(app).getNextStartedService();
            if (pending != null) service = Robolectric.buildService(ExportService.class, pending)
                    .create().startCommand(0, 1);
        }
        if (service != null) service.destroy();
        awaitFinished();
    }

    @Test public void savedFolderUsesIndependentPreferenceAndRejectsFileUris() {
        SharedPreferences preferences = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        Uri threeDs = DocumentsContract.buildTreeDocumentUri(FolderProvider.AUTHORITY, "3ds");
        preferences.edit().putString(PreparationFolder.PREFERENCE, threeDs.toString()).commit();
        assertNull(NdsGamesFolder.saved(preferences));
        preferences.edit().putString(NdsGamesFolder.PREFERENCE, documents.tree.toString()).commit();
        assertEquals(documents.tree, NdsGamesFolder.saved(preferences));
        assertEquals(threeDs, PreparationFolder.saved(preferences));
        preferences.edit().putString(NdsGamesFolder.PREFERENCE, "file:///sdcard/DS").commit();
        assertNull(NdsGamesFolder.saved(preferences));
        preferences.edit().putString(NdsGamesFolder.PREFERENCE,
                DocumentsContract.buildDocumentUri(FolderProvider.AUTHORITY, "file").toString()).commit();
        assertNull(NdsGamesFolder.saved(preferences));
    }

    @Test public void createsNdsChildAndRoutesZipThroughExporterWhileRetainingTreeGrant() throws Exception {
        startAndFinish();
        assertEquals(documents.folder, documents.createdParent);
        assertEquals("Demo.nds", documents.requestedName);
        assertEquals("application/octet-stream", documents.requestedMime);
        assertArrayEquals(rom, Files.readAllBytes(documents.files.get(documents.created).toPath()));
        assertArrayEquals(archive, Files.readAllBytes(source.toPath()));
        assertEquals(documents.created, new DownloadStore(app).savedDsUri(downloadId));
        assertTrue(PreparationFolder.hasPersistedAccess(app, documents.tree));
        assertEquals(1, app.getContentResolver().getPersistedUriPermissions().size());
        assertEquals(0, documents.deleteCalls);
    }

    @Test public void gbaFolderPreferenceIsIndependentOfDsAndPreparationFolders() {
        SharedPreferences preferences = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        Uri ds = DocumentsContract.buildTreeDocumentUri(FolderProvider.AUTHORITY, "ds");
        Uri threeDs = DocumentsContract.buildTreeDocumentUri(FolderProvider.AUTHORITY, "3ds");
        preferences.edit().putString(NdsGamesFolder.PREFERENCE, ds.toString())
                .putString(PreparationFolder.PREFERENCE, threeDs.toString()).commit();
        assertNull(GbaGamesFolder.saved(preferences));
        preferences.edit().putString(GbaGamesFolder.PREFERENCE, documents.tree.toString()).commit();
        assertEquals(documents.tree, GbaGamesFolder.saved(preferences));
        assertEquals(ds, NdsGamesFolder.saved(preferences));
        assertEquals(threeDs, PreparationFolder.saved(preferences));
        preferences.edit().putString(GbaGamesFolder.PREFERENCE, "file:///sdcard/GBA").commit();
        assertNull(GbaGamesFolder.saved(preferences));
    }

    @Test public void createsGbaChildAndRoutesThroughVerifiedExporterWhileRetainingTreeGrant() throws Exception {
        Files.write(source.toPath(), rom);
        GbaTestData.markSynthetic(app, downloadId, rom);
        GbaGamesFolder.startExport(app, downloadId, documents.tree, "Demo.zip");
        Intent intent = shadowOf(app).getNextStartedService();
        assertNotNull(intent);
        assertEquals("gba", intent.getStringExtra("rom_platform"));
        assertFalse(intent.getBooleanExtra("nds", false));
        assertEquals("Demo.gba", intent.getStringExtra("name"));
        service = Robolectric.buildService(ExportService.class, intent).create().startCommand(0, 1);
        awaitFinished();
        assertTrue(ExportService.status(app), ExportService.status(app).startsWith("Export complete:"));
        assertEquals("Demo.gba", documents.requestedName);
        assertArrayEquals(rom, Files.readAllBytes(documents.files.get(documents.created).toPath()));
        assertArrayEquals(rom, Files.readAllBytes(source.toPath()));
        assertEquals(documents.created, new DownloadStore(app).savedGbaUri(downloadId));
        assertNull(new DownloadStore(app).savedDsUri(downloadId));
        assertTrue(PreparationFolder.hasPersistedAccess(app, documents.tree));
        assertEquals(1, app.getContentResolver().getPersistedUriPermissions().size());
        assertEquals(0, documents.deleteCalls);
    }

    @Test public void gbaFolderRejectsAnExistingEmptyDocumentWithoutChangingIt() throws Exception {
        Uri existing = documents.add("Existing.gba", new byte[0]);
        documents.resultOverride = existing;
        assertThrows(IOException.class,
                () -> GbaGamesFolder.startExport(app, downloadId, documents.tree, "Demo.gba"));
        assertNull(shadowOf(app).getNextStartedService());
        assertEquals(0, documents.deleteCalls);
        assertTrue(documents.files.get(existing).exists());
        assertEquals(0, documents.files.get(existing).length());
        assertArrayEquals(archive, Files.readAllBytes(source.toPath()));
    }

    @Test public void providerResolvesFilenameCollisionWithoutChangingExistingGame() throws Exception {
        Uri existing = documents.add("Demo.nds", new byte[]{7, 9});
        startAndFinish();
        assertEquals("Demo (2).nds", documents.names.get(documents.created));
        assertNotEquals(existing, documents.created);
        assertArrayEquals(new byte[]{7, 9}, Files.readAllBytes(documents.files.get(existing).toPath()));
        assertArrayEquals(rom, Files.readAllBytes(documents.files.get(documents.created).toPath()));
        assertEquals(0, documents.deleteCalls);
    }

    @Test public void revokedTreeAccessRequiresSelectionBeforeCreatingFile() throws Exception {
        app.getContentResolver().releasePersistableUriPermission(documents.tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        assertRejected();
        assertEquals(0, documents.createCalls);
    }

    @Test public void readOnlyFolderIsRejectedBeforeCreatingFile() throws Exception {
        documents.writable = false;
        assertRejected();
        assertEquals(0, documents.createCalls);
    }

    @Test public void existingEmptyDocumentReturnedByProviderIsNeverExportedOrDeleted() throws Exception {
        Uri existing = documents.add("Demo.nds", new byte[0]);
        documents.resultOverride = existing;
        assertRejected();
        assertTrue(documents.files.get(existing).exists());
        assertEquals(0, documents.files.get(existing).length());
    }

    @Test public void existingNonemptyDocumentReturnedByProviderIsNeverExportedOrDeleted() throws Exception {
        Uri existing = documents.add("Demo.nds", new byte[]{7, 9});
        documents.resultOverride = existing;
        assertRejected();
        assertArrayEquals(new byte[]{7, 9}, Files.readAllBytes(documents.files.get(existing).toPath()));
    }

    @Test public void returnedTreeRootIsNeverExportedOrDeleted() throws Exception {
        documents.resultOverride = documents.folder;
        assertRejected();
    }

    @Test public void returnedDocumentFromAnotherTreeIsNeverExportedOrDeleted() throws Exception {
        Uri otherTree = DocumentsContract.buildTreeDocumentUri(FolderProvider.AUTHORITY, "other");
        documents.resultOverride = DocumentsContract.buildDocumentUriUsingTree(otherTree, "other/file");
        assertRejected();
    }

    @Test public void returnedNullDocumentIsRejected() throws Exception {
        documents.returnNull = true;
        assertRejected();
    }

    @Test public void unknownNewDocumentSizeIsPreservedInsteadOfExported() throws Exception {
        documents.unknownSize = true;
        assertRejected();
        assertTrue(documents.files.get(documents.created).exists());
    }

    @Test public void unexpectedlyNonemptyNewDocumentIsPreservedInsteadOfExported() throws Exception {
        documents.newContent = new byte[]{7, 9};
        assertRejected();
        assertArrayEquals(documents.newContent, Files.readAllBytes(documents.files.get(documents.created).toPath()));
    }

    @Test public void incompleteChildListingCannotEstablishNewFileOwnership() throws Exception {
        documents.loading = true;
        assertRejected();
        assertEquals(0, documents.createCalls);
    }

    private void startAndFinish() throws Exception {
        NdsGamesFolder.startExport(app, downloadId, documents.tree, "Demo.zip");
        Intent intent = shadowOf(app).getNextStartedService();
        assertNotNull(intent);
        service = Robolectric.buildService(ExportService.class, intent).create().startCommand(0, 1);
        assertEquals(ExportService.class.getName(), intent.getComponent().getClassName());
        assertEquals(downloadId, intent.getLongExtra("download_id", -1));
        assertEquals(documents.created.toString(), intent.getStringExtra("destination"));
        assertEquals("Demo.nds", intent.getStringExtra("name"));
        assertTrue(intent.getBooleanExtra("nds", false));
        awaitFinished();
        assertTrue(ExportService.status(app), ExportService.status(app).startsWith("Export complete:"));
    }

    private void assertRejected() throws Exception {
        try {
            NdsGamesFolder.startExport(app, downloadId, documents.tree, "Demo.zip");
            fail("Unsafe destination should have been rejected");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
        assertNull(shadowOf(app).getNextStartedService());
        assertEquals(0, documents.deleteCalls);
        assertArrayEquals(archive, Files.readAllBytes(source.toPath()));
    }

    private void awaitFinished() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        SharedPreferences state = app.getSharedPreferences("export_status", Context.MODE_PRIVATE);
        while (state.getBoolean("in_progress", false) && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse("Export did not finish", state.getBoolean("in_progress", false));
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void register(ContentProvider provider, String authority) {
        ProviderInfo info = new ProviderInfo();
        info.authority = authority;
        info.exported = true;
        info.grantUriPermissions = true;
        provider.attachInfo(app, info);
        ShadowContentResolver.registerProviderInternal(authority, provider);
    }

    public static final class FolderProvider extends ContentProvider {
        static final String AUTHORITY = "dev.thorshelf.test.ds-folder";
        final Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "ds");
        final Uri folder = DocumentsContract.buildDocumentUriUsingTree(tree, "ds");
        final Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "ds");
        final Map<Uri, File> files = new LinkedHashMap<>();
        final Map<Uri, String> names = new LinkedHashMap<>();
        Uri resultOverride;
        Uri created;
        Uri createdParent;
        String requestedName;
        String requestedMime;
        boolean writable = true;
        boolean returnNull;
        boolean unknownSize;
        boolean loading;
        byte[] newContent = new byte[0];
        int createCalls;
        int deleteCalls;

        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) {
            return folder.equals(uri) ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream";
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            String[] columns = projection == null ? new String[]{OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            if (children.equals(uri)) {
                for (Uri child : files.keySet()) cursor.addRow(new Object[]{DocumentsContract.getDocumentId(child)});
                Bundle extras = new Bundle();
                extras.putBoolean(DocumentsContract.EXTRA_LOADING, loading);
                cursor.setExtras(extras);
            } else if (folder.equals(uri) || files.containsKey(uri)) {
                Object[] row = new Object[columns.length];
                for (int i = 0; i < columns.length; i++) {
                    switch (columns[i]) {
                        case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                            row[i] = DocumentsContract.getDocumentId(uri); break;
                        case DocumentsContract.Document.COLUMN_MIME_TYPE: row[i] = getType(uri); break;
                        case DocumentsContract.Document.COLUMN_FLAGS:
                            row[i] = writable ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : 0; break;
                        case OpenableColumns.SIZE:
                            if (folder.equals(uri)) row[i] = 0L;
                            else if (!unknownSize) row[i] = files.get(uri).length();
                            break;
                        default: row[i] = names.get(uri);
                    }
                }
                cursor.addRow(row);
            }
            return cursor;
        }

        @Override public Bundle call(String method, String arg, Bundle extras) {
            if ("android:createDocument".equals(method)) {
                createCalls++;
                createdParent = extras.getParcelable("uri");
                requestedName = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                requestedMime = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE);
                assertEquals(folder, createdParent);
                try {
                    if (returnNull) created = null;
                    else if (resultOverride != null) created = resultOverride;
                    else {
                        String name = requestedName;
                        int collision = 2;
                        while (names.containsValue(name)) name = GameFilename.numbered(requestedName, collision++);
                        created = add(name, newContent);
                    }
                } catch (IOException error) { throw new IllegalStateException(error); }
                Bundle result = new Bundle();
                result.putParcelable("uri", created);
                return result;
            }
            if ("android:deleteDocument".equals(method)) {
                deleteCalls++;
                Uri uri = extras.getParcelable("uri");
                File file = files.get(uri);
                if (file != null && file.exists() && !file.delete()) throw new IllegalStateException();
                return new Bundle();
            }
            throw new UnsupportedOperationException(method);
        }

        Uri add(String name, byte[] bytes) throws IOException {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, "ds/file-" + files.size());
            File file = File.createTempFile("nds-games-folder", ".nds", getContext().getCacheDir());
            Files.write(file.toPath(), bytes);
            files.put(uri, file);
            names.put(uri, name);
            shadowOf(getContext().getContentResolver()).registerOutputStreamSupplier(uri, () -> {
                try { return Files.newOutputStream(file.toPath()); }
                catch (IOException error) { throw new IllegalStateException(error); }
            });
            return uri;
        }

        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            File file = files.get(uri);
            if (file == null) throw new FileNotFoundException();
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
