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
                        entry.optString("id"), entry.optString("title"), entry.optString("text"),
                        TextCategory.fromSavedValue(entry.optString("category")),
                        ScriptLanguage.fromCode(entry.optString("language"))
                    ));
                }
            }
        } catch (JSONException ignored) {
            // Invalid stored data should not prevent the app from opening.
        }
        return scripts;
    }

    String save(String id, String title, String text, TextCategory category, ScriptLanguage language) {
        String scriptId = id == null ? UUID.randomUUID().toString() : id;
        List<Script> scripts = list();
        for (int i = 0; i < scripts.size(); i++) {
            if (scripts.get(i).id.equals(scriptId)) {
                scripts.remove(i);
                break;
            }
        }
        scripts.add(0, new Script(scriptId, title, text, category, language));
        write(scripts);
        return scriptId;
    }

    /** Removes a saved text and returns its former position, or -1 if it was not found. */
    int delete(String id) {
        List<Script> scripts = list();
        for (int i = 0; i < scripts.size(); i++) {
            if (scripts.get(i).id.equals(id)) {
                scripts.remove(i);
                write(scripts);
                return i;
            }
        }
        return -1;
    }

    /** Puts a deleted text back at its former position. */
    void restore(Script script, int index) {
        List<Script> scripts = list();
        for (Script existing : scripts) {
            if (existing.id.equals(script.id)) {
                return;
            }
        }
        scripts.add(Math.max(0, Math.min(index, scripts.size())), script);
        write(scripts);
    }

    private void write(List<Script> scripts) {
        JSONArray entries = new JSONArray();
        for (Script script : scripts) {
            JSONObject entry = new JSONObject();
            try {
                entry.put("id", script.id);
                entry.put("title", script.title);
                entry.put("text", script.text);
                entry.put("category", script.category.name());
                entry.put("language", script.language.code);
            } catch (JSONException error) {
                throw new IllegalStateException("Could not serialize script", error);
            }
            entries.put(entry);
        }
        preferences.edit().putString(SCRIPTS, entries.toString()).apply();
    }

    static final class Script {
        final String id;
        final String title;
        final String text;
        final TextCategory category;
        final ScriptLanguage language;

        Script(String id, String title, String text) {
            this(id, title, text, TextCategory.ROLE_REHEARSAL, ScriptLanguage.TURKISH);
        }

        Script(String id, String title, String text, TextCategory category, ScriptLanguage language) {
            this.id = id;
            this.title = title;
            this.text = text;
            this.category = category;
            this.language = language;
        }
    }
}
