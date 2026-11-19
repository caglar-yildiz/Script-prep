package com.scriptprep.app;

import static com.scriptprep.app.Ui.ACCENT;
import static com.scriptprep.app.Ui.TEXT;
import static com.scriptprep.app.Ui.TEXT_SECONDARY;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Premium: records a self-tape while a teleprompter shows the lines and reads the partners. */
public final class VideoActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String TAG = "ScriptPrep";
    private static final int PERMISSIONS = 7;
    private static final int TAIL_MILLIS = 2000;
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_ROLES = "roles";
    private static final String EXTRA_TEXTS = "texts";
    private static final String EXTRA_ROLE = "role";
    private static final String EXTRA_HAS_ROLES = "has_roles";
    private static final String EXTRA_CAST = "cast";
    private static final String EXTRA_VOICE = "voice";
    private static final String EXTRA_AI_VOICE = "ai_voice";
    private static final String EXTRA_LANGUAGE = "language";
    private static final String EXTRA_SPEED = "speed";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui;
    private String[] roles;
    private String[] texts;
    private String myRole;
    private boolean hasRoles;
    private Map<String, VoiceCast.Part> cast;
    private String defaultVoiceName;
    private ScriptLanguage language;
    private float speed = 1f;
    private NeuralVoice neural;

    private FrameLayout previewFrame;
    private TextureView preview;
    private TextView roleView;
    private TextView lineView;
    private TextView nextView;
    private TextView timerView;
    private TextView countdownView;
    private TextView hintView;
    private FrameLayout recordButton;
    private View recordDot;
    private FrameLayout flipButton;
    private FrameLayout audioButton;
    private LinearLayout advanceButton;
    private LinearLayout permissionCard;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private MediaRecorder recorder;
    private Surface previewSurface;
    private Size videoSize;
    private Size previewSize;
    private int sensorOrientation = 90;
    private boolean front = true;
    private boolean recording;
    private boolean counting;
    private boolean askedPermissions;
    private long startedAt;

    private Uri outputUri;
    private File outputFile;
    private ParcelFileDescriptor outputDescriptor;

    private TextToSpeech speech;
    private boolean speechReady;
    private final List<Voice> voices = new ArrayList<>();
    private Voice defaultVoice;
    private boolean partnerAudio = true;
    private String activeUtterance;
    private int utteranceNumber;
    private int position;

    static Intent intent(Context context, String title, Scene scene, String role, boolean hasRoles,
                         ScriptLanguage language,
                         Map<String, VoiceCast.Part> cast, String defaultVoice, boolean aiVoice,
                         float speed) {
        String[] lineRoles = new String[scene.lines.size()];
        String[] lineTexts = new String[scene.lines.size()];
        for (int i = 0; i < lineRoles.length; i++) {
            lineRoles[i] = scene.lines.get(i).role;
            lineTexts[i] = scene.lines.get(i).text;
        }
        return new Intent(context, VideoActivity.class)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_ROLES, lineRoles)
            .putExtra(EXTRA_TEXTS, lineTexts)
            .putExtra(EXTRA_ROLE, role)
            .putExtra(EXTRA_HAS_ROLES, hasRoles)
            .putExtra(EXTRA_CAST, cast == null ? "" : VoiceCast.toJson(cast))
            .putExtra(EXTRA_VOICE, defaultVoice)
            .putExtra(EXTRA_AI_VOICE, aiVoice)
            .putExtra(EXTRA_LANGUAGE, language.code)
            .putExtra(EXTRA_SPEED, speed);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.wrapLegacyContext(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        Intent intent = getIntent();
        roles = intent.getStringArrayExtra(EXTRA_ROLES);
        texts = intent.getStringArrayExtra(EXTRA_TEXTS);
        if (roles == null || texts == null || roles.length == 0 || roles.length != texts.length) {
            finish();
            return;
        }
        myRole = intent.getStringExtra(EXTRA_ROLE);
        hasRoles = intent.getBooleanExtra(EXTRA_HAS_ROLES, true);
        cast = VoiceCast.fromJson(intent.getStringExtra(EXTRA_CAST));
        defaultVoiceName = intent.getStringExtra(EXTRA_VOICE);
        language = ScriptLanguage.fromCode(intent.getStringExtra(EXTRA_LANGUAGE));
        speed = intent.getFloatExtra(EXTRA_SPEED, 1f);
        if (intent.getBooleanExtra(EXTRA_AI_VOICE, false)) {
            neural = NeuralVoice.get(this);
            neural.load();
        }
        if (state != null) {
            front = state.getBoolean("front", true);
            partnerAudio = state.getBoolean("partner_audio", true);
            askedPermissions = state.getBoolean("asked_permissions");
        }
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        drawBehindSystemBars(window);
        buildLayout(intent.getStringExtra(EXTRA_TITLE));
        showLine();
        speech = new TextToSpeech(this, status -> main.post(() -> prepareSpeech(status)));
    }

    @SuppressWarnings("deprecation")
    private void drawBehindSystemBars(Window window) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("front", front);
        state.putBoolean("partner_audio", partnerAudio);
        state.putBoolean("asked_permissions", askedPermissions);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startCameraThread();
        if (hasPermissions()) {
            permissionCard.setVisibility(View.GONE);
            if (preview.isAvailable()) {
                openCamera();
            }
        } else if (!askedPermissions) {
            askedPermissions = true;
            requestPermissions(requiredPermissions(), PERMISSIONS);
        } else {
            permissionCard.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onPause() {
        counting = false;
        main.removeCallbacksAndMessages(null);
        if (recording) {
            stopRecording(false);
        }
        stopSpeech();
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (speech != null) {
            speech.shutdown();
            speech = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMISSIONS) {
            return;
        }
        if (hasPermissions()) {
            permissionCard.setVisibility(View.GONE);
            if (preview.isAvailable()) {
                openCamera();
            }
        } else {
            permissionCard.setVisibility(View.VISIBLE);
        }
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 29) {
            return new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        }
        return new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE};
    }

    private boolean hasPermissions() {
        for (String permission : requiredPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- Layout

    private void buildLayout(String title) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewFrame = new FrameLayout(this);
        root.addView(previewFrame, new FrameLayout.LayoutParams(-1, -1));
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);
        previewFrame.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        // Teleprompter, close to the front camera so the eyes stay near the lens.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(24));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {0xCC000000, 0x00000000}));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(ui.iconButton(R.drawable.ic_close, getString(R.string.action_close), Color.WHITE, 0x33000000,
            48, view -> finish()));
        TextView heading = ui.text(title == null ? "" : title, Ui.Type.SUBTITLE, Color.WHITE);
        heading.setSingleLine(true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, -2, 1);
        headingParams.leftMargin = ui.dp(8);
        bar.addView(heading, headingParams);
        timerView = ui.chip("", Color.WHITE, 0xCCD93A3A);
        timerView.setVisibility(View.GONE);
        bar.addView(timerView);
        top.addView(bar, Ui.matchWidth());

        LinearLayout prompter = new LinearLayout(this);
        prompter.setOrientation(LinearLayout.VERTICAL);
        prompter.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(16));
        prompter.setBackground(ui.shape(0xB3000000, 20));
        prompter.setOnClickListener(view -> {
            if (recording) {
                advance();
            }
        });
        roleView = ui.text("", Ui.Type.OVERLINE, ACCENT);
        prompter.addView(roleView);
        lineView = ui.serif("", 23, Color.WHITE);
        lineView.setMaxLines(7);
        prompter.addView(lineView, ui.spaced(6));
        nextView = ui.text("", Ui.Type.BODY, 0xB3FFFFFF);
        nextView.setMaxLines(2);
        prompter.addView(nextView, ui.spaced(10));
        top.addView(prompter, ui.spaced(8));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        countdownView = ui.text("", Ui.Type.DISPLAY, Color.WHITE);
        countdownView.setTextSize(96);
        countdownView.setShadowLayer(ui.dp(8), 0, 0, Color.BLACK);
        countdownView.setVisibility(View.GONE);
        root.addView(countdownView, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(ui.dp(20), ui.dp(32), ui.dp(20), ui.dp(20));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[] {0xCC000000, 0x00000000}));
        advanceButton = ui.button(getString(R.string.video_next_line), R.drawable.ic_next, Ui.Kind.PRIMARY,
            view -> advance());
        advanceButton.setVisibility(View.GONE);
        bottom.addView(advanceButton, new LinearLayout.LayoutParams(-2, -2));
        hintView = ui.text(getString(R.string.video_hint), Ui.Type.BODY, TEXT_SECONDARY);
        hintView.setGravity(Gravity.CENTER);
        bottom.addView(hintView, ui.spaced(12));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        flipButton = ui.iconButton(R.drawable.ic_switch_camera, getString(R.string.video_switch_camera),
            Color.WHITE, 0x40FFFFFF, 52, view -> switchCamera());
        controls.addView(flipButton);
        recordButton = new FrameLayout(this);
        recordButton.setBackground(ui.ripple(outlinedCircle(), 0x33FFFFFF));
        recordButton.setContentDescription(getString(R.string.video_record));
        recordButton.setOnClickListener(view -> toggleRecording());
        recordDot = new View(this);
        recordDot.setBackground(ui.shape(0xFFE5484D, 999));
        recordButton.addView(recordDot, new FrameLayout.LayoutParams(ui.dp(58), ui.dp(58), Gravity.CENTER));
        LinearLayout.LayoutParams recordParams = new LinearLayout.LayoutParams(ui.dp(76), ui.dp(76));
        recordParams.leftMargin = ui.dp(36);
        recordParams.rightMargin = ui.dp(36);
        controls.addView(recordButton, recordParams);
        audioButton = ui.iconButton(R.drawable.ic_volume, "", Color.WHITE, 0x40FFFFFF, 52,
            view -> togglePartnerAudio());
        controls.addView(audioButton);
        bottom.addView(controls, ui.spaced(16));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        updateAudioButton();
        if (!hasRoles) {
            audioButton.setVisibility(View.INVISIBLE);
        }

        permissionCard = ui.card(Ui.SURFACE, null);
        permissionCard.setGravity(Gravity.CENTER_HORIZONTAL);
        permissionCard.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(16));
        TextView permissionText = ui.text(getString(R.string.video_permission), Ui.Type.BODY_LARGE, TEXT);
        permissionText.setGravity(Gravity.CENTER);
        permissionCard.addView(permissionText, Ui.matchWidth());
        permissionCard.addView(ui.button(getString(R.string.video_allow), 0, Ui.Kind.PRIMARY,
            view -> openPermissionSettings()), ui.wrapSpaced(16));
        permissionCard.setVisibility(View.GONE);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        cardParams.setMargins(ui.dp(24), 0, ui.dp(24), 0);
        root.addView(permissionCard, cardParams);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int[] bars = systemInsets(insets);
            top.setPadding(ui.dp(12) + bars[0], ui.dp(8) + bars[1], ui.dp(12) + bars[2], ui.dp(24));
            bottom.setPadding(ui.dp(20), ui.dp(32), ui.dp(20), ui.dp(20) + bars[3]);
            return insets;
        });
        setContentView(root);
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

    private GradientDrawable outlinedCircle() {
        GradientDrawable ring = ui.shape(0x00000000, 999);
        ring.setStroke(ui.dp(4), Color.WHITE);
        return ring;
    }

    private void openPermissionSettings() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            || shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(requiredPermissions(), PERMISSIONS);
            return;
        }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)));
        } catch (ActivityNotFoundException error) {
            requestPermissions(requiredPermissions(), PERMISSIONS);
        }
    }

    private boolean isMine(int index) {
        return !hasRoles || roles[index].equals(myRole);
    }

    private void showLine() {
        if (position >= texts.length) {
            roleView.setText(R.string.video_finished_role);
            lineView.setText(R.string.video_finished_line);
            nextView.setText("");
            return;
        }
        boolean mine = isMine(position);
        roleView.setText(hasRoles ? (mine ? getString(R.string.your_turn, roles[position]) : roles[position])
            : getString(R.string.solo_your_turn));
        roleView.setTextColor(mine ? ACCENT : 0xFFC9B6F2);
        lineView.setText(texts[position]);
        lineView.setTypeface(Typeface.SERIF, mine ? Typeface.BOLD : Typeface.NORMAL);
        lineView.setTextColor(mine ? Color.WHITE : 0xE6FFFFFF);
        if (position + 1 < texts.length) {
            String next = hasRoles ? roles[position + 1] + " · " + texts[position + 1] : texts[position + 1];
            nextView.setText(getString(R.string.video_up_next, next));
        } else {
            nextView.setText("");
        }
    }

    private void updateAudioButton() {
        audioButton.removeAllViews();
        audioButton.addView(ui.icon(partnerAudio ? R.drawable.ic_volume : R.drawable.ic_volume_off, Color.WHITE, 24),
            new FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER));
        audioButton.setContentDescription(getString(partnerAudio ? R.string.video_partner_on
            : R.string.video_partner_off));
    }

    private void togglePartnerAudio() {
        partnerAudio = !partnerAudio;
        updateAudioButton();
        Toast.makeText(this, partnerAudio ? R.string.video_partner_on : R.string.video_partner_off,
            Toast.LENGTH_SHORT).show();
        if (!partnerAudio) {
            stopSpeech();
        } else if (recording) {
            readIfPartner();
        }
    }

    // ---------------------------------------------------------------- Teleprompter flow

    private void prepareSpeech(int status) {
        if (speech == null) {
            return;
        }
        if (status != TextToSpeech.SUCCESS || speech.setLanguage(language.locale) < TextToSpeech.LANG_AVAILABLE) {
            return;
        }
        speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {}

            @Override
            public void onDone(String id) {
                main.post(() -> {
                    if (id.equals(activeUtterance)) {
                        activeUtterance = null;
                        advance();
                    }
                });
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onError(String id) {
                main.post(() -> {
                    if (id.equals(activeUtterance)) {
                        activeUtterance = null;
                    }
                });
            }
        });
        if (speech.getVoices() != null) {
            for (Voice voice : speech.getVoices()) {
                if (voice.getLocale() != null && language.code.equals(voice.getLocale().getLanguage())) {
                    voices.add(voice);
                    if (voice.getName().equals(defaultVoiceName)) {
                        defaultVoice = voice;
                    }
                }
            }
        }
        speechReady = true;
    }

    private void readIfPartner() {
        if (!recording || position >= texts.length || isMine(position) || !partnerAudio) {
            return;
        }
        if (neural != null && neural.isReady()) {
            VoiceCast.Part part = cast.get(roles[position]);
            String id = "video-" + ++utteranceNumber;
            activeUtterance = id;
            neural.speak(texts[position], language.code, part == null ? 0 : part.speaker,
                (part == null ? 1f : part.pace.rate) * speed, 1f,
                new NeuralVoice.Playback() {
                    @Override
                    public void onDone() {
                        if (id.equals(activeUtterance)) {
                            activeUtterance = null;
                            advance();
                        }
                    }

                    @Override
                    public void onError() {
                        if (id.equals(activeUtterance)) {
                            activeUtterance = null;
                        }
                    }
                });
            prefetchNext();
            return;
        }
        if (!speechReady) {
            return;
        }
        VoiceCast.Part part = cast.get(roles[position]);
        VoiceCast.apply(speech, part, voices, defaultVoice, language.locale);
        speech.setSpeechRate((part == null ? 1f : part.pace.rate) * speed);
        activeUtterance = "video-" + ++utteranceNumber;
        if (speech.speak(texts[position], TextToSpeech.QUEUE_FLUSH, null, activeUtterance) == TextToSpeech.ERROR) {
            activeUtterance = null;
        }
    }

    private void stopSpeech() {
        if (activeUtterance != null) {
            activeUtterance = null;
            if (speech != null) {
                speech.stop();
            }
            if (neural != null) {
                neural.stop();
            }
        }
    }

    /** Prepares the next partner line with the AI voice so it starts without a pause. */
    private void prefetchNext() {
        if (neural == null || !neural.isReady()) {
            return;
        }
        for (int i = position + 1; i < texts.length; i++) {
            if (!isMine(i)) {
                VoiceCast.Part part = cast.get(roles[i]);
                neural.prefetch(texts[i], language.code, part == null ? 0 : part.speaker,
                    (part == null ? 1f : part.pace.rate) * speed);
                return;
            }
        }
    }

    private void advance() {
        if (!recording || position >= texts.length) {
            return;
        }
        stopSpeech();
        position++;
        showLine();
        if (position >= texts.length) {
            advanceButton.setVisibility(View.GONE);
            main.postDelayed(() -> {
                if (recording) {
                    stopRecording(true);
                }
            }, TAIL_MILLIS);
        } else {
            readIfPartner();
            prefetchNext();
        }
    }

    private void toggleRecording() {
        if (counting) {
            return;
        }
        if (recording) {
            stopRecording(true);
        } else if (!hasPermissions()) {
            requestPermissions(requiredPermissions(), PERMISSIONS);
        } else if (camera != null) {
            countdown(3);
        }
    }

    private void countdown(int value) {
        counting = value > 0;
        if (value == 0) {
            countdownView.setVisibility(View.GONE);
            startRecording();
            return;
        }
        countdownView.setText(String.valueOf(value));
        countdownView.setVisibility(View.VISIBLE);
        main.postDelayed(() -> countdown(value - 1), 1000);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!recording) {
                return;
            }
            long seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000;
            timerView.setText(String.format(Locale.ROOT, "● %02d:%02d", seconds / 60, seconds % 60));
            main.postDelayed(this, 500);
        }
    };

    private void setRecordingUi(boolean active) {
        recordDot.setBackground(ui.shape(0xFFE5484D, active ? 8 : 999));
        FrameLayout.LayoutParams dot = (FrameLayout.LayoutParams) recordDot.getLayoutParams();
        dot.width = ui.dp(active ? 30 : 58);
        dot.height = ui.dp(active ? 30 : 58);
        recordDot.setLayoutParams(dot);
        recordButton.setContentDescription(getString(active ? R.string.video_stop : R.string.video_record));
        timerView.setVisibility(active ? View.VISIBLE : View.GONE);
        advanceButton.setVisibility(active ? View.VISIBLE : View.GONE);
        hintView.setText(active ? R.string.video_hint_recording : R.string.video_hint);
        flipButton.setEnabled(!active);
        flipButton.setAlpha(active ? 0.4f : 1f);
    }

    // ---------------------------------------------------------------- Camera

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (hasPermissions()) {
            openCamera();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {}

    private void startCameraThread() {
        if (cameraThread == null) {
            cameraThread = new HandlerThread("camera");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }
    }

    private void stopCameraThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private void switchCamera() {
        if (recording) {
            return;
        }
        front = !front;
        closeCamera();
        openCamera();
    }

    private void openCamera() {
        if (camera != null || cameraHandler == null) {
            return;
        }
        CameraManager manager = getSystemService(CameraManager.class);
        try {
            String id = null;
            String fallback = null;
            int wanted = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            for (String candidate : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING);
                if (fallback == null) {
                    fallback = candidate;
                }
                if (facing != null && facing == wanted) {
                    id = candidate;
                    break;
                }
            }
            if (id == null) {
                id = fallback;
            }
            if (id == null) {
                failCamera();
                return;
            }
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (map == null) {
                failCamera();
                return;
            }
            sensorOrientation = orientation == null ? 90 : orientation;
            videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
            previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture.class), videoSize);
            boolean mirrored = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            layoutPreview(mirrored);
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    main.post(() -> {
                        if (isFinishing() || isDestroyed() || cameraHandler == null) {
                            device.close();
                            return;
                        }
                        camera = device;
                        startPreview();
                    });
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    device.close();
                    main.post(() -> camera = null);
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    device.close();
                    main.post(() -> {
                        camera = null;
                        failCamera();
                    });
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException error) {
            Log.w(TAG, "Could not open the camera", error);
            failCamera();
        }
    }

    private void failCamera() {
        Toast.makeText(this, R.string.video_camera_failed, Toast.LENGTH_LONG).show();
    }

    private static Size chooseVideoSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            return new Size(1280, 720);
        }
        for (Size preferred : new Size[] {new Size(1920, 1080), new Size(1280, 720)}) {
            for (Size size : sizes) {
                if (size.equals(preferred)) {
                    return size;
                }
            }
        }
        Size best = null;
        for (Size size : sizes) {
            if (size.getWidth() <= 1920 && size.getWidth() * 9 == size.getHeight() * 16
                && (best == null || size.getWidth() > best.getWidth())) {
                best = size;
            }
        }
        return best != null ? best : sizes[sizes.length - 1];
    }

    private static Size choosePreviewSize(Size[] sizes, Size video) {
        if (sizes == null || sizes.length == 0) {
            return video;
        }
        Size best = null;
        for (Size size : sizes) {
            if (size.getWidth() <= 1920 && (long) size.getWidth() * video.getHeight()
                == (long) size.getHeight() * video.getWidth()
                && (best == null || size.getWidth() > best.getWidth())) {
                best = size;
            }
        }
        return best != null ? best : video;
    }

    /** Fills the screen with the portrait preview, cropping instead of stretching. */
    private void layoutPreview(boolean mirrored) {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int longSide = Math.max(previewSize.getWidth(), previewSize.getHeight());
        int shortSide = Math.min(previewSize.getWidth(), previewSize.getHeight());
        int width = screenWidth;
        int height = Math.round(screenWidth * (longSide / (float) shortSide));
        if (height < screenHeight) {
            height = screenHeight;
            width = Math.round(screenHeight * (shortSide / (float) longSide));
        }
        preview.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
        preview.setScaleX(mirrored ? -1f : 1f);
    }

    private Surface previewSurface() {
        SurfaceTexture texture = preview.getSurfaceTexture();
        if (texture == null) {
            return null;
        }
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        if (previewSurface != null) {
            previewSurface.release();
        }
        previewSurface = new Surface(texture);
        return previewSurface;
    }

    @SuppressWarnings("deprecation")
    private void startPreview() {
        Surface surface = previewSurface();
        if (camera == null || surface == null) {
            return;
        }
        closeSession();
        try {
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(surface);
            camera.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession configured) {
                    if (camera == null) {
                        configured.close();
                        return;
                    }
                    session = configured;
                    try {
                        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        configured.setRepeatingRequest(request.build(), null, cameraHandler);
                    } catch (CameraAccessException | IllegalStateException error) {
                        Log.w(TAG, "Preview failed", error);
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession failed) {
                    main.post(VideoActivity.this::failCamera);
                }
            }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException error) {
            Log.w(TAG, "Preview failed", error);
            failCamera();
        }
    }

    @SuppressWarnings("deprecation")
    private void startRecording() {
        if (camera == null) {
            return;
        }
        try {
            closeSession();
            prepareRecorder();
            Surface surface = previewSurface();
            Surface recorderSurface = recorder.getSurface();
            if (surface == null) {
                throw new IOException("Preview surface unavailable");
            }
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            request.addTarget(surface);
            request.addTarget(recorderSurface);
            camera.createCaptureSession(Arrays.asList(surface, recorderSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession configured) {
                        session = configured;
                        try {
                            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                            configured.setRepeatingRequest(request.build(), null, cameraHandler);
                        } catch (CameraAccessException | IllegalStateException error) {
                            main.post(() -> abortRecording(error));
                            return;
                        }
                        main.post(() -> beginRecording());
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession failed) {
                        main.post(() -> abortRecording(null));
                    }
                }, cameraHandler);
        } catch (CameraAccessException | IOException | RuntimeException error) {
            abortRecording(error);
        }
    }

    private void beginRecording() {
        if (recorder == null) {
            return;
        }
        try {
            recorder.start();
        } catch (RuntimeException error) {
            abortRecording(error);
            return;
        }
        recording = true;
        startedAt = SystemClock.elapsedRealtime();
        position = 0;
        showLine();
        setRecordingUi(true);
        main.post(tick);
        readIfPartner();
        prefetchNext();
    }

    private void abortRecording(Exception error) {
        Log.w(TAG, "Recording failed", error);
        releaseRecorder();
        discardOutput();
        recording = false;
        setRecordingUi(false);
        Toast.makeText(this, R.string.video_record_failed, Toast.LENGTH_LONG).show();
        startPreview();
    }

    @SuppressWarnings("deprecation")
    private void prepareRecorder() throws IOException {
        recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        prepareOutput();
        recorder.setVideoEncodingBitRate(videoSize.getWidth() >= 1920 ? 12_000_000 : 6_000_000);
        recorder.setVideoFrameRate(30);
        recorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioEncodingBitRate(128_000);
        recorder.setAudioSamplingRate(44_100);
        // The activity is portrait only, so the sensor orientation turns the frames upright.
        recorder.setOrientationHint(sensorOrientation);
        recorder.prepare();
    }

    @SuppressWarnings("deprecation")
    private void prepareOutput() throws IOException {
        String name = "memo-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Memo Everything");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            ContentResolver resolver = getContentResolver();
            outputUri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (outputUri == null) {
                throw new IOException("Could not create the video entry");
            }
            outputDescriptor = resolver.openFileDescriptor(outputUri, "rw");
            if (outputDescriptor == null) {
                throw new IOException("Could not open the video entry");
            }
            recorder.setOutputFile(outputDescriptor.getFileDescriptor());
        } else {
            File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "Memo Everything");
            if (!folder.isDirectory() && !folder.mkdirs()) {
                throw new IOException("Could not create the video folder");
            }
            outputFile = new File(folder, name);
            recorder.setOutputFile(outputFile.getAbsolutePath());
        }
    }

    private void stopRecording(boolean showResult) {
        recording = false;
        main.removeCallbacks(tick);
        stopSpeech();
        boolean saved = true;
        try {
            recorder.stop();
        } catch (RuntimeException error) {
            // Stopping right after starting leaves no valid video.
            saved = false;
        }
        releaseRecorder();
        setRecordingUi(false);
        Uri result = saved ? publishOutput() : null;
        if (!saved) {
            discardOutput();
            Toast.makeText(this, R.string.video_record_failed, Toast.LENGTH_LONG).show();
        }
        position = 0;
        showLine();
        if (!isFinishing() && camera != null) {
            startPreview();
        }
        if (saved && showResult && !isFinishing()) {
            showSaved(result);
        }
    }

    private Uri publishOutput() {
        closeDescriptor();
        if (outputUri != null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(outputUri, values, null, null);
            Uri uri = outputUri;
            outputUri = null;
            return uri;
        }
        if (outputFile != null) {
            File file = outputFile;
            outputFile = null;
            Uri uri = Uri.fromFile(file);
            MediaScannerConnection.scanFile(getApplicationContext(), new String[] {file.getAbsolutePath()},
                new String[] {"video/mp4"}, (path, scanned) -> main.post(() -> lastScanned = scanned));
            return uri;
        }
        return null;
    }

    private Uri lastScanned;

    private void showSaved(Uri result) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.video_saved_title)
            .setMessage(R.string.video_saved_message)
            .setPositiveButton(R.string.video_share, (dialog, which) -> share(result))
            .setNeutralButton(R.string.video_retake, null)
            .setNegativeButton(R.string.action_close, (dialog, which) -> finish())
            .show();
    }

    private void share(Uri result) {
        Uri uri = result;
        if (uri != null && "file".equals(uri.getScheme())) {
            uri = lastScanned;
        }
        if (uri == null) {
            Toast.makeText(this, R.string.video_saved_message, Toast.LENGTH_LONG).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.video_share)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.video_saved_message, Toast.LENGTH_LONG).show();
        }
    }

    private void discardOutput() {
        closeDescriptor();
        if (outputUri != null) {
            getContentResolver().delete(outputUri, null, null);
            outputUri = null;
        }
        if (outputFile != null) {
            if (!outputFile.delete()) {
                Log.w(TAG, "Could not delete an unfinished video");
            }
            outputFile = null;
        }
    }

    private void closeDescriptor() {
        if (outputDescriptor != null) {
            try {
                outputDescriptor.close();
            } catch (IOException ignored) {
                // Nothing more to release.
            }
            outputDescriptor = null;
        }
    }

    private void releaseRecorder() {
        if (recorder != null) {
            recorder.reset();
            recorder.release();
            recorder = null;
        }
    }

    private void closeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private void closeCamera() {
        closeSession();
        if (camera != null) {
            camera.close();
            camera = null;
        }
        releaseRecorder();
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }
}
