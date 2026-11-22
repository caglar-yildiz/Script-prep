package com.scriptprep.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.Blob;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Recorded lines of an ensemble production.
 *
 * <p>A recording belongs to a line through {@link #lineKey}, a hash of the character and the
 * words, so it still matches after lines are added or moved, and stops matching once the words
 * change. Recordings live in Firestore (productions/{id}/recordings/{lineKey}_{uid}) and are
 * copied to app storage, so rehearsals play them offline. Deleting a recording leaves an empty
 * marker, so other devices learn about it on their next sync.
 *
 * <p>A Firestore document holds at most 1 MiB, so a long speech is split: the main document
 * carries the first {@link #PART_BYTES} and the number of parts, and {lineKey}_{uid}_{n} carry
 * the rest. All parts share a take id and are written in one batch, so a device never sees half
 * of a new take.
 */
final class EnsembleRecordings {
    private static final String TAG = "ScriptPrep";
    /** Audio per Firestore document; the rules allow up to 900 KB. */
    static final int PART_BYTES = 800_000;
    /** Ten minutes at 32 kbit/s is about 2.4 MB, three parts. */
    static final int MAX_MILLIS = 10 * 60_000;
    static final int MAX_BYTES = 6 * PART_BYTES;
    static final int BIT_RATE = 32_000;
    private static final String SYNC_PREFIX = "ensemble_sync_";
    private static final ExecutorService DISK = Executors.newSingleThreadExecutor();

    interface Done {
        void done(boolean ok);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    EnsembleRecordings(Context context) {
        this.context = context.getApplicationContext();
    }

    // ------------------------------------------------------------ Line identity

    /** Identifies a line by its character and words; spacing and letter case do not count. */
    static String lineKey(String role, String text) {
        String normalized = role.trim().toLowerCase(Locale.ROOT) + "\n"
            + text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    // ------------------------------------------------------------ Local copies

    static File directory(Context context, String productionId) {
        return new File(context.getFilesDir(), "ensemble/" + productionId.replaceAll("[^A-Za-z0-9_-]", ""));
    }

    private File file(String productionId, String key, String uid) {
        return new File(directory(context, productionId), key + "_" + uid + ".m4a");
    }

    File mine(String productionId, String key) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            return null;
        }
        File file = file(productionId, key, user.getUid());
        return file.isFile() ? file : null;
    }

    /** Removes a production's recordings from this device, after leaving or deleting it. */
    void forget(String productionId) {
        File directory = directory(context, productionId);
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        directory.delete();
        preferences().edit().remove(SYNC_PREFIX + productionId).apply();
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences("script_prep", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ Sync

    /** Downloads recordings added, changed or deleted since the last sync. */
    void sync(String productionId, Done done) {
        long since = preferences().getLong(SYNC_PREFIX + productionId, 0);
        CollectionReference collection = recordings(productionId);
        collection
            .whereGreaterThan("updatedAt", new Timestamp(since / 1000, (int) (since % 1000) * 1_000_000))
            .orderBy("updatedAt", Query.Direction.ASCENDING)
            .get()
            .addOnSuccessListener(snapshot -> {
                Map<String, DocumentSnapshot> byId = new HashMap<>();
                List<DocumentSnapshot> takes = new ArrayList<>();
                for (QueryDocumentSnapshot document : snapshot) {
                    byId.put(document.getId(), document);
                    if (document.get("part") == null) {
                        takes.add(document);
                    }
                }
                DISK.execute(() -> {
                    long latest = since;
                    boolean ok = true;
                    for (DocumentSnapshot take : takes) {
                        String key = take.getString("lineKey");
                        String uid = take.getString("uid");
                        Timestamp updated = take.getTimestamp("updatedAt");
                        if (key == null || uid == null || updated == null) {
                            continue;
                        }
                        File target = file(productionId, key, uid);
                        Blob first = take.getBlob("audio");
                        if (Boolean.TRUE.equals(take.getBoolean("deleted")) || first == null
                            || first.toBytes().length == 0) {
                            target.delete();
                        } else {
                            byte[] audio = assemble(collection, take, byId);
                            if (audio == null || !write(target, audio)) {
                                // Takes come oldest first: keep what is done, retry from here.
                                ok = false;
                                break;
                            }
                        }
                        latest = Math.max(latest, updated.toDate().getTime());
                    }
                    preferences().edit().putLong(SYNC_PREFIX + productionId, latest).apply();
                    boolean result = ok;
                    main.post(() -> done.done(result));
                });
            })
            .addOnFailureListener(error -> {
                Log.w(TAG, "Could not sync recordings", error);
                done.done(false);
            });
    }

    /**
     * Joins a take's parts. Parts come with the take in the same sync; one missing from it is
     * fetched. Runs on the disk thread, so it may wait for Firestore.
     */
    private static byte[] assemble(CollectionReference collection, DocumentSnapshot take,
                                   Map<String, DocumentSnapshot> byId) {
        Long count = take.getLong("parts");
        int parts = count == null ? 1 : (int) Math.max(1, Math.min(count, MAX_BYTES / PART_BYTES));
        String takeId = take.getString("take");
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        byte[] first = take.getBlob("audio").toBytes();
        audio.write(first, 0, first.length);
        for (int n = 1; n < parts; n++) {
            String id = take.getId() + "_" + n;
            DocumentSnapshot part = byId.get(id);
            try {
                if (part == null) {
                    part = Tasks.await(collection.document(id).get(), 30, TimeUnit.SECONDS);
                }
            } catch (ExecutionException | InterruptedException | TimeoutException error) {
                Log.w(TAG, "Could not fetch part of a recording", error);
                return null;
            }
            Blob bytes = part.getBlob("audio");
            if (bytes == null || takeId == null || !takeId.equals(part.getString("take"))) {
                // A newer take is on its way; the next sync brings it whole.
                return null;
            }
            byte[] chunk = bytes.toBytes();
            audio.write(chunk, 0, chunk.length);
        }
        return audio.toByteArray();
    }

    private static boolean write(File target, byte[] bytes) {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return false;
        }
        File partial = new File(target.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(partial)) {
            out.write(bytes);
        } catch (IOException error) {
            Log.w(TAG, "Could not save a recording", error);
            partial.delete();
            return false;
        }
        return partial.renameTo(target);
    }

    // ------------------------------------------------------------ The user's own lines

    /** Keeps a new recording on this device at once and uploads it, split into parts if long. */
    void save(String productionId, String key, String role, byte[] audio, long millis, String name, Done done) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || audio.length == 0 || audio.length > MAX_BYTES) {
            done.done(false);
            return;
        }
        File target = file(productionId, key, user.getUid());
        DISK.execute(() -> {
            boolean saved = write(target, audio);
            main.post(() -> {
                if (!saved) {
                    done.done(false);
                    return;
                }
                String uid = user.getUid();
                String takeId = UUID.randomUUID().toString();
                int parts = (audio.length + PART_BYTES - 1) / PART_BYTES;
                WriteBatch batch = FirebaseFirestore.getInstance().batch();
                for (int n = 0; n < parts; n++) {
                    byte[] chunk = Arrays.copyOfRange(audio, n * PART_BYTES, Math.min(audio.length, (n + 1) * PART_BYTES));
                    Map<String, Object> data = new HashMap<>();
                    data.put("uid", uid);
                    data.put("lineKey", key);
                    data.put("take", takeId);
                    data.put("audio", Blob.fromBytes(chunk));
                    data.put("updatedAt", FieldValue.serverTimestamp());
                    if (n == 0) {
                        data.put("name", name);
                        data.put("role", role);
                        data.put("parts", parts);
                        data.put("durationMs", millis);
                        data.put("deleted", false);
                    } else {
                        data.put("part", n);
                    }
                    batch.set(recordings(productionId).document(id(key, uid, n)), data);
                }
                removeExtraParts(productionId, key, uid, parts, batch, done);
            });
        });
    }

    /** Deletes the user's recording of a line here and, through an empty marker, everywhere. */
    void delete(String productionId, String key, Done done) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            done.done(false);
            return;
        }
        file(productionId, key, user.getUid()).delete();
        Map<String, Object> data = new HashMap<>();
        data.put("uid", user.getUid());
        data.put("lineKey", key);
        data.put("audio", Blob.fromBytes(new byte[0]));
        data.put("parts", 0);
        data.put("deleted", true);
        data.put("updatedAt", FieldValue.serverTimestamp());
        WriteBatch batch = FirebaseFirestore.getInstance().batch();
        batch.set(recordings(productionId).document(id(key, user.getUid(), 0)), data);
        removeExtraParts(productionId, key, user.getUid(), 1, batch, done);
    }

    /**
     * Adds deletes for parts an earlier, longer take left behind, then commits. Offline the
     * lookup fails; the batch is still committed and the leftover parts are harmless, because
     * a take only uses parts with its own take id.
     */
    private void removeExtraParts(String productionId, String key, String uid, int keep,
                                  WriteBatch batch, Done done) {
        recordings(productionId).whereEqualTo("uid", uid).whereEqualTo("lineKey", key).get()
            .addOnCompleteListener(found -> {
                if (found.isSuccessful()) {
                    for (QueryDocumentSnapshot part : found.getResult()) {
                        Long n = part.getLong("part");
                        if (n != null && n >= keep) {
                            batch.delete(part.getReference());
                        }
                    }
                }
                batch.commit()
                    .addOnSuccessListener(ignored -> done.done(true))
                    .addOnFailureListener(error -> {
                        Log.w(TAG, "Could not upload a recording", error);
                        done.done(false);
                    });
            });
    }

    private static String id(String key, String uid, int part) {
        return part == 0 ? key + "_" + uid : key + "_" + uid + "_" + part;
    }

    private static CollectionReference recordings(String productionId) {
        return FirebaseFirestore.getInstance().collection("productions").document(productionId)
            .collection("recordings");
    }

    // ------------------------------------------------------------ Playback

    /**
     * The recordings a rehearsal plays. Each character keeps one voice: the member who recorded
     * most of that character's lines, falling back to anyone who recorded a line they did not.
     */
    static final class Voices {
        /** line key → uid → file */
        private final Map<String, Map<String, File>> recordings = new HashMap<>();
        /** character → uid whose recordings play first */
        private final Map<String, String> preferred = new HashMap<>();

        static Voices load(Context context, String productionId, Scene scene) {
            Voices voices = new Voices();
            File[] files = directory(context, productionId).listFiles();
            if (files == null) {
                return voices;
            }
            for (File file : files) {
                String name = file.getName();
                int split = name.indexOf('_');
                if (!name.endsWith(".m4a") || split <= 0) {
                    continue;
                }
                String key = name.substring(0, split);
                String uid = name.substring(split + 1, name.length() - 4);
                voices.recordings.computeIfAbsent(key, ignored -> new HashMap<>()).put(uid, file);
            }
            Map<String, Map<String, Integer>> counts = new HashMap<>();
            for (Scene.Line line : scene.lines) {
                Map<String, File> takes = voices.recordings.get(lineKey(line.role, line.text));
                if (takes != null) {
                    Map<String, Integer> byUid = counts.computeIfAbsent(line.role, ignored -> new HashMap<>());
                    for (String uid : takes.keySet()) {
                        byUid.merge(uid, 1, Integer::sum);
                    }
                }
            }
            for (Map.Entry<String, Map<String, Integer>> entry : counts.entrySet()) {
                String best = null;
                int most = 0;
                for (Map.Entry<String, Integer> count : entry.getValue().entrySet()) {
                    if (count.getValue() > most || (count.getValue() == most && best != null
                        && count.getKey().compareTo(best) < 0)) {
                        best = count.getKey();
                        most = count.getValue();
                    }
                }
                voices.preferred.put(entry.getKey(), best);
            }
            return voices;
        }

        boolean isEmpty() {
            return recordings.isEmpty();
        }

        File find(Scene.Line line) {
            Map<String, File> takes = recordings.get(lineKey(line.role, line.text));
            if (takes == null || takes.isEmpty()) {
                return null;
            }
            File preferredTake = takes.get(preferred.get(line.role));
            File take = preferredTake != null ? preferredTake : takes.values().iterator().next();
            return take.isFile() ? take : null;
        }
    }
}
