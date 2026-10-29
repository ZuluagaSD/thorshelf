package dev.thorshelf.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.util.Locale;

/** Synthetic integrity metadata only; never reads or downloads a game. */
final class GbaTestData {
    static String sha1(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-1").digest(bytes)) {
            result.append(String.format(Locale.ROOT, "%02x", value & 255));
        }
        return result.toString();
    }

    static void markSynthetic(Context context, long id, byte[] expected) throws Exception {
        markSynthetic(context, id, expected.length, sha1(expected));
    }

    static void markSynthetic(Context context, long id, long expectedSize, String expectedSha1) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences("thorshelf_downloads_v1", Context.MODE_PRIVATE);
        JSONArray records = new JSONArray(preferences.getString("records", "[]"));
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            if (record.getLong("id") != id) continue;
            record.put("name", "Synthetic.gba").put("platform", "gba").put("sourcePage", GbaCatalog.HOME)
                    .put("expectedSha1", expectedSha1).put("expectedSize", expectedSize);
        }
        if (!preferences.edit().putString("records", records.toString()).commit()) {
            throw new AssertionError("Could not write synthetic history");
        }
    }
}
