package com.scriptprep.app;

import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Premium: a distinct device voice and tone for every character of a text. */
final class VoiceCast {
    enum Tone {
        LOW(0.8f), NORMAL(1f), HIGH(1.2f);

        final float pitch;

        Tone(float pitch) {
            this.pitch = pitch;
        }
    }

    enum Pace {
        SLOW(0.85f), NORMAL(1f), FAST(1.15f);

        final float rate;

        Pace(float rate) {
            this.rate = rate;
        }
    }

    /**
     * How one character sounds. An empty voice name means the default device voice; the speaker
     * is the character's voice when the on-device AI voice is used.
     */
    static final class Part {
        final String voice;
        final int speaker;
        final Tone tone;
        final Pace pace;

        Part(String voice, int speaker, Tone tone, Pace pace) {
            this.voice = voice == null ? "" : voice;
            this.speaker = speaker;
            this.tone = tone;
            this.pace = pace;
        }

        Part withVoice(String value) {
            return new Part(value, speaker, tone, pace);
        }

        Part withSpeaker(int value) {
            return new Part(voice, value, tone, pace);
        }

        Part withTone(Tone value) {
            return new Part(voice, speaker, value, pace);
        }

        Part withPace(Pace value) {
            return new Part(voice, speaker, tone, value);
        }
    }

    private static final String PREFIX = "voice_cast_";
    private static final Tone[] TONE_CYCLE = {Tone.NORMAL, Tone.LOW, Tone.HIGH};
    /** AI speakers 0–4 are female and 5–9 male. */
    private static final int HALF = 5;

    private VoiceCast() {}

    /**
     * Gives every character a different voice where enough voices are installed and
     * varies the tone so that characters sharing a voice still sound apart.
     */
    static Map<String, Part> automatic(List<String> roles, List<String> voices) {
        return automatic(roles, voices, java.util.Collections.emptyMap());
    }

    /**
     * As above, with the characters' likely genders ("female" or "male", by role) where they are
     * known: women take the female speakers 0–4 in turn, men the male speakers 5–9, and the others
     * whichever gender has fewer so far, so an unknown cast alternates as before.
     */
    static Map<String, Part> automatic(List<String> roles, List<String> voices, Map<String, String> genders) {
        Map<String, Part> cast = new LinkedHashMap<>();
        int women = 0;
        int men = 0;
        for (int i = 0; i < roles.size(); i++) {
            String voice = voices.isEmpty() || i == 0 ? "" : voices.get((i - 1) % voices.size());
            int round = voices.isEmpty() ? i : i / (voices.size() + 1);
            String gender = genders.get(roles.get(i));
            boolean female = "female".equals(gender) || (!"male".equals(gender) && women <= men);
            int speaker = female ? women++ % HALF : HALF + men++ % HALF;
            // Tone only shapes device voices; the AI voice has ten distinct speakers instead.
            cast.put(roles.get(i), new Part(voice, speaker, TONE_CYCLE[round % TONE_CYCLE.length], Pace.NORMAL));
        }
        return cast;
    }

    /** The saved cast completed with automatic choices for characters added since. */
    static Map<String, Part> load(SharedPreferences preferences, String key, List<String> roles,
                                  List<String> voices) {
        return load(preferences, key, roles, voices, java.util.Collections.emptyMap());
    }

    static Map<String, Part> load(SharedPreferences preferences, String key, List<String> roles,
                                  List<String> voices, Map<String, String> genders) {
        Map<String, Part> saved = fromJson(preferences.getString(PREFIX + key, null));
        Map<String, Part> defaults = automatic(roles, voices, genders);
        Map<String, Part> cast = new LinkedHashMap<>();
        for (String role : roles) {
            Part part = saved.get(role);
            cast.put(role, part != null ? part : defaults.get(role));
        }
        return cast;
    }

    static void save(SharedPreferences preferences, String key, Map<String, Part> cast) {
        preferences.edit().putString(PREFIX + key, toJson(cast)).apply();
    }

    /** The stored cast of a text as saved, for backups; null when it has none. */
    static String raw(SharedPreferences preferences, String key) {
        return preferences.getString(PREFIX + key, null);
    }

    static void putRaw(SharedPreferences preferences, String key, String json) {
        preferences.edit().putString(PREFIX + key, json).apply();
    }

    static void reset(SharedPreferences preferences, String key) {
        preferences.edit().remove(PREFIX + key).apply();
    }

    /** Prepares the engine to read a line of the given character. */
    static void apply(TextToSpeech speech, Part part, List<Voice> voices, Voice fallback, Locale locale) {
        Voice chosen = null;
        if (part != null && !part.voice.isEmpty()) {
            for (Voice voice : voices) {
                if (voice.getName().equals(part.voice)) {
                    chosen = voice;
                    break;
                }
            }
        }
        if (chosen == null) {
            chosen = fallback;
        }
        if (chosen == null || speech.setVoice(chosen) != TextToSpeech.SUCCESS) {
            speech.setLanguage(locale);
        }
        speech.setPitch(part == null ? 1f : part.tone.pitch);
        speech.setSpeechRate(part == null ? 1f : part.pace.rate);
    }

    static String toJson(Map<String, Part> cast) {
        JSONObject json = new JSONObject();
        try {
            for (Map.Entry<String, Part> entry : cast.entrySet()) {
                JSONObject part = new JSONObject();
                part.put("voice", entry.getValue().voice);
                part.put("speaker", entry.getValue().speaker);
                part.put("tone", entry.getValue().tone.name());
                part.put("pace", entry.getValue().pace.name());
                json.put(entry.getKey(), part);
            }
        } catch (JSONException error) {
            throw new IllegalStateException("Could not serialize the voice cast", error);
        }
        return json.toString();
    }

    static Map<String, Part> fromJson(String value) {
        Map<String, Part> cast = new LinkedHashMap<>();
        if (value == null || value.isEmpty()) {
            return cast;
        }
        try {
            JSONObject json = new JSONObject(value);
            Iterator<String> roles = json.keys();
            while (roles.hasNext()) {
                String role = roles.next();
                JSONObject part = json.optJSONObject(role);
                if (part != null) {
                    cast.put(role, new Part(part.optString("voice"),
                        Math.max(0, Math.min(NeuralVoice.SPEAKERS - 1, part.optInt("speaker", cast.size()
                            % NeuralVoice.SPEAKERS))),
                        parse(Tone.class, part.optString("tone"), Tone.NORMAL),
                        parse(Pace.class, part.optString("pace"), Pace.NORMAL)));
                }
            }
        } catch (JSONException ignored) {
            // A damaged cast falls back to automatic voices.
        }
        return cast;
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String value, T fallback) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException error) {
            return fallback;
        }
    }
}
