package com.scriptprep.app;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ScriptLibrary {
    private static final String SCRIPTS = "scripts";
    private final SharedPreferences preferences;

    ScriptLibrary(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    List<Script> list() {
        List<Script> scripts = new ArrayList<>();
        try {
            JSONArray entries = new JSONArray(preferences.getString(SCRIPTS, "[]"));
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry != null && !entry.optString("id").isEmpty()) {
                    scripts.add(new Script(
                        entry.optString("id"), entry.optString("title"), entry.optString("text")
                    ));
                }
            }
        } catch (JSONException ignored) {
            // Invalid stored data should not prevent the app from opening.
        }
        return scripts;
    }

    String save(String id, String title, String text) {
        String scriptId = id == null ? UUID.randomUUID().toString() : id;
        List<Script> scripts = list();
        for (int i = 0; i < scripts.size(); i++) {
            if (scripts.get(i).id.equals(scriptId)) {
                scripts.remove(i);
                break;
            }
        }
        scripts.add(0, new Script(scriptId, title, text));

        JSONArray entries = new JSONArray();
        for (Script script : scripts) {
            JSONObject entry = new JSONObject();
            try {
                entry.put("id", script.id);
                entry.put("title", script.title);
                entry.put("text", script.text);
            } catch (JSONException error) {
                throw new IllegalStateException("Could not serialize script", error);
            }
            entries.put(entry);
        }
        preferences.edit().putString(SCRIPTS, entries.toString()).apply();
        return scriptId;
    }

    static final class Script {
        final String id;
        final String title;
        final String text;

        Script(String id, String title, String text) {
            this.id = id;
            this.title = title;
            this.text = text;
        }
    }
}
