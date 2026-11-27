package com.scriptprep.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Premium studio voices: an AI director reads the scene and notes how each line is played, then
 * Gemini voices each partner line with that delivery. Both run through the Memo Everything Worker
 * (worker/), which holds the Gemini key. Each line's audio is generated once and kept on the phone,
 * so later rehearsals play it offline.
 */
final class StudioVoices {
    static final String WORKER = "https://memo-studio.cayildiz01.workers.dev";
    /** Gemini voices given to characters in turn, alternating female and male voices. */
    static final String[] VOICES = {"Kore", "Puck", "Aoede", "Charon", "Leda", "Fenrir", "Zephyr", "Orus",
        "Despina", "Iapetus", "Callirrhoe", "Enceladus", "Autonoe", "Algenib", "Erinome", "Alnilam"};
    private static final String TAG = "ScriptPrep";
    private static final int LINES_PER_REQUEST = 60;
    private static final String REFRESH = "studio_refresh_token";

    enum Stop { DONE, CANCELLED, DAILY_LIMIT, MONTHLY_LIMIT, FAILED }

    interface Progress {
        /** Lines with studio audio so far, of all partner lines of the scene. */
        void progress(int done, int total);

        void finished(Stop reason);
    }

    private static StudioVoices instance;

    private final Context context;
    private final SharedPreferences preferences;
    private final File folder;
    private final File notesFile;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private JSONObject notes;
    private String idToken;
    private long idTokenExpires;
    private volatile boolean cancelled;
    private volatile boolean running;

    private StudioVoices(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("studio_voices", Context.MODE_PRIVATE);
        folder = new File(this.context.getFilesDir(), "studio");
        notesFile = new File(folder, "notes.json");
    }

    static synchronized StudioVoices get(Context context) {
        if (instance == null) {
            instance = new StudioVoices(context);
        }
        return instance;
    }

    boolean isRunning() {
        return running;
    }

    void cancel() {
        cancelled = true;
    }

    // ---------------------------------------------------------------- What is ready

    /** The Gemini voice of a character: characters take the voices in turn. */
    static String voiceFor(Scene scene, String role) {
        int index = Math.max(0, scene.roles.indexOf(role));
        return VOICES[index % VOICES.length];
    }

    /** The studio audio of a line, or null when it has not been prepared. */
    synchronized File audio(Scene fullScene, Scene.Line line, ScriptLanguage language) {
        JSONObject note = notes().optJSONObject(noteKey(line, language));
        if (note == null) {
            return null;
        }
        File file = audioFile(line, language, voiceFor(fullScene, line.role), note);
        return file.isFile() ? file : null;
    }

    /** How many of the lines the actor does not say have studio audio. */
    int readyCount(Scene fullScene, Scene scene, String role, ScriptLanguage language) {
        int ready = 0;
        for (Scene.Line line : partnerLines(scene, role)) {
            if (audio(fullScene, line, language) != null) {
                ready++;
            }
        }
        return ready;
    }

    static List<Scene.Line> partnerLines(Scene scene, String role) {
        List<Scene.Line> lines = new ArrayList<>();
        for (Scene.Line line : scene.lines) {
            if (!line.role.equals(role)) {
                lines.add(line);
            }
        }
        return lines;
    }

    // ---------------------------------------------------------------- Preparing

    /**
     * Prepares the partner lines of a scene in the background: notes first, a whole scene at a
     * time, then each line's audio. Stops at the first limit or lasting error; what is done stays.
     */
    void prepare(Scene fullScene, Scene scene, String role, ScriptLanguage language, String title, Progress progress) {
        if (running) {
            return;
        }
        running = true;
        cancelled = false;
        worker.execute(() -> {
            Stop reason;
            try {
                reason = run(fullScene, scene, role, language, title, progress);
            } catch (LimitException limit) {
                reason = limit.monthly ? Stop.MONTHLY_LIMIT : Stop.DAILY_LIMIT;
            } catch (IOException | JSONException | RuntimeException error) {
                if (cancelled) {
                    reason = Stop.CANCELLED;
                } else {
                    Log.w(TAG, "Studio voices stopped", error);
                    reason = Stop.FAILED;
                }
            }
            running = false;
            Stop result = reason;
            main.post(() -> progress.finished(result));
        });
    }

