package dev.voxferrum;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

final class ClipNotes {
    private static SharedPreferences db(Context c) { return c.getSharedPreferences("clip_notes", Context.MODE_PRIVATE); }

    static boolean favorite(Context c, Uri id) { return db(c).getBoolean("fav/" + id, false); }
    static void favorite(Context c, Uri id, boolean value) {
        db(c).edit().putBoolean("fav/" + id, value).apply();
    }
    static void store(Context c, Uri id, List<Integer> marks) {
        if (!marks.isEmpty()) db(c).edit().putString("marks/" + id, new JSONArray(marks).toString()).apply();
    }
    static List<Integer> marks(Context c, Uri id) {
        ArrayList<Integer> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(db(c).getString("marks/" + id, "[]"));
            for (int i=0; i<values.length(); i++) result.add(values.getInt(i));
        } catch (org.json.JSONException ignored) { }
        return result;
    }
    static void remove(Context c, Uri id) {
        db(c).edit().remove("marks/" + id).remove("fav/" + id).apply();
    }
}
