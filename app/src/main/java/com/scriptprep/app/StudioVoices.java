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
    /**
     * The Gemini voice of each character voice of the cast: speakers 0–4 are female and 5–9 male,
     * like the on-device AI voices. The Worker's sample voice pack uses the same table.
     */
    static final String[] CAST_VOICES = {"Kore", "Aoede", "Leda", "Sulafat", "Gacrux",
        "Charon", "Puck", "Orus", "Algenib", "Iapetus"};
    private static final String SAMPLE_PACK = "studio-sample/";
    private static final String TAG = "ScriptPrep";
    private static final int LINES_PER_REQUEST = 60;
    private static final String REFRESH = "studio_refresh_token";

    enum Stop { DONE, PARTIAL, CANCELLED, DAILY_LIMIT, MONTHLY_LIMIT, SCRIPT_LIMIT, EXTRA_SCRIPT, PREMIUM_REQUIRED, FAILED }

    /** Failures in a row after which preparing stops: the connection or the service is down. */
    private static final int FAILURES_IN_A_ROW = 3;

    /** The Gemini voice of a character, from the script's cast. */
    interface Casting {
        String voice(String role);
    }

    interface Progress {
        /** Lines with studio audio so far, of all lines of the scene. */
        void progress(int done, int total);

        void finished(Stop reason);
    }

    private static StudioVoices instance;
    /** Also told of progress: the foreground service that keeps preparing while the app is away. */
    private volatile Progress observer;
    /** Lines the last preparation could not voice; they are tried again next time. */
    private volatile int skippedLines;

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
    /** Requests on the way, so stopping does not wait for them. */
    private final java.util.Set<HttpURLConnection> openConnections = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Headers of the last answer, read on the preparing thread only. */
    private final ThreadLocal<java.util.Map<String, List<String>>> lastHeaders =
        ThreadLocal.withInitial(java.util.Collections::emptyMap);
    /** How the last line was voiced: model, route, style and text the voice was given. */
    private final ThreadLocal<JSONObject> lastVoiced = ThreadLocal.withInitial(JSONObject::new);

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

    /** Whether the scene being prepared was asked to stop and is finishing what is on the way. */
    boolean isStopping() {
        return running && cancelled;
    }

    /** Stops the scene being prepared and forgets the ones waiting; requests on the way are cut off. */
    void cancel() {
        cancelled = true;
        for (HttpURLConnection connection : openConnections) {
            new Thread(connection::disconnect).start();
        }
        List<Job> dropped;
        synchronized (queue) {
            dropped = new ArrayList<>(queue);
            queue.clear();
        }
        for (Job job : dropped) {
            main.post(() -> job.progress.finished(Stop.CANCELLED));
        }
    }

    /** Whether this scene (a job key) is being prepared now. */
    boolean isPreparing(String key) {
        Job job = current;
        return job != null && job.key.equals(key);
    }

    /** Whether this scene waits for another to finish. */
    boolean isQueued(String key) {
        synchronized (queue) {
            for (Job job : queue) {
                if (job.key.equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Lines ready of the scene being prepared, and its title, for the progress notification. */
    int currentDone() {
        return currentDone;
    }

    int currentTotal() {
        return currentTotal;
    }

    String currentTitle() {
        Job job = current;
        return job == null ? "" : job.title;
    }

    // ---------------------------------------------------------------- What is ready

    /** The sample of a studio voice that comes with the app, in the app's assets. */
    static String previewAsset(ScriptLanguage language, String voice) {
        return "studio-voices/" + language.code + "-" + voice + ".m4a";
    }

    /** The Gemini voice of a character voice of the cast. */
    static String voiceOf(VoiceCast.Part part) {
        return CAST_VOICES[Math.max(0, Math.min(CAST_VOICES.length - 1, part == null ? 0 : part.speaker))];
    }

    /** The studio audio of a line in the given voice, or null when it has not been prepared. */
    synchronized File audio(Scene.Line line, ScriptLanguage language, String voice) {
        JSONObject note = notes().optJSONObject(noteKey(line, language));
        if (note == null) {
            return null;
        }
        File file = audioFile(line, language, voice, note, ".wav");
        if (file.isFile()) {
            return file;
        }
        File packed = audioFile(line, language, voice, note, ".m4a");
        return packed.isFile() ? packed : null;
    }

    /**
     * How many lines of the scene have studio audio. Every line is prepared, the actor's own too,
     * so listening, another part and a change of role all play studio voices.
     */
    int readyCount(Scene scene, ScriptLanguage language, Casting casting) {
        int ready = 0;
        for (Scene.Line line : scene.lines) {
            if (audio(line, language, casting.voice(line.role)) != null) {
                ready++;
            }
        }
        return ready;
    }

    // ---------------------------------------------------------------- Preparing

    /**
     * Prepares the partner lines of a scene in the background: notes first, a whole scene at a
     * time, then each line's audio. Stops at the first limit or lasting error; what is done stays.
     */
    /** One scene to prepare: what it is, and who to tell how it goes. */
    private static final class Job {
        final String key;
        final Scene fullScene;
        final Scene scene;
        final ScriptLanguage language;
        final String title;
        final String script;
        final Casting casting;
        final Progress progress;

        Job(String key, Scene fullScene, Scene scene, ScriptLanguage language, String title, String script,
            Casting casting, Progress progress) {
            this.key = key;
            this.fullScene = fullScene;
            this.scene = scene;
            this.language = language;
            this.title = title;
            this.script = script;
            this.casting = casting;
            this.progress = progress;
        }
    }

    private final java.util.ArrayDeque<Job> queue = new java.util.ArrayDeque<>();
    private volatile Job current;
    private volatile int currentDone;
    private volatile int currentTotal;

    /**
     * Prepares a scene in the background: the play's reading first, then notes in parts, then
     * each line's audio. A scene asked while another is being prepared waits its turn.
     *
     * @param key    the scene, so each screen shows its own progress (script, section, language)
     * @param script an id of the script, so the Worker can count the scripts of the month; made
     *               from the app's own id and not readable back to it
     */
    void prepare(String key, Scene fullScene, Scene scene, ScriptLanguage language, String title, String script,
        Casting casting, Progress progress) {
        Job job = new Job(key, fullScene, scene, language, title, sha(script), casting, progress);
        synchronized (queue) {
            if (current != null) {
                if (!isPreparing(key) && !isQueued(key)) {
                    queue.add(job);
                }
                return;
            }
            current = job;
            running = true;
        }
        start(job);
    }

    private void start(Job job) {
        cancelled = false;
        currentDone = 0;
        currentTotal = job.scene.lines.size();
        worker.execute(() -> {
            Stop reason;
            try {
                reason = run(job.fullScene, job.scene, job.language, job.title, job.script, job.casting, job.progress);
            } catch (LimitException limit) {
                reason = limit.reason;
            } catch (IOException | JSONException | RuntimeException error) {
                if (cancelled) {
                    reason = Stop.CANCELLED;
                } else {
                    Log.w(TAG, "Studio voices stopped", error);
                    reason = Stop.FAILED;
                }
            }
            // The next scene starts before this one is reported, so the service sees work going on.
            Job next;
            synchronized (queue) {
                next = reason == Stop.CANCELLED ? null : queue.poll();
                current = next;
                running = next != null;
            }
            Stop result = reason;
            main.post(() -> {
                job.progress.finished(result);
                Progress watching = observer;
                if (watching != null) {
                    watching.finished(result);
                }
            });
            if (next != null) {
                start(next);
            }
        });
    }

    int skippedLines() {
        return skippedLines;
    }

    void setObserver(Progress observer) {
        this.observer = observer;
    }

    private Stop run(Scene fullScene, Scene scene, ScriptLanguage language, String title, String script,
        Casting casting, Progress progress) throws IOException, JSONException {
        List<Scene.Line> lines = scene.lines;
        int total = lines.size();
        post(progress, readyCount(scene, language, casting), total);

        sendSubscription();

        // The director first reads the whole play once; each part is then directed knowing it.
        JSONObject analysis = analysis(fullScene, language, script);
        if (analysis == null) {
            try {
                analysis = analyze(fullScene, language, title, script);
            } catch (LimitException limit) {
                throw limit;
            } catch (IOException | JSONException error) {
                Log.w(TAG, "The play could not be read as a whole; directing scene by scene", error);
            }
        }

        // Parts and lines go several at a time. One that fails is skipped and tried again next time;
        // only failures in a row stop preparing, as the connection or the service is then down.
        skippedLines = 0;
        Failures failures = new Failures();

        // Notes: only lines that have none, in runs of up to a part, each with the lines before it for
        // context. Lines already directed keep their notes, so their audio stays theirs.
        List<java.util.concurrent.Callable<Void>> parts = new ArrayList<>();
        JSONObject reading = analysis;
        for (List<Scene.Line> chunk : undirected(lines, language)) {
            parts.add(() -> {
                failures.attempt(() -> direct(chunk, linesBefore(fullScene, chunk.get(0)),
                    contextFor(reading, chunk.get(0).section), language, title, script), "A part could not be directed");
                return null;
            });
        }
        runAll(parts, PARTS_AT_ONCE);
        failures.rethrow();
        if (cancelled) {
            return Stop.CANCELLED;
        }

        // Voices: each line on its own.
        java.util.concurrent.atomic.AtomicInteger ready = new java.util.concurrent.atomic.AtomicInteger(
            readyCount(scene, language, casting));
        List<java.util.concurrent.Callable<Void>> voices = new ArrayList<>();
        for (Scene.Line line : lines) {
            JSONObject note = note(line, language);
            // The director's genders can change the automatic cast, so the voice is asked now.
            String voice = casting.voice(line.role);
            if (note == null || audio(line, language, voice) != null || isBlocked(line, language)) {
                continue;
            }
            voices.add(() -> {
                boolean voiced = failures.attempt(() -> {
                    File target = audioFile(line, language, voice, note, ".wav");
                    byte[] wav = speak(note, language, voice, script);
                    File partial = new File(target.getPath() + "." + Thread.currentThread().getId() + ".part");
                    try (OutputStream output = new FileOutputStream(partial)) {
                        output.write(wav);
                    }
                    if (!partial.renameTo(target)) {
                        throw new IOException("Could not save studio audio");
                    }
                    synchronized (this) {
                        notes().put(VOICED + target.getName(), lastVoiced.get());
                        saveNotes();
                    }
                }, "A line could not be voiced", line, language);
                if (voiced) {
                    post(progress, ready.incrementAndGet(), total);
                }
                return null;
            });
        }
        runAll(voices, LINES_AT_ONCE);
        failures.rethrow();
        if (cancelled) {
            return Stop.CANCELLED;
        }
        skippedLines = total - readyCount(scene, language, casting);
        return skippedLines > 0 ? Stop.PARTIAL : Stop.DONE;
    }

    // ---------------------------------------------------------------- Lines that could not be voiced

    private static final String FAILED = "failed:";
    /** Tries for a line whose answer could not be used, before preparing leaves it to the actor. */
    private static final int LASTING_TRIES = 2;

    /** Whether a failure is the answer's (no audio, refused, unreadable) rather than the network's. */
    static boolean lasting(Exception error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        return error instanceof JSONException || message.startsWith("Refused") || message.startsWith("No audio");
    }

    private synchronized void recordFailure(Scene.Line line, ScriptLanguage language, boolean lasting) {
        try {
            String key = FAILED + noteKey(line, language);
            JSONObject old = notes().optJSONObject(key);
            int tries = old != null && old.optBoolean("lasting") == lasting ? old.optInt("tries") + 1 : 1;
            notes().put(key, new JSONObject().put("lasting", lasting).put("tries", tries)
                .put("at", System.currentTimeMillis()));
            saveNotes();
        } catch (IOException | JSONException error) {
            Log.w(TAG, "A failure could not be kept", error);
        }
    }

    private synchronized void clearFailure(Scene.Line line, ScriptLanguage language) {
        if (notes().remove(FAILED + noteKey(line, language)) != null) {
            try {
                saveNotes();
            } catch (IOException error) {
                Log.w(TAG, "Studio notes could not be saved", error);
            }
        }
    }

    /** Why a line has no studio audio after preparing: null, or whether the failure was lasting. */
    synchronized Boolean failure(Scene.Line line, ScriptLanguage language) {
        JSONObject failed = notes().optJSONObject(FAILED + noteKey(line, language));
        return failed == null ? null : failed.optBoolean("lasting");
    }

    /** A line preparing no longer tries: its answer could not be used, twice. */
    synchronized boolean isBlocked(Scene.Line line, ScriptLanguage language) {
        JSONObject failed = notes().optJSONObject(FAILED + noteKey(line, language));
        return failed != null && failed.optBoolean("lasting") && failed.optInt("tries") >= LASTING_TRIES;
    }

    /** Lines of a scene preparing no longer tries, without audio. */
    int blockedCount(Scene scene, ScriptLanguage language, Casting casting) {
        int blocked = 0;
        for (Scene.Line line : scene.lines) {
            if (isBlocked(line, language) && audio(line, language, casting.voice(line.role)) == null) {
                blocked++;
            }
        }
        return blocked;
    }

    /** Lets preparing try a line again (the actor asked). */
    void retry(Scene.Line line, ScriptLanguage language) {
        clearFailure(line, language);
    }

    /** Runs of lines with no note yet, at most a part long. */
    private List<List<Scene.Line>> undirected(List<Scene.Line> lines, ScriptLanguage language) {
        List<List<Scene.Line>> runs = new ArrayList<>();
        List<Scene.Line> run = new ArrayList<>();
        for (Scene.Line line : lines) {
            if (note(line, language) == null) {
                run.add(line);
                if (run.size() >= LINES_PER_REQUEST) {
                    runs.add(run);
                    run = new ArrayList<>();
                }
            } else if (!run.isEmpty()) {
                runs.add(run);
                run = new ArrayList<>();
            }
        }
        if (!run.isEmpty()) {
            runs.add(run);
        }
        return runs;
    }

    /** Requests at once: Google Cloud took six voices at a time without complaint on CI. */
    private static final int LINES_AT_ONCE = 6;
    private static final int PARTS_AT_ONCE = 3;

    private interface Work {
        void run() throws IOException, JSONException;
    }

    /** Failures of work done side by side: a limit or failures in a row end the run for all. */
    private final class Failures {
        private final java.util.concurrent.atomic.AtomicInteger inARow = new java.util.concurrent.atomic.AtomicInteger();
        private volatile Exception fatal;

        /** Runs one piece unless the run is over; true when it worked. */
        boolean attempt(Work work, String what) {
            return attempt(work, what, null, null);
        }

        /**
         * As above for one line, whose failure is kept: a network failure is tried again next time;
         * an answer that cannot be used (no audio, refused, unreadable) only once more. Only
         * network failures in a row stop the run, as the connection or the service is then down.
         */
        boolean attempt(Work work, String what, Scene.Line line, ScriptLanguage language) {
            if (fatal != null || cancelled) {
                return false;
            }
            try {
                work.run();
                inARow.set(0);
                if (line != null) {
                    clearFailure(line, language);
                }
                return true;
            } catch (LimitException limit) {
                fatal = limit;
            } catch (IOException | JSONException error) {
                if (cancelled) {
                    return false;
                }
                boolean lasting = lasting(error);
                if (line != null) {
                    recordFailure(line, language, lasting);
                }
                if (!lasting && inARow.incrementAndGet() >= FAILURES_IN_A_ROW) {
                    fatal = error;
                } else {
                    Log.w(TAG, what + "; going on", error);
                }
            }
            return false;
        }

        void rethrow() throws IOException, JSONException {
            Exception error = fatal;
            if (error instanceof IOException io) {
                throw io;
            }
            if (error instanceof JSONException json) {
                throw json;
            }
        }
    }

    private static void runAll(List<java.util.concurrent.Callable<Void>> work, int atOnce) throws IOException {
        if (work.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(atOnce, work.size()));
        try {
            pool.invokeAll(work);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        } finally {
            pool.shutdownNow();
        }
    }

    private void post(Progress progress, int done, int total) {
        currentDone = done;
        currentTotal = total;
        main.post(() -> {
            progress.progress(done, total);
            Progress watching = observer;
            if (watching != null) {
                watching.progress(done, total);
            }
        });
    }

    private static JSONArray linesJson(List<Scene.Line> chunk) throws JSONException {
        JSONArray lines = new JSONArray();
        for (Scene.Line line : chunk) {
            JSONArray notes = new JSONArray();
            for (String note : line.notes) {
                notes.put(note.length() > 300 ? note.substring(0, 300) : note);
            }
            lines.put(new JSONObject().put("role", line.role).put("text", line.text).put("notes", notes));
        }
        return lines;
    }

    private void direct(List<Scene.Line> chunk, List<Scene.Line> before, String context, ScriptLanguage language,
        String title, String script) throws IOException, JSONException {
        JSONObject body = new JSONObject().put("language", language.code).put("title", title).put("script", script)
            .put("lines", linesJson(chunk)).put("before", linesJson(before)).put("context", context);
        JSONObject answer = new JSONObject(new String(post("/v1/direct", body), StandardCharsets.UTF_8));
        JSONArray directions = answer.getJSONArray("lines");
        JSONArray cast = answer.optJSONArray("cast");
        synchronized (this) {
            for (int i = 0; i < chunk.size() && i < directions.length(); i++) {
                // A note is never replaced here: the line's audio belongs to it.
                if (!notes().has(noteKey(chunk.get(i), language))) {
                    notes().put(noteKey(chunk.get(i), language), directions.getJSONObject(i));
                }
            }
            for (int i = 0; cast != null && i < cast.length(); i++) {
                JSONObject member = cast.getJSONObject(i);
                putGender(member.optString("role"), member.optString("gender"));
            }
            saveNotes();
        }
    }

    private void putGender(String role, String gender) throws JSONException {
        if (!role.isEmpty() && ("female".equals(gender) || "male".equals(gender))) {
            notes().put(genderKey(role), gender);
        }
    }

    /** The genders the director gave the characters ("female" or "male"), for the automatic cast. */
    synchronized java.util.Map<String, String> genders(List<String> roles) {
        java.util.Map<String, String> genders = new java.util.HashMap<>();
        for (String role : roles) {
            String gender = notes().optString(genderKey(role), "");
            if (!gender.isEmpty()) {
                genders.put(role, gender);
            }
        }
        return genders;
    }

    // ---------------------------------------------------------------- Reading the whole play

    private static final String ANALYSIS = "analysis:";
    private static final int LINES_BEFORE = 30;
    private static final int MAX_PLAY_CHARS = 590_000;

    /** The key of a play's reading: the script and its words, so an edited script is read again. */
    private static String analysisKey(Scene fullScene, ScriptLanguage language, String script) {
        StringBuilder words = new StringBuilder(language.code).append('\n').append(script);
        for (Scene.Line line : fullScene.lines) {
            words.append('\n').append(line.role).append(':').append(line.text);
        }
        return ANALYSIS + sha(words.toString());
    }

    /** The director's reading of the play, or null until it has been read. */
    synchronized JSONObject analysis(Scene fullScene, ScriptLanguage language, String script) {
        return notes().optJSONObject(analysisKey(fullScene, language, script));
    }

    /** The app's id of a script, as the Worker sees it. */
    static String scriptId(String castKey) {
        return sha(castKey);
    }

    /**
     * The play as the director reads it: "## SCENE:" where a named scene starts, then
     * "ROLE: text (direction)". A very long play is cut at the Worker's limit.
     */
    static String playText(Scene fullScene) {
        StringBuilder play = new StringBuilder();
        String section = null;
        for (Scene.Line line : fullScene.lines) {
            if (!line.section.equals(section)) {
                section = line.section;
                if (!section.isEmpty()) {
                    play.append("\n## SCENE: ").append(section).append('\n');
                }
            }
            play.append(line.role).append(": ").append(line.text);
            for (String note : line.notes) {
                play.append(' ').append(note.startsWith("(") || note.startsWith("[") ? note : "(" + note + ")");
            }
            play.append('\n');
            if (play.length() > MAX_PLAY_CHARS) {
                break;
            }
        }
        return play.length() > MAX_PLAY_CHARS ? play.substring(0, MAX_PLAY_CHARS) : play.toString();
    }

    /** Asks the director to read the whole play; keeps the reading and the characters' genders. */
    private JSONObject analyze(Scene fullScene, ScriptLanguage language, String title, String script)
        throws IOException, JSONException {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("X-Memo-Language", language.code);
        headers.put("X-Memo-Title", URLEncoder.encode(title, "UTF-8").replace("+", "%20"));
        headers.put("X-Memo-Script", script);
        byte[] answer = post("/v1/analyze", playText(fullScene).getBytes(StandardCharsets.UTF_8),
            "text/plain; charset=utf-8", headers);
        JSONObject analysis = new JSONObject(new String(answer, StandardCharsets.UTF_8));
        synchronized (this) {
            notes().put(analysisKey(fullScene, language, script), analysis);
            JSONArray characters = analysis.optJSONArray("characters");
            for (int i = 0; characters != null && i < characters.length(); i++) {
                JSONObject character = characters.getJSONObject(i);
                putGender(character.optString("role"), character.optString("gender"));
            }
            saveNotes();
        }
        return analysis;
    }

    /**
     * Reads the play before the cast is checked, so its genders fill in the cast, and keeps the
     * reading for directing. Falls back to asking for the genders alone. {@code done} runs on the
     * main thread.
     */
    void analyzeFirst(Scene fullScene, ScriptLanguage language, String title, String castKey, Runnable done) {
        if (!running) {
            cancelled = false;
        }
        String script = scriptId(castKey);
        worker.execute(() -> {
            try {
                if (analysis(fullScene, language, script) == null) {
                    analyze(fullScene, language, title, script);
                }
            } catch (IOException | JSONException | RuntimeException error) {
                Log.w(TAG, "The play could not be read; asking for the genders alone", error);
                main.post(() -> guessGenders(fullScene, language, title, done));
                return;
            }
            main.post(done);
        });
    }

    /** The lines just before a line in the whole play, for the director's context. */
    private static List<Scene.Line> linesBefore(Scene fullScene, Scene.Line first) {
        int index = fullScene.lines.indexOf(first);
        if (index <= 0) {
            return java.util.Collections.emptyList();
        }
        return fullScene.lines.subList(Math.max(0, index - LINES_BEFORE), index);
    }

    /** The part of the director's reading that a scene needs: the play, its people, this scene. */
    static String contextFor(JSONObject analysis, String section) {
        if (analysis == null) {
            return "";
        }
        StringBuilder context = new StringBuilder();
        String summary = analysis.optString("summary");
        if (!summary.isEmpty()) {
            context.append("Story: ").append(summary).append('\n');
        }
        JSONArray characters = analysis.optJSONArray("characters");
        if (characters != null && characters.length() > 0) {
            context.append("Characters:\n");
            for (int i = 0; i < characters.length(); i++) {
                JSONObject c = characters.optJSONObject(i);
                if (c != null) {
                    context.append("- ").append(c.optString("role")).append(" (").append(c.optString("gender"))
                        .append(", ").append(c.optString("age")).append("): ").append(c.optString("description"))
                        .append('\n');
                }
            }
        }
        JSONArray relationships = analysis.optJSONArray("relationships");
        if (relationships != null && relationships.length() > 0) {
            context.append("Relationships:\n");
            for (int i = 0; i < relationships.length(); i++) {
                context.append("- ").append(relationships.optString(i)).append('\n');
            }
        }
        JSONObject scene = sceneOf(analysis, section);
        if (scene != null) {
            context.append("This scene").append(section.isEmpty() ? "" : " (" + section + ")").append(": ")
                .append(scene.optString("summary")).append('\n');
            if (!scene.optString("atmosphere").isEmpty()) {
                context.append("Atmosphere everyone carries: ").append(scene.optString("atmosphere")).append('\n');
            }
            JSONArray moods = scene.optJSONArray("moods");
            if (moods != null && moods.length() > 0) {
                context.append("State of mind in this scene:\n");
                for (int i = 0; i < moods.length(); i++) {
                    JSONObject mood = moods.optJSONObject(i);
                    if (mood != null) {
                        context.append("- ").append(mood.optString("role")).append(": ").append(mood.optString("mood"))
                            .append('\n');
                    }
                }
            }
        }
        return context.length() > 15_000 ? context.substring(0, 15_000) : context.toString();
    }

    /** The reading of one scene: the one with this name, or the only one. */
    static JSONObject sceneOf(JSONObject analysis, String section) {
        JSONArray scenes = analysis == null ? null : analysis.optJSONArray("scenes");
        if (scenes == null || scenes.length() == 0) {
            return null;
        }
        for (int i = 0; i < scenes.length(); i++) {
            JSONObject scene = scenes.optJSONObject(i);
            if (scene != null && scene.optString("scene").trim().equalsIgnoreCase(section.trim())) {
                return scene;
            }
        }
        return scenes.length() == 1 ? scenes.optJSONObject(0) : null;
    }

    /**
     * Forgets the director's notes and audio of a scene's lines, and the reading of the play, so
     * they are made again (with the reading) the next time the scene is prepared.
     */
    synchronized void forget(Scene fullScene, Scene scene, ScriptLanguage language, String castKey) {
        for (Scene.Line line : scene.lines) {
            JSONObject note = notes().optJSONObject(noteKey(line, language));
            if (note != null && note.optBoolean("byActor")) {
                // The actor's own fix stays, with its audio.
                continue;
            }
            if (note != null) {
                for (String voice : CAST_VOICES) {
                    for (String extension : new String[] {".wav", ".m4a"}) {
                        File file = audioFile(line, language, voice, note, extension);
                        notes().remove(VOICED + file.getName());
                        if (file.isFile() && !file.delete()) {
                            Log.w(TAG, "Could not delete " + file.getName());
                        }
                    }
                }
            }
            notes().remove(noteKey(line, language));
        }
        notes().remove(analysisKey(fullScene, language, scriptId(castKey)));
        try {
            saveNotes();
        } catch (IOException error) {
            Log.w(TAG, "Studio notes could not be saved", error);
        }
    }

    // ---------------------------------------------------------------- What the user bought

    private volatile List<String> subscriptionTokens = java.util.Collections.emptyList();
    private volatile long entitlementSentAt;

    /** The Play purchase tokens of the user's Premium, sent to the Worker before preparing. */
    void setSubscriptionTokens(List<String> tokens) {
        if (!tokens.equals(subscriptionTokens)) {
            subscriptionTokens = new ArrayList<>(tokens);
            entitlementSentAt = 0;
        }
    }

    /** Tells the Worker about the user's Premium at most every hour; it checks with Google Play. */
    private void sendSubscription() {
        List<String> tokens = subscriptionTokens;
        if (tokens.isEmpty() || System.currentTimeMillis() - entitlementSentAt < 3_600_000L) {
            return;
        }
        try {
            post("/v1/entitlement", new JSONObject().put("platform", "android")
                .put("subscriptions", new JSONArray(tokens)).put("extras", new JSONArray()));
            entitlementSentAt = System.currentTimeMillis();
        } catch (IOException | JSONException error) {
            Log.w(TAG, "Premium could not be sent to the Worker", error);
        }
    }

    interface Redeemed {
        /** The purchase tokens the Worker counted; those may be consumed. */
        void accepted(List<String> tokens);
    }

    /** Has the Worker check and count paid extra scripts. {@code done} runs on the main thread. */
    void redeemExtras(List<String> tokens, Redeemed done) {
        fixer.execute(() -> {
            List<String> accepted = new ArrayList<>();
            try {
                JSONObject answer = new JSONObject(new String(post("/v1/entitlement", new JSONObject()
                    .put("platform", "android").put("subscriptions", new JSONArray(subscriptionTokens))
                    .put("extras", new JSONArray(tokens))), StandardCharsets.UTF_8));
                JSONArray counted = answer.optJSONArray("accepted");
                for (int i = 0; counted != null && i < counted.length(); i++) {
                    accepted.add(counted.getString(i));
                }
            } catch (IOException | JSONException error) {
                Log.w(TAG, "Extra scripts could not be counted", error);
            }
            main.post(() -> done.accepted(accepted));
        });
    }

    // ---------------------------------------------------------------- Fixing one line

    /** Tries per line, so a line cannot be asked for again and again without end. */
    static final int FIXES_PER_LINE = 5;
    private static final String FIXES = "fixes:";
    private final ExecutorService fixer = Executors.newSingleThreadExecutor();

    /** A new take of one line: its direction and audio, kept aside until the actor chooses it. */
    static final class Take {
        final JSONObject note;
        final File audio;
        final String voice;
        final JSONObject voiced;

        Take(JSONObject note, File audio, String voice, JSONObject voiced) {
            this.note = note;
            this.audio = audio;
            this.voice = voice;
            this.voiced = voiced;
        }
    }

    interface TakeResult {
        void ready(Take take);

        void failed(Stop reason);
    }

    synchronized int fixesLeft(Scene.Line line, ScriptLanguage language) {
        return Math.max(0, FIXES_PER_LINE - notes().optInt(FIXES + noteKey(line, language)));
    }

    /**
     * Has the director direct one line again the way the actor asked, and voices it, as a take
     * the actor can listen to before keeping it. {@code result} runs on the main thread.
     */
    void fix(Scene fullScene, Scene.Line line, ScriptLanguage language, String title, String castKey, String voice,
        List<String> wishes, String request, String word, String say, TakeResult result) {
        String script = scriptId(castKey);
        fixer.execute(() -> {
            try {
                if (!running) {
                    cancelled = false;
                }
                synchronized (this) {
                    String key = FIXES + noteKey(line, language);
                    notes().put(key, notes().optInt(key) + 1);
                    saveNotes();
                }
                JSONArray wished = new JSONArray();
                for (String wish : wishes) {
                    wished.put(wish);
                }
                JSONArray sayings = new JSONArray();
                if (!word.isEmpty() && !say.isEmpty()) {
                    sayings.put(new JSONObject().put("word", word).put("say", say));
                }
                JSONObject current = note(line, language);
                JSONObject body = new JSONObject().put("language", language.code).put("title", title)
                    .put("script", script).put("lines", linesJson(java.util.Collections.singletonList(line)))
                    .put("before", linesJson(linesBefore(fullScene, line)))
                    .put("context", contextFor(analysis(fullScene, language, script), line.section))
                    .put("current", current == null ? new JSONObject() : current)
                    .put("wishes", wished).put("request", request).put("pronunciations", sayings);
                JSONObject answer = new JSONObject(new String(post("/v1/revise", body), StandardCharsets.UTF_8));
                JSONObject note = answer.getJSONObject("line").put("byActor", true).put("request", request);
                byte[] audio = speak(note, language, voice, script);
                File take = new File(context.getCacheDir(), "studio-take-" + System.nanoTime() + ".wav");
                try (OutputStream output = new FileOutputStream(take)) {
                    output.write(audio);
                }
                Take made = new Take(note, take, voice, lastVoiced.get());
                main.post(() -> result.ready(made));
            } catch (LimitException limit) {
                main.post(() -> result.failed(limit.reason));
            } catch (IOException | JSONException | RuntimeException error) {
                Log.w(TAG, "The line could not be fixed", error);
                main.post(() -> result.failed(Stop.FAILED));
            }
        });
    }

    /** Keeps a take: it becomes the line's direction and audio, and the old audio goes. */
    synchronized void keep(Take take, Scene.Line line, ScriptLanguage language) {
        try {
            JSONObject old = notes().optJSONObject(noteKey(line, language));
            if (old != null) {
                for (String extension : new String[] {".wav", ".m4a"}) {
                    File previous = audioFile(line, language, take.voice, old, extension);
                    notes().remove(VOICED + previous.getName());
                    if (previous.isFile() && !previous.delete()) {
                        Log.w(TAG, "Could not delete " + previous.getName());
                    }
                }
            }
            notes().put(noteKey(line, language), take.note);
            notes().remove(FAILED + noteKey(line, language));
            if (!folder.isDirectory() && !folder.mkdirs()) {
                throw new IOException("Could not create the studio folder");
            }
            File target = audioFile(line, language, take.voice, take.note, ".wav");
            try (InputStream input = new java.io.FileInputStream(take.audio);
                 OutputStream output = new FileOutputStream(target)) {
                output.write(readAll(input));
            }
            notes().put(VOICED + target.getName(), take.voiced);
            saveNotes();
        } catch (IOException | JSONException error) {
            Log.w(TAG, "The take could not be kept", error);
        } finally {
            discard(take);
        }
    }

    void discard(Take take) {
        if (take.audio.isFile() && !take.audio.delete()) {
            Log.w(TAG, "Could not delete a take");
        }
    }

    /** Whether the director has given a gender for every character. */
    synchronized boolean knowsGenders(List<String> roles) {
        return genders(roles).size() >= roles.size();
    }

    /**
     * Asks the director for the characters' likely genders before the cast is chosen, so women and
     * men start with voices of their gender. Quiet on failure: the cast screen works without it.
     */
    void guessGenders(Scene scene, ScriptLanguage language, String title, Runnable done) {
        if (!running) {
            cancelled = false;
        }
        worker.execute(() -> {
            try {
                // The opening of the scene, within the Worker's size limits, is enough to tell.
                JSONArray lines = new JSONArray();
                int count = 0;
                int characters = 0;
                for (Scene.Line line : scene.lines) {
                    if (count++ >= 80 || characters > 12_000) {
                        break;
                    }
                    characters += Math.min(line.text.length(), 400);
                    JSONArray notes = new JSONArray();
                    for (String note : line.notes) {
                        notes.put(note.length() > 300 ? note.substring(0, 300) : note);
                    }
                    String text = line.text.length() > 400 ? line.text.substring(0, 400) : line.text;
                    lines.put(new JSONObject().put("role", line.role).put("text", text).put("notes", notes));
                }
                JSONObject body = new JSONObject().put("language", language.code).put("title", title).put("lines", lines);
                JSONObject answer = new JSONObject(new String(post("/v1/cast", body), StandardCharsets.UTF_8));
                JSONArray cast = answer.optJSONArray("cast");
                synchronized (this) {
                    for (int i = 0; cast != null && i < cast.length(); i++) {
                        JSONObject member = cast.getJSONObject(i);
                        putGender(member.optString("role"), member.optString("gender"));
                    }
                    saveNotes();
                }
            } catch (IOException | JSONException | RuntimeException error) {
                Log.w(TAG, "Genders could not be guessed", error);
            }
            main.post(done);
        });
    }

    private String header(String name) {
        for (java.util.Map.Entry<String, List<String>> entry : lastHeaders.get().entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey()) && !entry.getValue().isEmpty()) {
                try {
                    return java.net.URLDecoder.decode(entry.getValue().get(0), "UTF-8");
                } catch (java.io.UnsupportedEncodingException | IllegalArgumentException error) {
                    return entry.getValue().get(0);
                }
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- What the director decided

    private static final String VOICED = "voiced:";

    /** The director's note of a line: emotion, pace, subtext, segments; null until it is directed. */
    synchronized JSONObject noteOf(Scene.Line line, ScriptLanguage language) {
        return notes().optJSONObject(noteKey(line, language));
    }

    /**
     * How a line's audio in the given voice was made (model, route, the style and text the voice
     * was given), or null for audio made before this was kept, or not made yet.
     */
    synchronized JSONObject voicedOf(Scene.Line line, ScriptLanguage language, String voice) {
        File audio = audio(line, language, voice);
        return audio == null ? null : notes().optJSONObject(VOICED + audio.getName());
    }

    private static String genderKey(String role) {
        return "gender:" + role.trim().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- The sample scene

    /**
     * Copies the voice pack of the sample scene from the app's assets (made by the Worker's
     * scripts/sample-pack.ts), so the first scene plays with studio voices without preparing it.
     * Only lines that match the scene are taken; it is done once per language and pack.
     */
    synchronized void installSample(Scene scene, ScriptLanguage language) {
        String done = "sample_pack_" + language.code;
        String name = SAMPLE_PACK + language.code + ".json";
        try {
            JSONObject pack = new JSONObject(new String(readAll(context.getAssets().open(name)), StandardCharsets.UTF_8));
            JSONArray lines = pack.getJSONArray("lines");
            String stamp = pack.optInt("version") + "/" + lines.length() + "/" + pack.toString().hashCode();
            if (stamp.equals(preferences.getString(done, ""))) {
                return;
            }
            JSONObject cast = pack.optJSONObject("cast");
            java.util.Iterator<String> roles = cast == null ? java.util.Collections.emptyIterator() : cast.keys();
            while (roles.hasNext()) {
                String role = roles.next();
                putGender(role, cast.getString(role));
            }
            if (!folder.isDirectory() && !folder.mkdirs()) {
                throw new IOException("Could not create the studio folder");
            }
            for (int i = 0; i < lines.length(); i++) {
                JSONObject entry = lines.getJSONObject(i);
                String key = EnsembleRecordings.lineKey(entry.getString("role"), entry.getString("text"));
                Scene.Line line = null;
                for (Scene.Line candidate : scene.lines) {
                    if (EnsembleRecordings.lineKey(candidate.role, candidate.text).equals(key)) {
                        line = candidate;
                        break;
                    }
                }
                String audio = entry.optString("audio", "");
                if (line == null || audio.isEmpty()) {
                    continue;
                }
                JSONObject note = entry.getJSONObject("note");
                notes().put(noteKey(line, language), note);
                File target = audioFile(line, language, entry.getString("voice"), note, ".m4a");
                try (InputStream input = context.getAssets().open(SAMPLE_PACK + audio);
                     OutputStream output = new FileOutputStream(target)) {
                    output.write(readAll(input));
                }
            }
            saveNotes();
            preferences.edit().putString(done, stamp).apply();
        } catch (java.io.FileNotFoundException missing) {
            // No pack for this language yet.
        } catch (IOException | JSONException error) {
            Log.w(TAG, "Sample studio voices could not be installed", error);
        }
    }

    private byte[] speak(JSONObject note, ScriptLanguage language, String voice, String script)
        throws IOException, JSONException {
        JSONObject body = new JSONObject()
            .put("language", language.code)
            .put("script", script)
            .put("voice", voice)
            .put("emotion", note.optString("emotion", "neutral"))
            .put("pace", note.optString("pace", "normal"))
            .put("undertone", note.optString("undertone", ""))
            .put("segments", note.getJSONArray("segments"))
            .put("pronunciations", note.optJSONArray("pronunciations") == null ? new JSONArray()
                : note.getJSONArray("pronunciations"));
        JSONObject answer = new JSONObject(new String(post("/v1/speak", body), StandardCharsets.UTF_8));
        lastVoiced.set(new JSONObject()
            .put("model", answer.optString("modelVersion", answer.optString("model", "")))
            .put("source", header("X-Memo-Source"))
            .put("style", header("X-Memo-Style"))
            .put("text", header("X-Memo-Text"))
            .put("voice", voice)
            .put("at", System.currentTimeMillis()));
        // Gemini's interaction: steps of model output whose content holds the audio.
        JSONArray steps = answer.optJSONArray("steps");
        for (int i = 0; steps != null && i < steps.length(); i++) {
            JSONArray content = steps.getJSONObject(i).optJSONArray("content");
            for (int j = 0; content != null && j < content.length(); j++) {
                JSONObject part = content.getJSONObject(j);
                if ("audio".equals(part.optString("type")) && part.has("data")) {
                    byte[] audio = Base64.decode(part.getString("data"), Base64.DEFAULT);
                    String type = part.optString("mime_type", "");
                    return type.contains("wav") || isWav(audio) ? audio : wav(audio, sampleRate(type));
                }
            }
        }
        // An older voice's answer: candidates whose parts carry raw PCM.
        JSONArray candidates = answer.optJSONArray("candidates");
        JSONObject content = candidates == null || candidates.length() == 0 ? null
            : candidates.getJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        for (int i = 0; parts != null && i < parts.length(); i++) {
            JSONObject data = parts.getJSONObject(i).optJSONObject("inlineData");
            if (data != null && data.has("data")) {
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
        final Stop reason;

        LimitException(Stop reason) {
            this.reason = reason;
        }
    }

    /** Posts to the Worker, retrying a busy service a few times. */
    private byte[] post(String path, JSONObject body) throws IOException {
        return post(path, body.toString().getBytes(StandardCharsets.UTF_8), "application/json",
            java.util.Collections.emptyMap());
    }

    private byte[] post(String path, byte[] body, String type, java.util.Map<String, String> headers) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            if (cancelled) {
                throw new IOException("Cancelled");
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(WORKER + path).openConnection();
            openConnections.add(connection);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(240_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", type);
            connection.setRequestProperty("Authorization", "Bearer " + idToken(attempt > 0));
            for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            try {
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
                int code = connection.getResponseCode();
                if (code == 200) {
                    lastHeaders.set(connection.getHeaderFields());
                    return readAll(connection.getInputStream());
                }
                String error = connection.getErrorStream() == null ? ""
                    : new String(readAll(connection.getErrorStream()), StandardCharsets.UTF_8);
                if (code == 402) {
                    throw new LimitException(error.contains("extra_script_needed") ? Stop.EXTRA_SCRIPT
                        : Stop.PREMIUM_REQUIRED);
                }
                if (code == 429) {
                    throw new LimitException(error.contains("script_limit") ? Stop.SCRIPT_LIMIT
                        : error.contains("monthly_limit") ? Stop.MONTHLY_LIMIT : Stop.DAILY_LIMIT);
                }
                if (code == 401) {
                    idToken = null;
                    last = new IOException("Not signed in");
                } else if (code >= 500 && error.contains("without audio")) {
                    // The voice gave no audio for this line; asking again would not change that.
                    throw new IOException("Refused: " + code + " " + error);
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
                openConnections.remove(connection);
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
    private File audioFile(Scene.Line line, ScriptLanguage language, String voice, JSONObject note, String extension) {
        return new File(folder, sha(language.code + "\n" + voice + "\n" + line.text + "\n" + note) + extension);
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
