package com.scriptprep.app;

import static com.scriptprep.app.Ui.ACCENT;
import static com.scriptprep.app.Ui.ACCENT_CONTAINER;
import static com.scriptprep.app.Ui.BACKGROUND;
import static com.scriptprep.app.Ui.DANGER;
import static com.scriptprep.app.Ui.ON_ACCENT_CONTAINER;
import static com.scriptprep.app.Ui.SURFACE;
import static com.scriptprep.app.Ui.SURFACE_HIGHEST;
import static com.scriptprep.app.Ui.TEAL;
import static com.scriptprep.app.Ui.TEAL_CONTAINER;
import static com.scriptprep.app.Ui.TEXT;
import static com.scriptprep.app.Ui.TEXT_SECONDARY;
import static com.scriptprep.app.Ui.TEXT_TERTIARY;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.scriptprep.app.Ui.Kind;
import com.scriptprep.app.Ui.Type;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Records the actor's own lines for an ensemble production, one line at a time, with the cue
 * line above it. Each take is kept on the device and uploaded for the rest of the cast.
 */
public final class RecordLinesActivity extends Activity {
    private static final String TAG = "ScriptPrep";
    static final String EXTRA_PRODUCTION = "production";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_TEXT = "text";
    static final String EXTRA_CATEGORY = "category";
    static final String EXTRA_ROLES = "roles";
    static final String EXTRA_NAME = "name";
    private static final int REQUEST_MICROPHONE = 21;
    /** Recordings shorter than this are treated as a slip of the finger. */
    private static final long MIN_MILLIS = 400;

    /** One of the actor's lines and the line before it that cues it. */
    private static final class Take {
        final Scene.Line line;
        final Scene.Line cue;
        final String key;

        Take(Scene.Line line, Scene.Line cue) {
            this.line = line;
            this.cue = cue;
            this.key = EnsembleRecordings.lineKey(line.role, line.text);
        }
    }

    private enum State { IDLE, RECORDING, PLAYING }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Take> takes = new ArrayList<>();
    private final Set<String> uploading = new HashSet<>();
    private Ui ui;
    private EnsembleRecordings recordings;
    private String productionId;
    private String title;
    private String name;
    private int index;
    private State state = State.IDLE;

