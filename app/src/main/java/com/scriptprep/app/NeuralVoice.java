package com.scriptprep.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Premium: natural voices in many languages, generated on the device with Supertonic 3 through
 * sherpa-onnx.
 * The model (about 145 MB) is downloaded on request and runs offline afterwards.
 */
final class NeuralVoice {
    enum State { NOT_INSTALLED, DOWNLOADING, INSTALLED, LOADING, READY, FAILED }

    interface Listener {
        void onNeuralVoiceChanged();
    }

    interface Playback {
        void onDone();

        void onError();
    }

    static final int SPEAKERS = 10;

    private static final String TAG = "ScriptPrep";
    private static final String FOLDER = "supertonic-3-tr";
    private static final String SOURCE = "https://huggingface.co/csukuangfj2/"
        + "sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/cca5a0e6c96e1d2c720986bf7e75fcc81dee3ae4/";
    private static final ModelFile[] FILES = {
        new ModelFile("duration_predictor.int8.onnx", 3_700_147L,
            "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"),
        new ModelFile("text_encoder.int8.onnx", 36_416_150L,
            "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"),
        new ModelFile("vector_estimator.int8.onnx", 78_400_833L,
            "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd"),
        new ModelFile("vocoder.int8.onnx", 25_991_073L,
            "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152"),
        new ModelFile("tts.json", 8_253L,
            "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"),
        new ModelFile("unicode_indexer.bin", 262_144L,
            "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30"),
        new ModelFile("voice.bin", 517_168L,
            "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8"),
    };
    /** Diffusion steps: 8 matches the published samples; fewer is faster but rougher. */
    private static final int STEPS = 8;
    private static final int CACHE_SIZE = 16;

    private static NeuralVoice instance;

