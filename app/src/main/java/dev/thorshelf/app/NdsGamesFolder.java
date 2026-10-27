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

/** The reusable DS destination, separate from the folder used for 3DS games. */
final class NdsGamesFolder {
    static final String PREFERENCE = "ndsGamesFolder";

    private NdsGamesFolder() { }

    static Uri saved(SharedPreferences preferences) {
        String value = preferences.getString(PREFERENCE, "");
        if (value == null || value.isEmpty()) return null;
        Uri uri = Uri.parse(value);
        return PreparationFolder.isFolder(uri) ? uri : null;
    }

    /** Call on a worker: document providers may perform remote I/O. */
    static void startExport(Context context, long downloadId, Uri folder, String name) throws IOException {
        if (downloadId <= 0) throw new IOException("Choose a completed DS download to save.");
        if (!PreparationFolder.hasPersistedAccess(context, folder)
                || !PreparationFolder.canWrite(context, folder)) {
            throw new IOException("Choose the DS games folder again so ThorShelf can save files there.");
        }
        ContentResolver resolver = context.getContentResolver();
        String filename = GameFilename.withExtension(name, "nds");
        Uri destination;
        try {
            String rootId = DocumentsContract.getTreeDocumentId(folder);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(folder, rootId);
            Set<String> existing = existingChildren(resolver, folder, rootId);
            destination = DocumentsContract.createDocument(resolver, parent,
                    "application/octet-stream", filename);
            // A faulty provider may return the root or an existing empty file. Neither is ours
            // to overwrite or to pass to the service's failure/busy cleanup.
            if (destination == null || !"content".equals(destination.getScheme())
                    || !folder.getAuthority().equals(destination.getAuthority())
                    || !DocumentsContract.isTreeUri(destination)
                    || !rootId.equals(DocumentsContract.getTreeDocumentId(destination))) {
                throw unsafeDestination();
            }
            String documentId = DocumentsContract.getDocumentId(destination);
            if (documentId.isEmpty() || rootId.equals(documentId) || existing.contains(documentId)) {
                throw unsafeDestination();
            }
            String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
            try (Cursor cursor = resolver.query(destination, columns, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst() || !documentId.equals(cursor.getString(0))
                        || cursor.isNull(1) || DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(1))
                        || cursor.isNull(2) || cursor.getLong(2) != 0) {
                    throw unsafeDestination();
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("Could not create a new DS file. Choose the DS games folder again.", error);
        }
        // From here the existing exporter owns this new, empty document and any cleanup.
        // Its child URI differs from the persisted tree URI, so the reusable grant is retained.
        ExportService.startNds(context, downloadId, destination, filename);
    }

    private static Set<String> existingChildren(ContentResolver resolver, Uri folder, String rootId)
            throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, rootId);
        Set<String> ids = new HashSet<>();
        try (Cursor cursor = resolver.query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            if (cursor == null || cursor.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                throw new IOException("Could not check the DS games folder. Wait for it to load and try again.");
            }
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                if (id == null || id.isEmpty()) throw unsafeDestination();
                ids.add(id);
            }
        }
        return ids;
    }

    private static IOException unsafeDestination() {
        return new IOException("Could not confirm a new, empty DS file. Choose another games folder or document provider.");
    }
}