    private MediaRecorder recorder;
    private File recordingFile;
    private long recordingStarted;
    private MediaPlayer player;
    private TextView timer;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (state == State.RECORDING && timer != null) {
                long elapsed = SystemClock.elapsedRealtime() - recordingStarted;
                timer.setText(getString(R.string.record_timer, clock(elapsed), clock(EnsembleRecordings.MAX_MILLIS)));
                main.postDelayed(this, 250);
            }
        }
    };

    static Intent intent(Context context, Ensemble.Production production, List<String> roles, String name) {
        return new Intent(context, RecordLinesActivity.class)
            .putExtra(EXTRA_PRODUCTION, production.id)
            .putExtra(EXTRA_TITLE, production.title)
            .putExtra(EXTRA_TEXT, production.text)
            .putExtra(EXTRA_CATEGORY, production.category.name())
            .putStringArrayListExtra(EXTRA_ROLES, new ArrayList<>(roles))
            .putExtra(EXTRA_NAME, name);
    }

    /** The actor's distinct lines, each with its cue; a repeated line is recorded once. */
    static List<Scene.Line[]> ownLines(Scene scene, List<String> roles) {
        List<Scene.Line[]> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < scene.lines.size(); i++) {
            Scene.Line line = scene.lines.get(i);
            if (!roles.contains(line.role) || !seen.add(EnsembleRecordings.lineKey(line.role, line.text))) {
                continue;
            }
            Scene.Line cue = null;
            for (int j = i - 1; j >= 0; j--) {
                if (!roles.contains(scene.lines.get(j).role)) {
                    cue = scene.lines.get(j);
                    break;
                }
            }
            result.add(new Scene.Line[] {line, cue});
        }
        return result;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.wrapLegacyContext(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        recordings = new EnsembleRecordings(this);
        Intent intent = getIntent();
        productionId = intent.getStringExtra(EXTRA_PRODUCTION);
        title = intent.getStringExtra(EXTRA_TITLE);
        name = intent.getStringExtra(EXTRA_NAME);
        List<String> roles = intent.getStringArrayListExtra(EXTRA_ROLES);
        String text = intent.getStringExtra(EXTRA_TEXT);
        if (productionId == null || text == null || roles == null || roles.isEmpty()) {
            finish();
            return;
        }
        TextCategory category = TextCategory.fromSavedValue(intent.getStringExtra(EXTRA_CATEGORY));
        Scene scene = category.parse(text, getString(R.string.solo_role));
        for (Scene.Line[] pair : ownLines(scene, roles)) {
            takes.add(new Take(pair[0], pair[1]));
        }
        index = state == null ? firstMissing() : Math.min(state.getInt("index"), Math.max(0, takes.size() - 1));
        drawBehindSystemBars();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("index", index);
    }

    @Override
    protected void onStop() {
        // Leaving the screen ends a take without saving it.
        if (state == State.RECORDING) {
            stopRecording(false);
        }
        stopPlaying();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        releaseRecorder();
        stopPlaying();
        super.onDestroy();
    }

    private int firstMissing() {
        for (int i = 0; i < takes.size(); i++) {
            if (recordings.mine(productionId, takes.get(i).key) == null) {
                return i;
            }
        }
        return 0;
    }

    private int recordedCount() {
        int count = 0;
        for (Take take : takes) {
            if (recordings.mine(productionId, take.key) != null) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------ Frame

    @SuppressWarnings("deprecation")
    private void drawBehindSystemBars() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @SuppressWarnings("deprecation")
    private static int[] systemInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets value = insets.getInsets(WindowInsets.Type.systemBars()
                | WindowInsets.Type.displayCutout());
            return new int[] {value.left, value.top, value.right, value.bottom};
        }
        return new int[] {insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
            insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()};
    }

    private void render() {
        timer = null;
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(BACKGROUND);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
        bar.setMinimumHeight(ui.dp(60));
        bar.addView(ui.iconButton(R.drawable.ic_back, getString(R.string.action_back), TEXT, 0, 48,
            view -> finish()));
        TextView label = ui.text(getString(R.string.record_title), Type.TITLE, TEXT);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.leftMargin = ui.dp(4);
        bar.addView(label, labelParams);
        column.addView(bar, Ui.matchWidth());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(20));
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(16));
        footer.setBackgroundColor(SURFACE);
        column.addView(footer, Ui.matchWidth());

        if (takes.isEmpty()) {
            content.addView(ui.text(getString(R.string.record_no_lines), Type.BODY_LARGE, TEXT_SECONDARY),
                ui.spaced(16));
        } else {
            renderTake(content, footer);
        }

        column.setOnApplyWindowInsetsListener((view, insets) -> {
            int[] bars = systemInsets(insets);
            view.setPadding(bars[0], bars[1], bars[2], bars[3]);
            return insets;
        });
        setContentView(column);
    }

    private void renderTake(LinearLayout content, LinearLayout footer) {
        Take take = takes.get(index);
        int recorded = recordedCount();
        content.addView(ui.text(title, Type.OVERLINE, ACCENT), ui.spaced(8));
        content.addView(ui.text(getString(R.string.record_progress, recorded, takes.size()),
            Type.BODY, TEXT_SECONDARY), ui.spaced(6));
        LinearLayout progress = ui.progress(recorded, takes.size());
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, ui.dp(6));
        progressParams.topMargin = ui.dp(10);
        content.addView(progress, progressParams);

        // Cue
        if (take.cue != null) {
            LinearLayout cue = ui.card(SURFACE, null);
            cue.addView(ui.text(getString(R.string.record_cue, take.cue.role), Type.OVERLINE, TEXT_TERTIARY));
            TextView cueText = ui.serif(take.cue.text, 17, TEXT_SECONDARY);
            cue.addView(cueText, ui.spaced(6));
            content.addView(cue, ui.spaced(20));
        }

        // The actor's line
        LinearLayout own = ui.card(ACCENT_CONTAINER, null);
        own.addView(ui.text(getString(R.string.record_your_line, take.line.role, index + 1, takes.size()),
            Type.OVERLINE, ON_ACCENT_CONTAINER));
        own.addView(ui.serif(take.line.text, 22, TEXT), ui.spaced(8));
        content.addView(own, ui.spaced(take.cue == null ? 20 : 10));

        File mine = recordings.mine(productionId, take.key);
        boolean has = mine != null;
        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        String statusText;
        if (state == State.RECORDING) {
            statusText = getString(R.string.record_recording);
        } else if (uploading.contains(take.key)) {
            statusText = getString(R.string.record_uploading);
        } else {
            statusText = getString(has ? R.string.record_saved : R.string.record_missing);
        }
        status.addView(ui.chip(statusText, has || state == State.RECORDING ? TEAL : TEXT_SECONDARY,
            has || state == State.RECORDING ? TEAL_CONTAINER : SURFACE_HIGHEST));
        content.addView(status, ui.spaced(14));

        // Footer: record controls, then navigation
        if (state == State.RECORDING) {
            timer = ui.text(getString(R.string.record_timer, clock(0), clock(EnsembleRecordings.MAX_MILLIS)), Type.TITLE, DANGER);
            timer.setGravity(Gravity.CENTER);
            footer.addView(timer, Ui.matchWidth());
            footer.addView(ui.button(getString(R.string.record_stop), R.drawable.ic_stop, Kind.PRIMARY,
                view -> stopRecording(true)), ui.spaced(10));
            main.post(tick);
            return;
        }
        if (has) {
            LinearLayout row = new LinearLayout(this);
            boolean playing = state == State.PLAYING;
            row.addView(ui.button(getString(playing ? R.string.stop_line : R.string.record_listen),
                playing ? R.drawable.ic_stop : R.drawable.ic_play, Kind.TONAL,
                view -> togglePlay(mine)), new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams again = new LinearLayout.LayoutParams(0, -2, 1);
            again.leftMargin = ui.dp(8);
            row.addView(ui.button(getString(R.string.record_again), R.drawable.ic_mic, Kind.PRIMARY,
                view -> startRecording()), again);
            footer.addView(row, Ui.matchWidth());
            footer.addView(ui.button(getString(R.string.action_delete), R.drawable.ic_delete, Kind.TEXT,
                view -> confirmDelete(take)), ui.spaced(4));
        } else {
            footer.addView(ui.button(getString(R.string.record_start), R.drawable.ic_mic, Kind.PRIMARY,
                view -> startRecording()), Ui.matchWidth());
        }

        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.addView(ui.button(getString(R.string.previous_line), R.drawable.ic_previous, Kind.TEXT,
            index > 0 ? view -> go(index - 1) : null), new LinearLayout.LayoutParams(0, -2, 1));
        navigation.addView(ui.button(getString(R.string.record_next), R.drawable.ic_next, Kind.TEXT,
            index < takes.size() - 1 ? view -> go(index + 1) : null), new LinearLayout.LayoutParams(0, -2, 1));
        navigation.getChildAt(0).setAlpha(index > 0 ? 1f : 0.4f);
        navigation.getChildAt(1).setAlpha(index < takes.size() - 1 ? 1f : 0.4f);
        footer.addView(navigation, ui.spaced(6));
    }

    private void go(int target) {
        stopPlaying();
        index = target;
        render();
    }

    private static String clock(long millis) {
        long seconds = millis / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    // ------------------------------------------------------------ Recording

    private void startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            return;
        }
        stopPlaying();
        releaseRecorder();
        recordingFile = new File(getCacheDir(), "take.m4a");
        recordingFile.delete();
        try {
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : newLegacyRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(44_100);
            recorder.setAudioEncodingBitRate(EnsembleRecordings.BIT_RATE);
            recorder.setMaxDuration(EnsembleRecordings.MAX_MILLIS);
            recorder.setOutputFile(recordingFile.getPath());
            recorder.setOnInfoListener((ignored, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    main.post(() -> stopRecording(true));
                }
            });
            recorder.prepare();
            recorder.start();
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Could not start recording", error);
            releaseRecorder();
            Toast.makeText(this, R.string.record_failed, Toast.LENGTH_LONG).show();
            return;
        }
        recordingStarted = SystemClock.elapsedRealtime();
        state = State.RECORDING;
        render();
    }

    @SuppressWarnings("deprecation")
    private static MediaRecorder newLegacyRecorder() {
        return new MediaRecorder();
    }

    private void stopRecording(boolean keep) {
        if (state != State.RECORDING) {
            return;
        }
        long millis = SystemClock.elapsedRealtime() - recordingStarted;
        boolean finished;
        try {
            recorder.stop();
            finished = true;
        } catch (RuntimeException error) {
            // stop() throws when nothing was captured, for example after a very short tap.
            finished = false;
        }
        releaseRecorder();
        state = State.IDLE;
        main.removeCallbacks(tick);
        if (!keep || !finished || millis < MIN_MILLIS) {
            if (keep) {
                Toast.makeText(this, R.string.record_too_short, Toast.LENGTH_SHORT).show();
            }
            render();
            return;
        }
        Take take = takes.get(index);
        byte[] audio;
        try {
            audio = readAll(recordingFile);
        } catch (IOException error) {
            Toast.makeText(this, R.string.record_failed, Toast.LENGTH_LONG).show();
            render();
            return;
        }
        if (audio.length > EnsembleRecordings.MAX_BYTES) {
            Toast.makeText(this, R.string.record_too_long, Toast.LENGTH_LONG).show();
            render();
            return;
        }
        uploading.add(take.key);
        recordings.save(productionId, take.key, take.line.role, audio, Math.min(millis, EnsembleRecordings.MAX_MILLIS),
            name == null ? "" : name, ok -> {
                uploading.remove(take.key);
                if (!ok && !isDestroyed()) {
                    Toast.makeText(this, R.string.record_upload_failed, Toast.LENGTH_LONG).show();
                }
                if (!isDestroyed() && state == State.IDLE) {
                    render();
                }
            });
        render();
    }

    private static byte[] readAll(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < bytes.length) {
                int count = in.read(bytes, read, bytes.length - read);
                if (count < 0) {
                    throw new IOException("Recording ended early");
                }
                read += count;
            }
        }
        return bytes;
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try {
                recorder.reset();
            } catch (RuntimeException ignored) {
                // Already stopped.
            }
            recorder.release();
            recorder = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_MICROPHONE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording();
            } else {
                Toast.makeText(this, R.string.record_permission, Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------ Listening and deleting

    private void togglePlay(File file) {
        if (state == State.PLAYING) {
            stopPlaying();
            render();
            return;
        }
        try {
            player = new MediaPlayer();
            player.setDataSource(file.getPath());
            player.setOnCompletionListener(done -> {
                stopPlaying();
                render();
            });
            player.prepare();
            player.start();
            state = State.PLAYING;
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Could not play a recording", error);
            stopPlaying();
            Toast.makeText(this, R.string.speech_failed, Toast.LENGTH_SHORT).show();
        }
        render();
    }

    private void stopPlaying() {
        if (player != null) {
            try {
                player.stop();
            } catch (RuntimeException ignored) {
                // Not started yet.
            }
            player.release();
            player = null;
        }
        if (state == State.PLAYING) {
            state = State.IDLE;
        }
    }

    private void confirmDelete(Take take) {
        new AlertDialog.Builder(this)
            .setMessage(R.string.record_delete_confirm)
            .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                stopPlaying();
                recordings.delete(productionId, take.key, ok -> {
                    if (!ok && !isDestroyed()) {
                        Toast.makeText(this, R.string.record_upload_failed, Toast.LENGTH_LONG).show();
                    }
                });
                render();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
