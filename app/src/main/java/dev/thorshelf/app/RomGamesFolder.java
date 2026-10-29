package dev.thorshelf.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Shared SAF destination ownership checks for DS and GBA exports. */
final class RomGamesFolder {

    private RomGamesFolder() { }

    static Uri saved(SharedPreferences preferences, String preference) {
        String value = preferences.getString(preference, "");
        if (value == null || value.isEmpty()) return null;
        Uri uri = Uri.parse(value);
        return PreparationFolder.isFolder(uri) ? uri : null;
    }

    /** Call on a worker: document providers may perform remote I/O. */
    static void startExport(Context context, long downloadId, Uri folder, String name, GamePlatform platform) throws IOException {
        if (platform != GamePlatform.DS && platform != GamePlatform.GBA) throw new IllegalArgumentException("Unsupported ROM platform.");
        String label = platform.shortLabel;
        if (downloadId <= 0) throw new IOException("Choose a completed " + label + " download to save.");
        if (!PreparationFolder.hasPersistedAccess(context, folder)
                || !PreparationFolder.canWrite(context, folder)) {
            throw new IOException("Choose the " + label + " games folder again so ThorShelf can save files there.");
        }
        ContentResolver resolver = context.getContentResolver();
        String filename = GameFilename.withExtension(name, platform.extension);
        Uri destination;
        try {
            String rootId = DocumentsContract.getTreeDocumentId(folder);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(folder, rootId);
            Set<String> existing = existingChildren(resolver, folder, rootId, label);
            destination = DocumentsContract.createDocument(resolver, parent,
                    "application/octet-stream", filename);
            // A faulty provider may return the root or an existing empty file. Neither is ours
            // to overwrite or to pass to the service's failure/busy cleanup.
            if (destination == null || !"content".equals(destination.getScheme())
                    || !folder.getAuthority().equals(destination.getAuthority())
                    || !DocumentsContract.isTreeUri(destination)
                    || !rootId.equals(DocumentsContract.getTreeDocumentId(destination))) {
                throw unsafeDestination(label);
            }
            String documentId = DocumentsContract.getDocumentId(destination);
            if (documentId.isEmpty() || rootId.equals(documentId) || existing.contains(documentId)) {
                throw unsafeDestination(label);
            }
            String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
            try (Cursor cursor = resolver.query(destination, columns, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst() || !documentId.equals(cursor.getString(0))
                        || cursor.isNull(1) || DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(1))
                        || cursor.isNull(2) || cursor.getLong(2) != 0) {
                    throw unsafeDestination(label);
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("Could not create a new " + label + " file. Choose the " + label + " games folder again.", error);
        }
        // From here the existing exporter owns this new, empty document and any cleanup.
        // Its child URI differs from the persisted tree URI, so the reusable grant is retained.
        if (platform == GamePlatform.GBA) ExportService.startGba(context, downloadId, destination, filename);
        else ExportService.startNds(context, downloadId, destination, filename);
    }

    private static Set<String> existingChildren(ContentResolver resolver, Uri folder, String rootId, String label)
            throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, rootId);
        Set<String> ids = new HashSet<>();
        try (Cursor cursor = resolver.query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            if (cursor == null || cursor.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                throw new IOException("Could not check the " + label + " games folder. Wait for it to load and try again.");
            }
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                if (id == null || id.isEmpty()) throw unsafeDestination(label);
                ids.add(id);
            }
        }
        return ids;
    }

    private static IOException unsafeDestination(String label) {
        return new IOException("Could not confirm a new, empty " + label + " file. Choose another games folder or document provider.");
    }
}