    private final Context context;
    private final File folder;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService synthesis = Executors.newSingleThreadExecutor();
    private final ExecutorService player = Executors.newSingleThreadExecutor();
    private final AtomicInteger playToken = new AtomicInteger();
    private final Map<String, float[]> cache = Collections.synchronizedMap(
        new LinkedHashMap<String, float[]>(CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                return size() > CACHE_SIZE;
            }
        });

    private volatile State state;
    private volatile String lastError = "";
    private volatile long downloaded;
    private volatile boolean cancelDownload;
    private volatile int sampleRate = 44_100;
    private OfflineTts engine;
    private AudioTrack track;
    private Listener listener;
    private Runnable progressListener;
    private final SharedPreferences preferences;
    /** System downloads in progress, as "file name=download id" pairs separated by commas. */
    private static final String DOWNLOADS = "ai_voice_downloads";
    private volatile boolean waitingForNetwork;
    private final Runnable poll = this::pollDownloads;

    private NeuralVoice(Context context) {
        this.context = context.getApplicationContext();
        // Download bookkeeping is device specific, so it lives outside the backed-up preferences.
        preferences = this.context.getSharedPreferences("neural_voice", Context.MODE_PRIVATE);
        // App-specific external storage: the system download manager can write there, and it
        // is removed with the app.
        File external = this.context.getExternalFilesDir(null);
        folder = new File(external != null ? external : this.context.getFilesDir(), FOLDER);
        if (isInstalled()) {
            state = State.INSTALLED;
        } else if (!preferences.getString(DOWNLOADS, "").isEmpty()) {
            // A download started earlier kept running in the background.
            state = State.DOWNLOADING;
            main.post(poll);
        } else {
            state = State.NOT_INSTALLED;
        }
    }

    static synchronized NeuralVoice get(Context context) {
        if (instance == null) {
            instance = new NeuralVoice(context);
        }
        return instance;
    }

    /** The bundled native libraries cover these processor types. */
    static boolean isSupported() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi.equals("arm64-v8a") || abi.equals("armeabi-v7a") || abi.equals("x86_64")) {
                return true;
            }
        }
        return false;
    }

    static long downloadBytes() {
        long total = 0;
        for (ModelFile file : FILES) {
            total += file.size;
        }
        return total;
    }

    void setListener(Listener value) {
        listener = value;
    }

    /** Called on the main thread while bytes arrive, without a state change. */
    void setProgressListener(Runnable value) {
        progressListener = value;
    }

    /** The download is paused until a network connection is available. */
    boolean isWaitingForNetwork() {
        return waitingForNetwork;
    }

    State state() {
        return state;
    }

    /** A short technical reason for the last failure, shown so problems can be reported. */
    String lastError() {
        return lastError;
    }

    boolean isReady() {
        return state == State.READY;
    }

    /** Download progress from 0 to 100. */
    int progress() {
        return (int) Math.min(100, downloaded * 100 / downloadBytes());
    }

    private boolean isInstalled() {
        if (!new File(folder, ".complete").exists()) {
            return false;
        }
        for (ModelFile file : FILES) {
            if (new File(folder, file.name).length() != file.size) {
                return false;
            }
        }
        return true;
    }

    private void setState(State value) {
        state = value;
        main.post(() -> {
            if (listener != null) {
                listener.onNeuralVoiceChanged();
            }
        });
    }

    // ---------------------------------------------------------------- Download

    void download() {
        if (state != State.NOT_INSTALLED && state != State.FAILED) {
            return;
        }
        cancelDownload = false;
        waitingForNetwork = false;
        setState(State.DOWNLOADING);
        if (startSystemDownloads()) {
            main.post(poll);
            return;
        }
        network.execute(() -> {
            try {
                installFiles();
                setState(State.INSTALLED);
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "Neural voice download failed", error);
                lastError = describe(error);
                setState(cancelDownload ? State.NOT_INSTALLED : State.FAILED);
            }
        });
    }

    /** After a failure: loads an installed model again, otherwise resumes the download. */
    void retry() {
        if (state != State.FAILED) {
            return;
        }
        if (isInstalled()) {
            state = State.INSTALLED;
            load();
        } else {
            download();
        }
    }

    void cancelDownload() {
        cancelDownload = true;
        if (removeSystemDownloads()) {
            main.removeCallbacks(poll);
            setState(State.NOT_INSTALLED);
        }
    }

    // ---------------------------------------------------------------- System downloads

    /**
     * Hands the files to the system download manager, which keeps going when the app is closed,
     * shows its progress in the notification shade and resumes after connection drops.
     */
    private boolean startSystemDownloads() {
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        if (manager == null || context.getExternalFilesDir(null) == null) {
            return false;
        }
        if (!folder.isDirectory() && !folder.mkdirs()) {
            return false;
        }
        long needed = 0;
        for (ModelFile file : FILES) {
            if (new File(folder, file.name).length() != file.size) {
                needed += file.size;
            }
        }
        if (folder.getUsableSpace() < needed + 32L * 1024 * 1024) {
            lastError = "Not enough free space";
            state = State.FAILED;
            setState(State.FAILED);
            return true;
        }
        StringBuilder ids = new StringBuilder();
        try {
            for (ModelFile file : FILES) {
                if (new File(folder, file.name).length() == file.size) {
                    continue;
                }
                File target = new File(folder, file.name + ".download");
                if (target.exists() && !target.delete()) {
                    Log.w(TAG, "Could not remove an old download");
                }
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(SOURCE + file.name))
                    .setTitle(context.getString(R.string.ai_voice_notification))
                    .setDescription(file.name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(context, null, FOLDER + "/" + file.name + ".download")
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false);
                long id = manager.enqueue(request);
                if (ids.length() > 0) {
                    ids.append(',');
                }
                ids.append(file.name).append('=').append(id);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "System download manager refused the request", error);
            preferences.edit().putString(DOWNLOADS, ids.toString()).apply();
            removeSystemDownloads();
            return false;
        }
        preferences.edit().putString(DOWNLOADS, ids.toString()).apply();
        if (ids.length() == 0) {
            // Everything was already there; just verify and finish.
            finishSystemDownloads(Collections.emptyMap());
        }
        return true;
    }

    private Map<String, Long> systemDownloads() {
        Map<String, Long> ids = new LinkedHashMap<>();
        for (String pair : preferences.getString(DOWNLOADS, "").split(",")) {
            int split = pair.indexOf('=');
            if (split > 0) {
                try {
                    ids.put(pair.substring(0, split), Long.parseLong(pair.substring(split + 1)));
                } catch (NumberFormatException ignored) {
                    // A damaged entry is skipped.
                }
            }
        }
        return ids;
    }

    /** Cancels running system downloads; returns whether there were any. */
    private boolean removeSystemDownloads() {
        Map<String, Long> ids = systemDownloads();
        preferences.edit().remove(DOWNLOADS).apply();
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        if (ids.isEmpty() || manager == null) {
            return !ids.isEmpty();
        }
        long[] values = new long[ids.size()];
        int index = 0;
        for (long id : ids.values()) {
            values[index++] = id;
        }
        manager.remove(values);
        return true;
    }

    /** Checks the system downloads about once a second while they run. */
    private void pollDownloads() {
        Map<String, Long> ids = systemDownloads();
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        if (ids.isEmpty() || manager == null || state != State.DOWNLOADING) {
            return;
        }
        long[] values = new long[ids.size()];
        int index = 0;
        for (long id : ids.values()) {
            values[index++] = id;
        }
        long bytes = 0;
        int finished = 0;
        boolean paused = false;
        String failure = null;
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(values))) {
            int found = 0;
            while (cursor != null && cursor.moveToNext()) {
                found++;
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long so = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                bytes += Math.max(0, so);
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    finished++;
                } else if (status == DownloadManager.STATUS_FAILED) {
                    failure = "Download error "
                        + cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                } else if (status == DownloadManager.STATUS_PAUSED) {
                    paused = true;
                }
            }
            if (found < ids.size() && failure == null) {
                // The user cancelled it from the notification or system settings.
                failure = "Download was cancelled";
            }
        } catch (RuntimeException error) {
            failure = describe(error);
        }
        for (ModelFile file : FILES) {
            if (!ids.containsKey(file.name) && new File(folder, file.name).length() == file.size) {
                bytes += file.size;
            }
        }
        downloaded = bytes;
        waitingForNetwork = paused;
        if (failure != null) {
            lastError = failure;
            removeSystemDownloads();
            setState(State.FAILED);
            return;
        }
        if (finished == ids.size()) {
            finishSystemDownloads(ids);
            return;
        }
        Runnable progress = progressListener;
        if (progress != null) {
            progress.run();
        }
        main.postDelayed(poll, 1000);
    }

    /** Verifies every finished file before the model counts as installed. */
    private void finishSystemDownloads(Map<String, Long> ids) {
        network.execute(() -> {
            try {
                for (ModelFile file : FILES) {
                    File target = new File(folder, file.name);
                    if (target.length() == file.size && !ids.containsKey(file.name)) {
                        continue;
                    }
                    File done = new File(folder, file.name + ".download");
                    if (!file.sha256.equals(sha256(done))) {
                        throw new IOException("Checksum mismatch for " + file.name);
                    }
                    if (target.exists() && !target.delete()) {
                        throw new IOException("Could not replace " + file.name);
                    }
                    if (!done.renameTo(target)) {
                        throw new IOException("Could not store " + file.name);
                    }
                }
                File marker = new File(folder, ".complete");
                if (!marker.exists() && !marker.createNewFile()) {
                    throw new IOException("Could not mark the model as installed");
                }
                // The files have been moved, so this only clears the system's records.
                main.post(this::removeSystemDownloads);
                setState(State.INSTALLED);
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "Neural voice verification failed", error);
                lastError = describe(error);
                main.post(this::removeSystemDownloads);
                setState(State.FAILED);
            }
        });
    }

    private void installFiles() throws IOException {
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("Could not create the model folder");
        }
        long needed = 0;
        for (ModelFile file : FILES) {
            needed += file.size - Math.min(file.size, existing(file));
        }
        if (folder.getUsableSpace() < needed + 32L * 1024 * 1024) {
            throw new IOException("Not enough free space");
        }
        downloaded = 0;
        for (ModelFile file : FILES) {
            File target = new File(folder, file.name);
            if (target.length() == file.size) {
                downloaded += file.size;
                continue;
            }
            File partial = new File(folder, file.name + ".part");
            fetch(file, partial);
            if (!file.sha256.equals(sha256(partial))) {
                if (!partial.delete()) {
                    Log.w(TAG, "Could not remove a damaged download");
                }
                throw new IOException("Checksum mismatch for " + file.name);
            }
            if (!partial.renameTo(target)) {
                throw new IOException("Could not store " + file.name);
            }
        }
        if (!new File(folder, ".complete").createNewFile() && !new File(folder, ".complete").exists()) {
            throw new IOException("Could not mark the model as installed");
        }
    }

    private long existing(ModelFile file) {
        File target = new File(folder, file.name);
        return target.length() == file.size ? file.size : new File(folder, file.name + ".part").length();
    }

    /** Downloads one file, resuming a partial download when the server allows it. */
    private void fetch(ModelFile file, File partial) throws IOException {
        long have = partial.length();
        if (have > file.size) {
            have = 0;
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(SOURCE + file.name).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        if (have > 0) {
            connection.setRequestProperty("Range", "bytes=" + have + "-");
        }
        try {
            int code = connection.getResponseCode();
            boolean resume = code == HttpURLConnection.HTTP_PARTIAL;
            if (!resume && code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " for " + file.name);
            }
            if (!resume) {
                have = 0;
            }
            long base = downloaded;
            try (InputStream input = connection.getInputStream();
                 OutputStream output = new FileOutputStream(partial, resume)) {
                byte[] buffer = new byte[1 << 16];
                long written = have;
                long lastReport = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (cancelDownload) {
                        throw new IOException("Cancelled");
                    }
                    output.write(buffer, 0, count);
                    written += count;
                    downloaded = base + written;
                    if (downloaded - lastReport > 1_000_000) {
                        lastReport = downloaded;
                        Runnable progress = progressListener;
                        if (progress != null) {
                            main.post(progress);
                        }
                    }
                }
                downloaded = base + written;
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", value));
        }
        return hex.toString();
    }

    /** Removes the model and frees its memory. */
    void delete() {
        stop();
        cancelDownload = true;
        main.removeCallbacks(poll);
        removeSystemDownloads();
        synthesis.execute(() -> {
            releaseEngine();
            cache.clear();
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!file.delete()) {
                        Log.w(TAG, "Could not delete " + file.getName());
                    }
                }
            }
            setState(State.NOT_INSTALLED);
        });
    }

    // ---------------------------------------------------------------- Engine

    /** Loads the model into memory in the background; listeners hear when it is ready. */
    void load() {
        if (state != State.INSTALLED) {
            return;
        }
        setState(State.LOADING);
        synthesis.execute(() -> {
            try {
                OfflineTtsSupertonicModelConfig supertonic = new OfflineTtsSupertonicModelConfig();
                supertonic.setDurationPredictor(path("duration_predictor.int8.onnx"));
                supertonic.setTextEncoder(path("text_encoder.int8.onnx"));
                supertonic.setVectorEstimator(path("vector_estimator.int8.onnx"));
                supertonic.setVocoder(path("vocoder.int8.onnx"));
                supertonic.setTtsJson(path("tts.json"));
                supertonic.setUnicodeIndexer(path("unicode_indexer.bin"));
                supertonic.setVoiceStyle(path("voice.bin"));
                OfflineTtsModelConfig model = new OfflineTtsModelConfig();
                model.setSupertonic(supertonic);
                model.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
                model.setDebug(false);
                model.setProvider("cpu");
                OfflineTtsConfig config = new OfflineTtsConfig();
                config.setModel(model);
                engine = new OfflineTts(null, config);
                sampleRate = engine.sampleRate();
                setState(State.READY);
            } catch (RuntimeException | LinkageError error) {
                Log.w(TAG, "Could not load the neural voice", error);
                lastError = describe(error);
                engine = null;
                setState(State.FAILED);
            }
        });
    }

    /** Frees the model memory; it can be loaded again later. */
    void unload() {
        stop();
        synthesis.execute(() -> {
            releaseEngine();
            cache.clear();
            if (state == State.READY || state == State.LOADING) {
                setState(isInstalled() ? State.INSTALLED : State.NOT_INSTALLED);
            }
        });
    }

    private void releaseEngine() {
        if (engine != null) {
            engine.release();
            engine = null;
        }
    }

    private String path(String name) {
        return new File(folder, name).getAbsolutePath();
    }

    private static String key(String text, String language, int speaker, float speed) {
        return language + "|" + speaker + "|" + speed + "|" + text;
    }

    /** Must run on the synthesis thread. */
    private float[] generate(String text, String language, int speaker, float speed) {
        String key = key(text, language, speaker, speed);
        float[] cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (engine == null) {
            return null;
        }
        GenerationConfig config = new GenerationConfig();
        config.setSid(Math.max(0, Math.min(SPEAKERS - 1, speaker)));
        config.setNumSteps(STEPS);
        config.setSpeed(speed);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("lang", language);
        config.setExtra(extra);
        GeneratedAudio audio = engine.generateWithConfig(text, config);
        float[] samples = audio.getSamples();
        sampleRate = audio.getSampleRate();
        cache.put(key, samples);
        return samples;
    }

    /** Generates a line ahead of time so it plays without delay. */
    void prefetch(String text, String language, int speaker, float speed) {
        if (state != State.READY || cache.containsKey(key(text, language, speaker, speed))) {
            return;
        }
        synthesis.execute(() -> {
            try {
                generate(text, language, speaker, speed);
            } catch (RuntimeException error) {
                Log.w(TAG, "Prefetch failed", error);
            }
        });
    }

    /** Speaks a line; the callback runs on the main thread unless playback is stopped first. */
    void speak(String text, String language, int speaker, float speed, float pitch, Playback callback) {
        int token = playToken.incrementAndGet();
        synthesis.execute(() -> {
            float[] samples;
            try {
                samples = generate(text, language, speaker, speed);
            } catch (RuntimeException error) {
                Log.w(TAG, "Synthesis failed", error);
                samples = null;
            }
            if (token != playToken.get()) {
                return;
            }
            if (samples == null || samples.length == 0) {
                main.post(() -> {
                    if (token == playToken.get()) {
                        callback.onError();
                    }
                });
                return;
            }
            float[] audio = samples;
            int rate = sampleRate;
            player.execute(() -> play(audio, rate, pitch, token, callback));
        });
    }

    private void play(float[] samples, int rate, float pitch, int token, Playback callback) {
        if (token != playToken.get()) {
            return;
        }
        AudioTrack output;
        try {
            int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT);
            output = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(minimum, rate / 5 * 4))
                .build();
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not open audio output", error);
            finish(token, callback, false);
            return;
        }
        synchronized (this) {
            track = output;
        }
        try {
            if (Math.abs(pitch - 1f) > 0.01f) {
                try {
                    output.setPlaybackParams(new PlaybackParams().setPitch(pitch).setSpeed(1f));
                } catch (RuntimeException ignored) {
                    // Pitch changes are optional.
                }
            }
            output.play();
            int offset = 0;
            while (offset < samples.length && token == playToken.get()) {
                int count = Math.min(4096, samples.length - offset);
                int written = output.write(samples, offset, count, AudioTrack.WRITE_BLOCKING);
                if (written < 0) {
                    throw new IllegalStateException("AudioTrack write failed: " + written);
                }
                offset += written;
            }
            // Wait until the last samples have actually been heard.
            long deadline = System.currentTimeMillis() + samples.length * 1000L / rate + 2000;
            while (token == playToken.get() && output.getPlaybackHeadPosition() < samples.length
                && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            finish(token, callback, true);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            Log.w(TAG, "Playback failed", error);
            finish(token, callback, false);
        } finally {
            synchronized (this) {
                if (track == output) {
                    track = null;
                }
            }
            try {
                output.stop();
            } catch (IllegalStateException ignored) {
                // Already stopped.
            }
            output.release();
        }
    }

    private void finish(int token, Playback callback, boolean done) {
        main.post(() -> {
            if (token == playToken.get()) {
                if (done) {
                    callback.onDone();
                } else {
                    callback.onError();
                }
            }
        });
    }

    /** Stops the current line; its callback will not run. */
    void stop() {
        playToken.incrementAndGet();
        synchronized (this) {
            if (track != null) {
                try {
                    track.pause();
                    track.flush();
                } catch (IllegalStateException ignored) {
                    // The player thread releases it.
                }
            }
        }
    }

    private static final class ModelFile {
        final String name;
        final long size;
        final String sha256;

        ModelFile(String name, long size, String sha256) {
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
        }
    }
}