    private Stop run(Scene fullScene, Scene scene, String role, ScriptLanguage language, String title,
        Progress progress) throws IOException, JSONException {
        List<Scene.Line> partners = partnerLines(scene, role);
        int total = partners.size();
        post(progress, readyCount(fullScene, scene, role, language), total);

        // Notes: whole scenes, so the director sees what comes before and after each line.
        List<Scene.Line> lines = scene.lines;
        for (int start = 0; start < lines.size(); start += LINES_PER_REQUEST) {
            List<Scene.Line> chunk = lines.subList(start, Math.min(lines.size(), start + LINES_PER_REQUEST));
            boolean missing = false;
            for (Scene.Line line : chunk) {
                missing |= !line.role.equals(role) && note(line, language) == null;
            }
            if (!missing) {
                continue;
            }
            if (cancelled) {
                return Stop.CANCELLED;
            }
            direct(chunk, language, title);
        }

        // Voices: one line at a time.
        for (Scene.Line line : partners) {
            if (cancelled) {
                return Stop.CANCELLED;
            }
            JSONObject note = note(line, language);
            String voice = voiceFor(fullScene, line.role);
            if (note == null) {
                continue;
            }
            File target = audioFile(line, language, voice, note);
            if (target.isFile()) {
                continue;
            }
            byte[] wav = speak(note, language, voice);
            File partial = new File(target.getPath() + ".part");
            try (OutputStream output = new FileOutputStream(partial)) {
                output.write(wav);
            }
            if (!partial.renameTo(target)) {
                throw new IOException("Could not save studio audio");
            }
            post(progress, readyCount(fullScene, scene, role, language), total);
        }
        return Stop.DONE;
    }

    private void post(Progress progress, int done, int total) {
        main.post(() -> progress.progress(done, total));
    }

    private void direct(List<Scene.Line> chunk, ScriptLanguage language, String title) throws IOException, JSONException {
        JSONArray lines = new JSONArray();
        for (Scene.Line line : chunk) {
            JSONArray notes = new JSONArray();
            for (String note : line.notes) {
                notes.put(note);
            }
            lines.put(new JSONObject().put("role", line.role).put("text", line.text).put("notes", notes));
        }
        JSONObject body = new JSONObject().put("language", language.code).put("title", title).put("lines", lines);
        JSONObject answer = new JSONObject(new String(post("/v1/direct", body), StandardCharsets.UTF_8));
        JSONArray directions = answer.getJSONArray("lines");
        synchronized (this) {
            for (int i = 0; i < chunk.size() && i < directions.length(); i++) {
                notes().put(noteKey(chunk.get(i), language), directions.getJSONObject(i));
            }
            saveNotes();
        }
    }

    private byte[] speak(JSONObject note, ScriptLanguage language, String voice) throws IOException, JSONException {
        JSONObject body = new JSONObject()
            .put("language", language.code)
            .put("voice", voice)
            .put("emotion", note.optString("emotion", "neutral"))
            .put("pace", note.optString("pace", "normal"))
            .put("segments", note.getJSONArray("segments"))
            .put("pronunciations", note.optJSONArray("pronunciations") == null ? new JSONArray()
                : note.getJSONArray("pronunciations"));
        JSONObject answer = new JSONObject(new String(post("/v1/speak", body), StandardCharsets.UTF_8));
        JSONArray parts = answer.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts");
        for (int i = 0; i < parts.length(); i++) {
            JSONObject data = parts.getJSONObject(i).optJSONObject("inlineData");
            if (data != null) {
                byte[] audio = Base64.decode(data.getString("data"), Base64.DEFAULT);
                String type = data.optString("mimeType", "");
                return type.contains("wav") || isWav(audio) ? audio : wav(audio, sampleRate(type));
            }
        }
        throw new IOException("No audio in the answer");
    }

    private static boolean isWav(byte[] audio) {
        return audio.length > 12 && audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F';
    }

    private static int sampleRate(String mimeType) {
        java.util.regex.Matcher rate = java.util.regex.Pattern.compile("rate=(\\d+)").matcher(mimeType);
        return rate.find() ? Integer.parseInt(rate.group(1)) : 24_000;
    }

