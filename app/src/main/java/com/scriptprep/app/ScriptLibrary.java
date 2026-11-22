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

    /**
     * Saves a text without moving it: an existing one keeps its place, a new one goes on top.
     * Used for the copy of a shared text, which is refreshed every time the group opens.
     */
    void put(String id, String title, String text, TextCategory category, ScriptLanguage language) {
        List<Script> scripts = list();
        Script updated = new Script(id, title, text, category, language);
        for (int i = 0; i < scripts.size(); i++) {
            if (scripts.get(i).id.equals(id)) {
                scripts.set(i, updated);
                write(scripts);
                return;
            }
        }
        scripts.add(0, updated);
        write(scripts);
    }

    /**
     * Gives a text a new id in place, with its character voices; returns false if it is not
     * in the library. A shared text becomes the group's copy this way, and back again.
     */
    boolean rekey(String oldId, String newId, String title) {
        List<Script> scripts = list();
        for (int i = 0; i < scripts.size(); i++) {
            Script old = scripts.get(i);
            if (old.id.equals(oldId)) {
                scripts.removeIf(script -> script.id.equals(newId));
                int index = Math.min(i, scripts.size());
                scripts.removeIf(script -> script.id.equals(oldId));
                scripts.add(Math.min(index, scripts.size()), new Script(newId, title, old.text, old.category, old.language));
                write(scripts);
                String cast = VoiceCast.raw(preferences, oldId);
                if (cast != null) {
                    VoiceCast.putRaw(preferences, newId, cast);
                    VoiceCast.reset(preferences, oldId);
                }
                return true;
            }
        }
        return false;
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

    /** A backup file: every text plus its character voices. */
    String exportJson() throws JSONException {
        JSONArray texts = new JSONArray();
        JSONObject casts = new JSONObject();
        for (Script script : list()) {
            texts.put(toJson(script));
            String cast = VoiceCast.raw(preferences, script.id);
            if (cast != null) {
                casts.put(script.id, cast);
            }
        }
        return new JSONObject()
            .put("app", "memo-everything")
            .put("version", 1)
            .put("texts", texts)
            .put("voiceCasts", casts)
            .toString(2);
    }

    /**
     * Merges a backup into the library: texts with the same id are replaced, new ones are added
     * at the top, nothing is removed. Returns how many texts were imported.
     */
    int importJson(String json) throws JSONException {
        JSONObject backup = new JSONObject(json);
        JSONArray texts = backup.getJSONArray("texts");
        JSONObject casts = backup.optJSONObject("voiceCasts");
        List<Script> imported = new ArrayList<>();
        for (int i = 0; i < texts.length(); i++) {
            JSONObject entry = texts.optJSONObject(i);
            if (entry == null || entry.optString("text").isEmpty()) {
                continue;
            }
            String id = entry.optString("id");
            imported.add(new Script(id.isEmpty() ? UUID.randomUUID().toString() : id,
                entry.optString("title"), entry.optString("text"),
                TextCategory.fromSavedValue(entry.optString("category")),
                ScriptLanguage.fromCode(entry.optString("language"))));
        }
        List<Script> merged = new ArrayList<>(imported);
        for (Script existing : list()) {
            boolean replaced = false;
            for (Script incoming : imported) {
                if (incoming.id.equals(existing.id)) {
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                merged.add(existing);
            }
        }
        write(merged);
        if (casts != null) {
            for (Script script : imported) {
                String cast = casts.optString(script.id, null);
                if (cast != null) {
                    VoiceCast.putRaw(preferences, script.id, cast);
                }
            }
        }
        return imported.size();
    }

    private static JSONObject toJson(Script script) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("id", script.id);
        entry.put("title", script.title);
        entry.put("text", script.text);
        entry.put("category", script.category.name());
        entry.put("language", script.language.code);
        return entry;
    }

    private void write(List<Script> scripts) {
        JSONArray entries = new JSONArray();
        for (Script script : scripts) {
            try {
                entries.put(toJson(script));
            } catch (JSONException error) {
                throw new IllegalStateException("Could not serialize script", error);
            }
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
