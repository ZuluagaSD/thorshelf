package dev.thorshelf.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

/** The user's reusable destination, independent of any preparation currently running. */
final class PreparationFolder {
    static final String PREFERENCE = "lastPreparationFolder";

    private PreparationFolder() { }

    static Uri saved(SharedPreferences preferences) {
        String value = preferences.getString(PREFERENCE, "");
        if (value == null || value.isEmpty()) return null;
        Uri uri = Uri.parse(value);
        return isFolder(uri) ? uri : null;
    }

    static boolean isFolder(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme()) || uri.getAuthority() == null
                || !DocumentsContract.isTreeUri(uri)) return false;
        try { return !DocumentsContract.getTreeDocumentId(uri).isEmpty(); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    static boolean hasPersistedAccess(Context context, Uri folder) {
        if (!isFolder(folder)) return false;
        try {
            for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
                if (folder.equals(permission.getUri()) && permission.isReadPermission()
                        && permission.isWritePermission()) return true;
            }
        } catch (SecurityException ignored) { }
        return false;
    }

    static void retainAccess(Context context, Intent result) {
        int flags = ((result.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
                ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)
                | ((result.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
                ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
        if (flags == 0) return;
        try { context.getContentResolver().takePersistableUriPermission(result.getData(), flags); }
        catch (SecurityException ignored) {
            // A transient grant can still serve the preparation selected in this session.
        }
    }

    /** Call on a worker: even a local-looking document provider may perform remote I/O. */
    static boolean canWrite(Context context, Uri folder) {
        if (!hasPersistedAccess(context, folder)) return false;
        try {
            Uri document = DocumentsContract.buildDocumentUriUsingTree(folder,
                    DocumentsContract.getTreeDocumentId(folder));
            String[] columns = {DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_FLAGS};
            try (Cursor cursor = context.getContentResolver().query(document, columns, null, null, null)) {
                return cursor != null && cursor.moveToFirst()
                        && DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0))
                        && (cursor.getInt(1) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0;
            }
        } catch (RuntimeException ignored) { return false; }
    }

    static String label(Uri folder) {
        if (!isFolder(folder)) return "No games folder selected";
        String id = DocumentsContract.getTreeDocumentId(folder);
        if ("com.android.externalstorage.documents".equals(folder.getAuthority())) {
            int separator = id.indexOf(':');
            if (separator >= 0) {
                String volume = id.substring(0, separator);
                String path = id.substring(separator + 1);
                return ("primary".equals(volume) ? "Internal storage" : "Storage " + volume)
                        + (path.isEmpty() ? "" : " / " + path);
            }
        }
        return "Selected games folder";
    }
}