    /** Raw 16-bit mono PCM wrapped as a WAV file, so the media player can play it. */
    static byte[] wav(byte[] pcm, int rate) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length + 44);
        writeAscii(out, "RIFF");
        writeInt(out, 36 + pcm.length);
        writeAscii(out, "WAVEfmt ");
        writeInt(out, 16);
        writeShort(out, 1);
        writeShort(out, 1);
        writeInt(out, rate);
        writeInt(out, rate * 2);
        writeShort(out, 2);
        writeShort(out, 16);
        writeAscii(out, "data");
        writeInt(out, pcm.length);
        out.write(pcm, 0, pcm.length);
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    // ---------------------------------------------------------------- Requests

    static final class LimitException extends IOException {
        final boolean monthly;

        LimitException(boolean monthly) {
            this.monthly = monthly;
        }
    }

    /** Posts to the Worker, retrying a busy service a few times. */
    private byte[] post(String path, JSONObject body) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            if (cancelled) {
                throw new IOException("Cancelled");
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(WORKER + path).openConnection();
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(120_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + idToken(attempt > 0));
            try {
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = connection.getResponseCode();
                if (code == 200) {
                    return readAll(connection.getInputStream());
                }
                String error = connection.getErrorStream() == null ? ""
                    : new String(readAll(connection.getErrorStream()), StandardCharsets.UTF_8);
                if (code == 429) {
                    throw new LimitException(error.contains("monthly_limit"));
                }
                if (code == 401) {
                    idToken = null;
                    last = new IOException("Not signed in");
                } else if (code >= 500) {
                    last = new IOException("Service busy: " + code);
                } else {
                    throw new IOException("Refused: " + code + " " + error);
                }
            } catch (LimitException limit) {
                throw limit;
            } catch (IOException error) {
                last = error;
                if (error.getMessage() != null && error.getMessage().startsWith("Refused")) {
                    throw error;
                }
            } finally {
                connection.disconnect();
            }
            try {
                Thread.sleep(2000L << attempt);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        }
        throw last != null ? last : new IOException("No answer");
    }

    /**
     * A Firebase ID token for an identity of its own, kept with a refresh token. It is separate
     * from ensemble rehearsal, which asks the user's name before signing in.
     */
    private synchronized String idToken(boolean fresh) throws IOException {
        long now = System.currentTimeMillis();
        if (!fresh && idToken != null && now < idTokenExpires - 60_000) {
            return idToken;
        }
        String key = apiKey();
        try {
            String refresh = preferences.getString(REFRESH, null);
            JSONObject answer;
            if (refresh != null) {
                String form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8");
                answer = request("https://securetoken.googleapis.com/v1/token?key=" + key,
                    "application/x-www-form-urlencoded", form);
                if (answer != null) {
                    idToken = answer.getString("id_token");
                    preferences.edit().putString(REFRESH, answer.getString("refresh_token")).apply();
                    idTokenExpires = now + answer.optLong("expires_in", 3600) * 1000;
                    return idToken;
                }
            }
            answer = request("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=" + key,
                "application/json", "{\"returnSecureToken\":true}");
            if (answer == null) {
                throw new IOException("Could not sign in");
            }
            idToken = answer.getString("idToken");
            preferences.edit().putString(REFRESH, answer.getString("refreshToken")).apply();
            idTokenExpires = now + answer.optLong("expiresIn", 3600) * 1000;
            return idToken;
        } catch (JSONException error) {
            throw new IOException(error);
        }
    }

    /** The Firebase web API key from google-services.json; it is meant to be public. */
    @android.annotation.SuppressLint("DiscouragedApi")
    private String apiKey() throws IOException {
        int id = context.getResources().getIdentifier("google_api_key", "string", context.getPackageName());
        if (id == 0) {
            throw new IOException("Firebase is not configured");
        }
        return context.getString(id);
    }

    private static JSONObject request(String url, String type, String body) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", type);
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() != 200) {
                return null;
            }
            return new JSONObject(new String(readAll(connection.getInputStream()), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream in = input) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1 << 15];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------- Storage

    private JSONObject notes() {
        if (notes == null) {
            notes = new JSONObject();
            if (notesFile.isFile()) {
                try {
                    notes = new JSONObject(new String(readAll(new java.io.FileInputStream(notesFile)),
                        StandardCharsets.UTF_8));
                } catch (IOException | JSONException error) {
                    Log.w(TAG, "Studio notes could not be read", error);
                }
            }
        }
        return notes;
    }

    private synchronized JSONObject note(Scene.Line line, ScriptLanguage language) {
        return notes().optJSONObject(noteKey(line, language));
    }

    private void saveNotes() throws IOException {
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("Could not create the studio folder");
        }
        File partial = new File(notesFile.getPath() + ".part");
        try (OutputStream output = new FileOutputStream(partial)) {
            output.write(notes.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!partial.renameTo(notesFile)) {
            throw new IOException("Could not save studio notes");
        }
    }

    private static String noteKey(Scene.Line line, ScriptLanguage language) {
        return language.code + ":" + EnsembleRecordings.lineKey(line.role, line.text);
    }

    /** A line's audio changes with its words, its note and its voice; each combination is one file. */
    private File audioFile(Scene.Line line, ScriptLanguage language, String voice, JSONObject note) {
        return new File(folder, sha(language.code + "\n" + voice + "\n" + line.text + "\n" + note) + ".wav");
    }

    private static String sha(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
