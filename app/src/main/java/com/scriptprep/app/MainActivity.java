package com.scriptprep.app;

import static com.scriptprep.app.Ui.ACCENT;
import static com.scriptprep.app.Ui.ACCENT_CONTAINER;
import static com.scriptprep.app.Ui.BACKGROUND;
import static com.scriptprep.app.Ui.ON_ACCENT_CONTAINER;
import static com.scriptprep.app.Ui.OUTLINE_SOFT;
import static com.scriptprep.app.Ui.SURFACE;
import static com.scriptprep.app.Ui.SURFACE_HIGH;
import static com.scriptprep.app.Ui.SURFACE_HIGHEST;
import static com.scriptprep.app.Ui.TEAL;
import static com.scriptprep.app.Ui.TEAL_CONTAINER;
import static com.scriptprep.app.Ui.TEXT;
import static com.scriptprep.app.Ui.TEXT_SECONDARY;
import static com.scriptprep.app.Ui.TEXT_TERTIARY;
import static com.scriptprep.app.Ui.VIOLET;
import static com.scriptprep.app.Ui.VIOLET_CONTAINER;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.scriptprep.app.RehearsalOptions.Mode;
import com.scriptprep.app.RehearsalOptions.Visibility;
import com.scriptprep.app.Ui.Kind;
import com.scriptprep.app.Ui.Type;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");
    private static final int MAX_SCRIPT_LENGTH = 150_000;
    private static final int MAX_TITLE_LENGTH = 80;
    private static final int IMPORT_TEXT = 1;
    private static final int IMPORT_PDF = 2;
    private static final int SNACKBAR_DURATION = 6000;
    private static final String VOICE_PREFERENCE = "tts_voice";
    private static final String HANDS_FREE_PREFERENCE = "hands_free";
    private static final String TURN_CUE_PREFERENCE = "turn_cue";
    private static final String AI_VOICE_PREFERENCE = "ai_voice";
    private static final int TURN_CUE_MILLIS = 180;
    private static final int REQUEST_MICROPHONE = 11;
    private static final double LINE_SAID = 0.6;
    private static final double LINE_CLEAN = 0.8;
    private static final int LISTEN_RETRIES = 6;
    private enum Screen { HOME, CATEGORIES, SOURCE, EDITOR, SECTIONS, SETUP, READER, REHEARSAL, VOICES }
    private enum Tab { SCRIPTS, PREMIUM, SETTINGS }
    private enum SpeechState { LOADING, READY, UNAVAILABLE }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService imports = Executors.newSingleThreadExecutor();
    private final List<Voice> turkishVoices = new ArrayList<>();
    /** Offline voices in settings order followed by online voices; Premium character voices. */
    private final List<Voice> castVoices = new ArrayList<>();
    private final Set<Integer> revealedLines = new TreeSet<>();
    private final Set<Integer> retriedLines = new TreeSet<>();
    private final Set<Integer> missedLines = new TreeSet<>();
    private ArrayList<Integer> reviewBase;
    private HandsFree handsFree;
    private ToneGenerator turnCue;
    private NeuralVoice neural;
    private boolean listening;
    private boolean listeningPaused;
    private String heardSoFar = "";
    private int listenAttempts;
    private TextView heardView;
    private final Map<String, Scene> parsedCache = new HashMap<>();
    private SharedPreferences preferences;
    private ScriptLibrary library;
    private Ui ui;

    private LinearLayout header;
    private LinearLayout content;
    private LinearLayout footer;
    private FrameLayout overlay;
    private EditText titleField;
    private EditText editor;
    private View snackbarView;

    private TextToSpeech speech;
    private SpeechState speechState = SpeechState.LOADING;
    private String activeUtterance;
    private int utteranceNumber;
    private boolean autoPlayPending;
    private boolean playbackFailed;
    private boolean visible;
    private boolean importing;
    private String activePreview;

    private Screen screen = Screen.HOME;
    private Tab tab = Tab.SCRIPTS;
    private TextCategory category = TextCategory.ROLE_REHEARSAL;
    private String scriptId;
    private String scriptTitle = "";
    private String script = "";
    private String savedTitle = "";
    private String savedScript = "";
    private Screen editorReturn = Screen.HOME;
    private Scene fullScene;
    private Scene scene;
    private int sectionIndex = -1;
    private String role;
    private int position;
    private Mode mode = Mode.PRACTICE;
    private Visibility lineVisibility = Visibility.FULL;
    private boolean revealLine;
    /** Whether the line being read now uses the AI voice. */
    private boolean aiSpeaking;

    private String snackbarMessage;
    private String snackbarActionLabel;
    private Runnable snackbarAction;
    private final Runnable hideSnackbar = this::dismissSnackbar;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.wrapLegacyContext(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppLanguage.migrateLegacyChoice(this);
        ui = new Ui(this);
        preferences = getSharedPreferences("script_prep", MODE_PRIVATE);
        library = new ScriptLibrary(preferences);
        neural = NeuralVoice.get(this);
        neural.setListener(this::onNeuralVoiceChanged);
        drawBehindSystemBars();
        if (state != null) {
            script = state.getString("script", "");
            scriptTitle = state.getString("title", "");
            savedScript = state.getString("saved_script", script);
            savedTitle = state.getString("saved_title", scriptTitle);
            scriptId = state.getString("script_id");
            editorReturn = Screen.valueOf(state.getString("editor_return", Screen.HOME.name()));
            String restoredScreen = state.getString("screen", Screen.HOME.name());
            screen = "ROLES".equals(restoredScreen) ? Screen.SETUP : Screen.valueOf(restoredScreen);
            tab = Tab.valueOf(state.getString("tab", Tab.SCRIPTS.name()));
            category = TextCategory.fromSavedValue(state.getString("category"));
            fullScene = parseScript(script);
            sectionIndex = state.getInt("section_index", -1);
            scene = fullScene.forSection(sectionIndex);
            reviewBase = state.getIntegerArrayList("review_base");
            if (reviewBase != null) {
                scene = scene.subset(reviewBase);
            }
            restoreSet(revealedLines, state.getIntegerArrayList("revealed_lines"));
            restoreSet(retriedLines, state.getIntegerArrayList("retried_lines"));
            restoreSet(missedLines, state.getIntegerArrayList("missed_lines"));
            role = state.getString("role");
            position = Math.min(state.getInt("position"), scene.lines.size());
            mode = Mode.valueOf(state.getString("mode", Mode.PRACTICE.name()));
            lineVisibility = Visibility.valueOf(state.getString("line_visibility", Visibility.FULL.name()));
            revealLine = state.getBoolean("reveal_line");
            if (screen == Screen.SETUP || screen == Screen.READER || screen == Screen.REHEARSAL
                || screen == Screen.VOICES) {
                ensureRole();
            }
        }
        render();
        speech = new TextToSpeech(this, status -> main.post(() -> prepareSpeech(status)));
    }

    @SuppressWarnings("deprecation")
    private void drawBehindSystemBars() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        if (screen == Screen.REHEARSAL) {
            main.post(this::listenIfNeeded);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        boolean editing = screen == Screen.EDITOR && editor != null;
        state.putString("script", editing ? editor.getText().toString() : script);
        state.putString("title", editing ? titleField.getText().toString() : scriptTitle);
        state.putString("saved_script", savedScript);
        state.putString("saved_title", savedTitle);
        state.putString("script_id", scriptId);
        state.putString("editor_return", editorReturn.name());
        state.putString("category", category.name());
        state.putString("screen", screen.name());
        state.putString("tab", tab.name());
        state.putString("role", role);
        state.putInt("section_index", sectionIndex);
        state.putInt("position", position);
        state.putString("mode", mode.name());
        state.putString("line_visibility", lineVisibility.name());
        state.putBoolean("reveal_line", revealLine);
        state.putIntegerArrayList("review_base", reviewBase);
        state.putIntegerArrayList("revealed_lines", new ArrayList<>(revealedLines));
        state.putIntegerArrayList("retried_lines", new ArrayList<>(retriedLines));
        state.putIntegerArrayList("missed_lines", new ArrayList<>(missedLines));
    }

    private static void restoreSet(Set<Integer> target, ArrayList<Integer> values) {
        target.clear();
        if (values != null) {
            target.addAll(values);
        }
    }

    @Override
    protected void onStop() {
        visible = false;
        cancelSpeech();
        stopListening();
        if (screen == Screen.REHEARSAL) {
            render();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cancelSpeech();
        neural.setListener(null);
        if (isFinishing()) {
            neural.unload();
        }
        if (handsFree != null) {
            handsFree.destroy();
        }
        if (turnCue != null) {
            turnCue.release();
            turnCue = null;
        }
        imports.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (speech != null) {
            speech.shutdown();
            speech = null;
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (screen == Screen.REHEARSAL || screen == Screen.READER || screen == Screen.VOICES) {
            show(Screen.SETUP);
        } else if (screen == Screen.SOURCE) {
            show(Screen.CATEGORIES);
        } else if (screen == Screen.EDITOR) {
            leaveEditor();
        } else if (screen == Screen.SETUP && hasNamedSections()) {
            show(Screen.SECTIONS);
        } else if (screen != Screen.HOME || tab != Tab.SCRIPTS) {
            returnToLibrary();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != IMPORT_TEXT && requestCode != IMPORT_PDF)
            || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        boolean pdf = requestCode == IMPORT_PDF;
        TextCategory selectedCategory = category;
        String narrator = getString(R.string.solo_role);
        importing = true;
        render();
        imports.execute(() -> {
            try {
                ScriptLibrary.Script imported = ScriptImport.read(getApplicationContext(), uri, pdf, MAX_SCRIPT_LENGTH);
                Scene preview = pdf ? selectedCategory.parse(imported.text, narrator) : null;
                main.post(() -> {
                    finishImport();
                    if (!isDestroyed() && screen == Screen.SOURCE && category == selectedCategory) {
                        String title = imported.title.trim();
                        openEditor(null, title.isEmpty() ? getString(R.string.imported_title)
                            : title, imported.text);
                        savedScript = "";
                        savedTitle = "";
                        if (preview != null) {
                            showParsePreview(preview);
                        }
                    }
                });
            } catch (ScriptImport.LengthLimitException error) {
                main.post(() -> failImport(R.string.import_too_long));
            } catch (PdfScriptReader.NoTextException error) {
                main.post(() -> failImport(R.string.pdf_no_text));
            } catch (IOException | RuntimeException | LinkageError error) {
                Log.w("ScriptPrep", "Could not import a script", error);
                main.post(() -> failImport(pdf ? R.string.pdf_import_failed : R.string.import_failed));
            }
        });
    }

    private void finishImport() {
        importing = false;
        if (!isDestroyed() && screen == Screen.SOURCE) {
            render();
        }
    }

    private void failImport(int message) {
        finishImport();
        notifyUser(message);
    }

    // ---------------------------------------------------------------- Speech

    private void prepareSpeech(int status) {
        if (speech == null) {
            return;
        }
        if (status == TextToSpeech.SUCCESS && speech.setLanguage(TURKISH) >= TextToSpeech.LANG_AVAILABLE) {
            int listenerStatus = speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {}

                @Override
                public void onDone(String id) {
                    main.post(() -> finishSpeech(id, false));
                }

                @Override
                public void onError(String id) {
                    main.post(() -> finishSpeech(id, true));
                }
            });
            speechState = listenerStatus == TextToSpeech.SUCCESS
                ? SpeechState.READY : SpeechState.UNAVAILABLE;
            if (speechState == SpeechState.READY) {
                loadTurkishVoices();
            }
        } else {
            speechState = SpeechState.UNAVAILABLE;
        }
        boolean shouldPlay = autoPlayPending && visible;
        autoPlayPending = false;
        if (screen == Screen.REHEARSAL) {
            if (shouldPlay && speechState == SpeechState.READY) {
                playCurrent();
            } else {
                render();
            }
        } else if (screen == Screen.HOME && tab == Tab.SETTINGS) {
            render();
        }
    }

    private void finishSpeech(String id, boolean failed) {
        if (id.equals(activePreview)) {
            activePreview = null;
            if (failed) {
                notifyUser(R.string.speech_failed);
            }
            return;
        }
        if (!id.equals(activeUtterance)) {
            return;
        }
        activeUtterance = null;
        if (failed) {
            playbackFailed = true;
            render();
        } else {
            moveTo(position + 1, true);
        }
    }

    private void cancelSpeech() {
        autoPlayPending = false;
        if (activeUtterance != null || activePreview != null) {
            activeUtterance = null;
            activePreview = null;
            speech.stop();
            neural.stop();
        }
    }

    private void playCurrent() {
        if (!visible || screen != Screen.REHEARSAL || position >= scene.lines.size()) {
            return;
        }
        Scene.Line line = scene.lines.get(position);
        if (!RehearsalOptions.shouldSpeak(mode, role, line.role)) {
            return;
        }
        if (useAiVoice() && neural.state() != NeuralVoice.State.FAILED) {
            playWithAiVoice(line);
            return;
        }
        if (speechState == SpeechState.LOADING) {
            autoPlayPending = true;
        } else if (speechState == SpeechState.READY) {
            cancelSpeech();
            stopListening();
            playbackFailed = false;
            aiSpeaking = false;
            VoiceCast.apply(speech, premium() ? currentCast().get(line.role) : null, castVoices,
                defaultVoice(), TURKISH);
            activeUtterance = "line-" + ++utteranceNumber;
            if (speech.speak(line.text, TextToSpeech.QUEUE_FLUSH, null, activeUtterance)
                == TextToSpeech.ERROR) {
                activeUtterance = null;
                playbackFailed = true;
            }
        }
        render();
    }

    private void moveTo(int target, boolean autoPlay) {
        cancelSpeech();
        stopListening();
        position = target;
        revealLine = false;
        playbackFailed = false;
        if (autoPlay && position < scene.lines.size()
            && RehearsalOptions.shouldSpeak(mode, role, scene.lines.get(position).role)) {
            playCurrent();
        } else {
            render();
            prefetchAhead();
            if (cueTurn()) {
                // Let the beep finish before the microphone starts listening.
                main.postDelayed(this::listenIfNeeded, TURN_CUE_MILLIS + 150);
            } else {
                listenIfNeeded();
            }
        }
    }

    /** Beeps when the rehearsal reaches one of the actor's own lines. */
    private boolean cueTurn() {
        if (mode != Mode.PRACTICE || !visible || screen != Screen.REHEARSAL || !isMyTurn()
            || !preferences.getBoolean(TURN_CUE_PREFERENCE, true)) {
            return false;
        }
        try {
            if (turnCue == null) {
                turnCue = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
            }
            turnCue.startTone(ToneGenerator.TONE_PROP_BEEP, TURN_CUE_MILLIS);
            return true;
        } catch (RuntimeException error) {
            // Some devices refuse to create a tone generator; the rehearsal goes on silently.
            turnCue = null;
            return false;
        }
    }

    // ---------------------------------------------------------------- Frame

    private void show(Screen target) {
        cancelSpeech();
        stopListening();
        if (target != Screen.REHEARSAL && reviewBase != null) {
            reviewBase = null;
            scene = fullScene.forSection(sectionIndex);
        }
        screen = target;
        render();
    }

    private void returnToLibrary() {
        tab = Tab.SCRIPTS;
        show(Screen.HOME);
    }

    private void render() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        column.addView(header, Ui.matchWidth());

        FrameLayout body = new FrameLayout(this);
        column.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(28));
        content.setFocusableInTouchMode(true);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        overlay = new FrameLayout(this);
        overlay.setClipChildren(false);
        body.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        column.addView(footer, Ui.matchWidth());

        titleField = null;
        editor = null;
        snackbarView = null;
        switch (screen) {
            case HOME -> {
                switch (tab) {
                    case SCRIPTS -> renderLibrary();
                    case PREMIUM -> renderPremium();
                    case SETTINGS -> renderSettings();
                }
                bottomNavigation();
            }
            case CATEGORIES -> renderCategories();
            case SOURCE -> renderSource();
            case EDITOR -> renderEditor();
            case SECTIONS -> renderSections();
            case SETUP -> renderSetup();
            case READER -> renderReader();
            case REHEARSAL -> renderRehearsal();
            case VOICES -> renderVoices();
        }
        if (snackbarMessage != null) {
            addSnackbar();
        }

        int footerLeft = footer.getPaddingLeft();
        int footerTop = footer.getPaddingTop();
        int footerRight = footer.getPaddingRight();
        int footerBottom = footer.getPaddingBottom();
        boolean hasFooter = footer.getChildCount() > 0;
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int[] bars = systemInsets(insets);
            column.setPadding(bars[0], bars[1], bars[2], hasFooter ? 0 : bars[3]);
            footer.setPadding(footerLeft, footerTop, footerRight, footerBottom + (hasFooter ? bars[3] : 0));
            return insets;
        });
        setContentView(root);
    }

    @SuppressWarnings("deprecation")
    private static int[] systemInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets value = insets.getInsets(WindowInsets.Type.systemBars()
                | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
            return new int[] {value.left, value.top, value.right, value.bottom};
        }
        return new int[] {insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
            insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()};
    }

    /** A top app bar with a navigation icon, an optional title and trailing actions. */
    private LinearLayout topBar(int navigationIcon, String title, Runnable navigate) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
        bar.setMinimumHeight(ui.dp(60));
        if (navigationIcon != 0) {
            int description = navigationIcon == R.drawable.ic_close ? R.string.action_close : R.string.action_back;
            bar.addView(ui.iconButton(navigationIcon, getString(description), TEXT, 0, 48,
                view -> navigate.run()));
        }
        TextView label = ui.text(title == null ? "" : title, Type.TITLE, TEXT);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.leftMargin = ui.dp(navigationIcon == 0 ? 12 : 4);
        bar.addView(label, labelParams);
        header.addView(bar, Ui.matchWidth());
        return bar;
    }

    private void barAction(LinearLayout bar, int drawable, int description, View.OnClickListener action) {
        bar.addView(ui.iconButton(drawable, getString(description), TEXT_SECONDARY, 0, 48, action));
    }

    private TextView pageTitle(String overline, String title, String subtitle) {
        if (overline != null) {
            content.addView(ui.text(overline, Type.OVERLINE, ACCENT), ui.spaced(12));
        }
        TextView heading = ui.text(title, Type.DISPLAY, TEXT);
        content.addView(heading, ui.spaced(overline == null ? 16 : 6));
        if (subtitle != null) {
            TextView detail = ui.text(subtitle, Type.BODY_LARGE, TEXT_SECONDARY);
            content.addView(detail, ui.spaced(8));
        }
        return heading;
    }

    private void sectionLabel(int label) {
        content.addView(ui.text(getString(label), Type.OVERLINE, TEXT_TERTIARY), ui.spaced(28));
    }

    private LinearLayout footerBar() {
        footer.setBackground(topBorder());
        footer.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
        return footer;
    }

    private LayerDrawable topBorder() {
        LayerDrawable surface = new LayerDrawable(new Drawable[] {
            new ColorDrawable(OUTLINE_SOFT), new ColorDrawable(SURFACE)
        });
        surface.setLayerInset(1, 0, ui.dp(1), 0, 0);
        return surface;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private static LinearLayout.LayoutParams weighted(int leftMargin, Ui ui) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.leftMargin = ui.dp(leftMargin);
        return params;
    }

    // ---------------------------------------------------------------- Home tabs

    private void bottomNavigation() {
        footer.setBackground(topBorder());
        footer.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(10));
        LinearLayout items = horizontal();
        addTab(items, Tab.SCRIPTS, R.string.scripts_tab, R.drawable.ic_texts);
        addTab(items, Tab.PREMIUM, R.string.premium_tab, R.drawable.ic_star);
        addTab(items, Tab.SETTINGS, R.string.settings_tab, R.drawable.ic_settings);
        footer.addView(items, Ui.matchWidth());
    }

    private void addTab(LinearLayout navigation, Tab destination, int label, int drawable) {
        boolean selected = tab == destination;
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(0, ui.dp(4), 0, ui.dp(4));
        item.setBackground(ui.ripple(null, 0x1FFFFFFF));
        item.setClickable(true);
        item.setFocusable(true);
        item.setSelected(selected);
        item.setContentDescription(getString(label));
        item.setOnClickListener(view -> {
            if (tab != destination) {
                cancelSpeech();
                tab = destination;
                render();
            }
        });

        FrameLayout pill = new FrameLayout(this);
        if (selected) {
            pill.setBackground(ui.shape(ACCENT_CONTAINER, 999));
        }
        pill.addView(ui.icon(drawable, selected ? ACCENT : TEXT_TERTIARY, 24),
            new FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER));
        item.addView(pill, new LinearLayout.LayoutParams(ui.dp(64), ui.dp(32)));

        TextView text = ui.text(getString(label), Type.CAPTION, selected ? TEXT : TEXT_TERTIARY);
        text.setTypeface(selected ? Ui.MEDIUM : Ui.REGULAR);
        text.setPadding(0, ui.dp(4), 0, 0);
        text.setGravity(Gravity.CENTER);
        text.setSingleLine(true);
        item.addView(text, new LinearLayout.LayoutParams(-2, -2));
        navigation.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void renderLibrary() {
        List<ScriptLibrary.Script> scripts = library.list();
        if (scripts.isEmpty()) {
            renderEmptyLibrary();
            return;
        }

        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.BOTTOM);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(ui.text(getString(R.string.app_name), Type.OVERLINE, ACCENT));
        TextView title = ui.text(getString(R.string.scripts_tab), Type.DISPLAY, TEXT);
        title.setPadding(0, ui.dp(6), 0, 0);
        titles.addView(title);
        TextView count = ui.text(getResources().getQuantityString(R.plurals.library_count,
            scripts.size(), scripts.size()), Type.BODY, TEXT_SECONDARY);
        count.setPadding(0, ui.dp(4), 0, 0);
        titles.addView(count);
        heading.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(heading, ui.spaced(20));

        LinearLayout.LayoutParams first = ui.spaced(20);
        boolean isFirst = true;
        for (ScriptLibrary.Script entry : scripts) {
            content.addView(scriptCard(entry), isFirst ? first : ui.spaced(10));
            isFirst = false;
        }

        LinearLayout sample = ui.card(0x00000000, view -> openSample());
        GradientDrawable dashed = ui.shape(0x00000000, 20);
        dashed.setStroke(ui.dp(1), Ui.OUTLINE, ui.dp(6), ui.dp(4));
        sample.setBackground(ui.ripple(dashed, 0x22FFFFFF));
        sample.setOrientation(LinearLayout.HORIZONTAL);
        sample.setGravity(Gravity.CENTER_VERTICAL);
        sample.addView(ui.iconTile(R.drawable.ic_play, TEXT_SECONDARY, SURFACE_HIGH, 44));
        LinearLayout sampleText = new LinearLayout(this);
        sampleText.setOrientation(LinearLayout.VERTICAL);
        sampleText.addView(ui.text(getString(R.string.sample_button), Type.SUBTITLE, TEXT));
        TextView sampleDetail = ui.text(getString(R.string.sample_details), Type.BODY, TEXT_TERTIARY);
        sampleDetail.setPadding(0, ui.dp(2), 0, 0);
        sampleText.addView(sampleDetail);
        sample.addView(sampleText, weighted(14, ui));
        content.addView(sample, ui.spaced(10));

        // Leave room for the floating create button.
        content.addView(new View(this), new LinearLayout.LayoutParams(-1, ui.dp(72)));

        LinearLayout fab = ui.button(getString(R.string.create_script), R.drawable.ic_add, Kind.PRIMARY,
            view -> show(Screen.CATEGORIES));
        fab.setMinimumHeight(ui.dp(56));
        fab.setElevation(ui.dp(6));
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        fabParams.setMargins(0, 0, ui.dp(20), ui.dp(16));
        overlay.addView(fab, fabParams);
    }

    private LinearLayout scriptCard(ScriptLibrary.Script entry) {
        LinearLayout card = ui.card(SURFACE, view -> openSavedScript(entry));
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(14), ui.dp(4), ui.dp(14));

        int[] colors = categoryColors(entry.category);
        card.addView(ui.iconTile(categoryIcon(entry.category), colors[0], colors[1], 48));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = ui.text(entry.title.isEmpty() ? getString(R.string.imported_title) : entry.title,
            Type.SUBTITLE, TEXT);
        name.setTextSize(17);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        TextView meta = ui.text(scriptSummary(entry), Type.BODY, TEXT_SECONDARY);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, ui.dp(3), 0, 0);
        labels.addView(meta);
        LinearLayout.LayoutParams labelParams = weighted(14, ui);
        card.addView(labels, labelParams);

        FrameLayout more = ui.iconButton(R.drawable.ic_more,
            getString(R.string.text_actions, entry.title), TEXT_SECONDARY, 0, 44, null);
        more.setOnClickListener(view -> showScriptMenu(view, entry));
        card.addView(more);
        card.setOnLongClickListener(view -> {
            showScriptMenu(more, entry);
            return true;
        });
        return card;
    }

    private void showScriptMenu(View anchor, ScriptLibrary.Script entry) {
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.END);
        menu.getMenu().add(0, 1, 0, R.string.action_practice);
        menu.getMenu().add(0, 2, 1, R.string.edit_scene);
        menu.getMenu().add(0, 3, 2, R.string.action_delete);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1 -> openSavedScript(entry);
                case 2 -> {
                    category = entry.category;
                    openEditor(entry.id, entry.title, entry.text);
                }
                case 3 -> confirmDelete(entry);
                default -> {
                    return false;
                }
            }
            return true;
        });
        menu.show();
    }

    private String scriptSummary(ScriptLibrary.Script entry) {
        String key = entry.id + ':' + entry.category.name() + ':' + entry.text.length() + ':' + entry.text.hashCode();
        Scene parsed = parsedCache.get(key);
        if (parsed == null) {
            parsed = entry.category.parse(entry.text, getString(R.string.solo_role));
            if (parsedCache.size() > 32) {
                parsedCache.clear();
            }
            parsedCache.put(key, parsed);
        }
        StringBuilder summary = new StringBuilder(getString(categoryTitle(entry.category)));
        summary.append(" · ");
        if (entry.category.hasRoles()) {
            summary.append(getResources().getQuantityString(R.plurals.line_count,
                parsed.lines.size(), parsed.lines.size()));
            if (!parsed.roles.isEmpty()) {
                summary.append(" · ").append(getResources().getQuantityString(R.plurals.role_count,
                    parsed.roles.size(), parsed.roles.size()));
            }
        } else {
            summary.append(getResources().getQuantityString(R.plurals.part_count,
                parsed.lines.size(), parsed.lines.size()));
        }
        return summary.toString();
    }

    private void openSample() {
        category = TextCategory.ROLE_REHEARSAL;
        openSavedScript(new ScriptLibrary.Script(null, getString(R.string.sample_title),
            getString(R.string.sample_script)));
    }

    private void renderEmptyLibrary() {
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.memo_badge);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(logo, new LinearLayout.LayoutParams(ui.dp(104), ui.dp(104)));

        TextView title = ui.text(getString(R.string.app_name), Type.DISPLAY, TEXT);
        title.setGravity(Gravity.CENTER);
        content.addView(title, ui.spaced(24));

        TextView tagline = ui.serif(getString(R.string.memo_tagline), 19, TEXT_SECONDARY);
        tagline.setTypeface(Typeface.SERIF, Typeface.ITALIC);
        tagline.setGravity(Gravity.CENTER);
        content.addView(tagline, ui.spaced(8));

        LinearLayout steps = ui.card(SURFACE, null);
        steps.setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8));
        onboardingStep(steps, R.drawable.ic_texts, R.string.onboarding_add);
        onboardingStep(steps, R.drawable.ic_person, R.string.onboarding_role);
        onboardingStep(steps, R.drawable.ic_headphones, R.string.onboarding_rehearse);
        content.addView(steps, ui.spaced(32));

        LinearLayout create = ui.button(getString(R.string.create_first_text), R.drawable.ic_add,
            Kind.PRIMARY, view -> show(Screen.CATEGORIES));
        content.addView(create, ui.spaced(28));
        LinearLayout sample = ui.button(getString(R.string.sample_button), R.drawable.ic_play,
            Kind.TEXT, view -> openSample());
        content.addView(sample, ui.spaced(4));
        content.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void onboardingStep(LinearLayout parent, int drawable, int label) {
        LinearLayout row = horizontal();
        row.setPadding(0, ui.dp(10), 0, ui.dp(10));
        row.addView(ui.iconTile(drawable, ACCENT, ACCENT_CONTAINER, 36));
        TextView text = ui.text(getString(label), Type.BODY_LARGE, TEXT);
        row.addView(text, weighted(14, ui));
        parent.addView(row, Ui.matchWidth());
    }

    private void feature(LinearLayout parent, int drawable, int title, int description, boolean divider) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        row.addView(ui.iconTile(drawable, ACCENT, SURFACE_HIGHEST, 40));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(ui.text(getString(title), Type.SUBTITLE, TEXT));
        TextView detail = ui.text(getString(description), Type.BODY, TEXT_SECONDARY);
        detail.setPadding(0, ui.dp(2), 0, 0);
        labels.addView(detail);
        row.addView(labels, weighted(14, ui));
        parent.addView(row, Ui.matchWidth());
        if (divider) {
            parent.addView(ui.divider(70));
        }
    }

    // ---------------------------------------------------------------- Settings

    private void loadTurkishVoices() {
        Set<Voice> voices = speech.getVoices();
        if (voices != null) {
            for (Voice voice : voices) {
                Set<String> features = voice.getFeatures();
                if (TURKISH.getLanguage().equals(voice.getLocale().getLanguage())
                    && !voice.isNetworkConnectionRequired()
                    && (features == null || !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))) {
                    turkishVoices.add(voice);
                }
            }
        }
        Collections.sort(turkishVoices, (first, second) -> first.getName().compareTo(second.getName()));
        castVoices.addAll(turkishVoices);
        List<Voice> online = new ArrayList<>();
        if (voices != null) {
            for (Voice voice : voices) {
                Set<String> features = voice.getFeatures();
                if (TURKISH.getLanguage().equals(voice.getLocale().getLanguage())
                    && voice.isNetworkConnectionRequired()
                    && (features == null || !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))) {
                    online.add(voice);
                }
            }
        }
        Collections.sort(online, (first, second) -> first.getName().compareTo(second.getName()));
        castVoices.addAll(online);
        int index = selectedVoiceIndex();
        if (index > 0 && speech.setVoice(turkishVoices.get(index - 1)) != TextToSpeech.SUCCESS) {
            preferences.edit().remove(VOICE_PREFERENCE).apply();
        }
    }

    private int selectedVoiceIndex() {
        String name = preferences.getString(VOICE_PREFERENCE, "");
        for (int i = 0; i < turkishVoices.size(); i++) {
            if (turkishVoices.get(i).getName().equals(name)) {
                return i + 1;
            }
        }
        return 0;
    }

    private void renderSettings() {
        pageTitle(getString(R.string.app_name), getString(R.string.settings_tab), null);

        sectionLabel(R.string.settings_general);
        LinearLayout general = ui.card(SURFACE, null);
        general.setPadding(0, ui.dp(4), 0, ui.dp(4));
        general.addView(ui.listRow(R.drawable.ic_translate, getString(R.string.language_title),
            getString(AppLanguage.current(this).label), view -> showLanguageOptions()), Ui.matchWidth());
        content.addView(general, ui.spaced(10));

        sectionLabel(R.string.voice_settings);
        TextView explanation = ui.text(getString(R.string.voice_settings_description), Type.BODY, TEXT_SECONDARY);
        content.addView(explanation, ui.spaced(8));
        LinearLayout voice = ui.card(SURFACE, null);
        voice.setPadding(0, ui.dp(4), 0, ui.dp(4));
        if (speechState != SpeechState.READY) {
            voice.addView(ui.listRow(R.drawable.ic_volume, getString(R.string.voice_settings),
                getString(speechState == SpeechState.LOADING ? R.string.speech_loading : R.string.speech_unavailable),
                null), Ui.matchWidth());
        } else {
            int selected = selectedVoiceIndex();
            String current = selected == 0 ? getString(R.string.voice_default)
                : getString(R.string.voice_option, selected);
            if (turkishVoices.isEmpty()) {
                voice.addView(ui.listRow(R.drawable.ic_volume, getString(R.string.voice_choose),
                    current + "\n" + getString(R.string.voice_no_alternatives), null), Ui.matchWidth());
            } else {
                voice.addView(ui.listRow(R.drawable.ic_volume, getString(R.string.voice_choose), current,
                    view -> showVoiceOptions()), Ui.matchWidth());
            }
            voice.addView(ui.divider(70));
            voice.addView(ui.listRow(R.drawable.ic_play, getString(R.string.voice_preview),
                getString(R.string.voice_preview_text), view -> previewVoice()), Ui.matchWidth());
        }
        content.addView(voice, ui.spaced(12));

        renderAiVoiceSettings();

        sectionLabel(R.string.plan_title);
        LinearLayout plan = ui.card(SURFACE, null);
        plan.setPadding(0, ui.dp(4), 0, ui.dp(4));
        plan.addView(ui.listRow(R.drawable.ic_star, getString(R.string.plan_title),
            getString(premium() ? R.string.plan_premium : R.string.plan_free), view -> {
                tab = Tab.PREMIUM;
                render();
            }), Ui.matchWidth());
        content.addView(plan, ui.spaced(10));

        sectionLabel(R.string.settings_about);
        LinearLayout about = ui.card(SURFACE, null);
        about.setPadding(0, ui.dp(4), 0, ui.dp(4));
        about.addView(ui.listRow(R.drawable.ic_info, getString(R.string.settings_version), appVersion(), null),
            Ui.matchWidth());
        about.addView(ui.divider(70));
        about.addView(ui.listRow(R.drawable.ic_book, getString(R.string.licenses_title),
            getString(R.string.licenses_value), view -> showLicenses()), Ui.matchWidth());
        content.addView(about, ui.spaced(10));
    }

    @SuppressWarnings("deprecation")
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "";
        }
    }

    private void previewVoice() {
        cancelSpeech();
        VoiceCast.apply(speech, null, castVoices, defaultVoice(), TURKISH);
        activePreview = "preview-" + ++utteranceNumber;
        if (speech.speak(getString(R.string.voice_preview_text), TextToSpeech.QUEUE_FLUSH,
            null, activePreview) != TextToSpeech.SUCCESS) {
            activePreview = null;
            notifyUser(R.string.speech_failed);
        }
    }

    private void showLanguageOptions() {
        AppLanguage[] available = AppLanguage.values();
        String[] labels = new String[available.length];
        for (int i = 0; i < available.length; i++) {
            labels[i] = getString(available[i].label);
        }
        new AlertDialog.Builder(this).setTitle(R.string.language_title)
            .setSingleChoiceItems(labels, AppLanguage.current(this).ordinal(), (dialog, index) -> {
                dialog.dismiss();
                available[index].apply(this);
            })
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showVoiceOptions() {
        String[] labels = new String[turkishVoices.size() + 1];
        labels[0] = getString(R.string.voice_default);
        for (int i = 0; i < turkishVoices.size(); i++) {
            labels[i + 1] = getString(R.string.voice_option, i + 1);
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.voice_choose)
            .setSingleChoiceItems(labels, selectedVoiceIndex(), (dialog, choice) -> {
                cancelSpeech();
                if (choice == 0) {
                    if (speech.setLanguage(TURKISH) < TextToSpeech.LANG_AVAILABLE) {
                        notifyUser(R.string.voice_change_failed);
                        return;
                    }
                    preferences.edit().remove(VOICE_PREFERENCE).apply();
                } else {
                    Voice voice = turkishVoices.get(choice - 1);
                    if (speech.setVoice(voice) != TextToSpeech.SUCCESS) {
                        notifyUser(R.string.voice_change_failed);
                        return;
                    }
                    preferences.edit().putString(VOICE_PREFERENCE, voice.getName()).apply();
                }
                dialog.dismiss();
                render();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---------------------------------------------------------------- New text flow

    private void renderCategories() {
        topBar(R.drawable.ic_close, null, this::returnToLibrary);
        pageTitle(getString(R.string.step_counter, 1, 2), getString(R.string.choose_text_category), null);
        categoryOption(TextCategory.ROLE_REHEARSAL, R.string.category_role_details, 24);
        categoryOption(TextCategory.SPEECH, R.string.category_speech_details, 12);
        categoryOption(TextCategory.POEM_OR_MONOLOGUE, R.string.category_poem_details, 12);
    }

    private void categoryOption(TextCategory value, int detail, int top) {
        LinearLayout card = ui.card(SURFACE, view -> {
            category = value;
            show(Screen.SOURCE);
        });
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(18), ui.dp(12), ui.dp(18));
        int[] colors = categoryColors(value);
        card.addView(ui.iconTile(categoryIcon(value), colors[0], colors[1], 52));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = ui.text(getString(categoryTitle(value)), Type.TITLE, TEXT);
        labels.addView(title);
        TextView caption = ui.text(getString(detail), Type.BODY, TEXT_SECONDARY);
        caption.setPadding(0, ui.dp(3), 0, 0);
        labels.addView(caption);
        card.addView(labels, weighted(16, ui));
        card.addView(ui.icon(R.drawable.ic_chevron_right, TEXT_TERTIARY, 22));
        content.addView(card, ui.spaced(top));
    }

    private void renderSource() {
        topBar(R.drawable.ic_back, null, () -> show(Screen.CATEGORIES));
        pageTitle(getString(R.string.step_counter, 2, 2), getString(R.string.choose_text_source), null);
        int[] colors = categoryColors(category);
        LinearLayout selected = ui.chip(getString(categoryTitle(category)), categoryIcon(category), colors[0], colors[1]);
        content.addView(selected, ui.wrapSpaced(14));

        if (importing) {
            LinearLayout loading = ui.card(SURFACE, null);
            loading.setOrientation(LinearLayout.HORIZONTAL);
            loading.setGravity(Gravity.CENTER_VERTICAL);
            loading.setPadding(ui.dp(18), ui.dp(20), ui.dp(18), ui.dp(20));
            ProgressBar spinner = new ProgressBar(this);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
            loading.addView(spinner, new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
            TextView label = ui.text(getString(R.string.importing), Type.SUBTITLE, TEXT);
            label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            loading.addView(label, weighted(16, ui));
            content.addView(loading, ui.spaced(24));
            return;
        }

        LinearLayout first = horizontal();
        first.setGravity(Gravity.TOP);
        sourceTile(first, R.drawable.ic_pdf, R.string.create_import_pdf, R.string.source_pdf_details,
            view -> openImportPicker(true));
        sourceTile(first, R.drawable.ic_file, R.string.create_import_txt, R.string.source_txt_details,
            view -> openImportPicker(false));
        content.addView(first, ui.spaced(24));
        LinearLayout second = horizontal();
        second.setGravity(Gravity.TOP);
        sourceTile(second, R.drawable.ic_paste, R.string.create_paste, R.string.source_paste_details,
            view -> pasteFromClipboard());
        sourceTile(second, R.drawable.ic_edit, R.string.create_write, R.string.source_write_details,
            view -> openEditor(null, "", ""));
        content.addView(second, ui.spaced(12));
    }

    private void sourceTile(LinearLayout row, int drawable, int title, int detail, View.OnClickListener action) {
        LinearLayout tile = ui.card(SURFACE, action);
        tile.setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(18));
        tile.setMinimumHeight(ui.dp(148));
        tile.addView(ui.iconTile(drawable, ACCENT, ACCENT_CONTAINER, 44));
        TextView label = ui.text(getString(title), Type.SUBTITLE, TEXT);
        tile.addView(label, ui.spaced(16));
        TextView caption = ui.text(getString(detail), Type.BODY, TEXT_SECONDARY);
        tile.addView(caption, ui.spaced(4));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() > 0) {
            params.leftMargin = ui.dp(12);
        }
        row.addView(tile, params);
    }

    private int categoryTitle(TextCategory selected) {
        return switch (selected) {
            case ROLE_REHEARSAL -> R.string.category_role;
            case SPEECH -> R.string.category_speech;
            case POEM_OR_MONOLOGUE -> R.string.category_poem;
        };
    }

    private int categoryIcon(TextCategory selected) {
        return switch (selected) {
            case ROLE_REHEARSAL -> R.drawable.ic_people;
            case SPEECH -> R.drawable.ic_mic;
            case POEM_OR_MONOLOGUE -> R.drawable.ic_quote;
        };
    }

    private int[] categoryColors(TextCategory selected) {
        return switch (selected) {
            case ROLE_REHEARSAL -> new int[] {ACCENT, ACCENT_CONTAINER};
            case SPEECH -> new int[] {TEAL, TEAL_CONTAINER};
            case POEM_OR_MONOLOGUE -> new int[] {VIOLET, VIOLET_CONTAINER};
        };
    }

    private void pasteFromClipboard() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        CharSequence value = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).getText();
        if (value != null && value.length() > MAX_SCRIPT_LENGTH) {
            notifyUser(R.string.import_too_long);
            return;
        }
        openEditor(null, "", value == null ? "" : value.toString());
        savedScript = "";
        if (value == null || value.length() == 0) {
            notifyUser(R.string.empty_clipboard);
        }
    }

    private Scene parseScript(String value) {
        return category.parse(value, getString(R.string.solo_role));
    }

    @SuppressWarnings("deprecation")
    private void openImportPicker(boolean pdf) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(pdf ? "application/pdf" : "text/plain")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, pdf ? IMPORT_PDF : IMPORT_TEXT);
        } catch (ActivityNotFoundException error) {
            notifyUser(pdf ? R.string.pdf_import_failed : R.string.import_failed);
        }
    }

    private void openSavedScript(ScriptLibrary.Script entry) {
        category = entry.category;
        Scene parsed = parseScript(entry.text);
        if (parsed.roles.isEmpty()) {
            openEditor(entry.id, entry.title, entry.text);
            return;
        }
        scriptId = entry.id;
        scriptTitle = entry.title;
        script = entry.text;
        beginScript(parsed);
    }

    // ---------------------------------------------------------------- Editor

    private void openEditor(String id, String title, String text) {
        scriptId = id;
        scriptTitle = title;
        script = text;
        savedTitle = title;
        savedScript = text;
        editorReturn = Screen.HOME;
        show(Screen.EDITOR);
    }

    /** Edits the open text and comes back to the current screen afterwards. */
    private void editCurrent() {
        savedTitle = scriptTitle;
        savedScript = script;
        editorReturn = screen;
        show(Screen.EDITOR);
    }

    private void renderEditor() {
        LinearLayout bar = topBar(R.drawable.ic_back,
            getString(scriptId == null ? R.string.editor_new_title : R.string.editor_title), this::leaveEditor);
        if (scriptId != null) {
            barAction(bar, R.drawable.ic_delete, R.string.action_delete, view -> deleteCurrent());
        }

        int[] colors = categoryColors(category);
        content.addView(ui.chip(getString(categoryTitle(category)), categoryIcon(category), colors[0], colors[1]),
            ui.wrapSpaced(8));

        content.addView(fieldLabel(R.string.script_title_label, null), ui.spaced(20));
        titleField = field(R.string.script_title_hint, scriptTitle,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, MAX_TITLE_LENGTH);
        titleField.setSingleLine(true);
        content.addView(titleField, ui.spaced(8));

        TextView counter = ui.text("", Type.CAPTION, TEXT_TERTIARY);
        LinearLayout textLabel = fieldLabel(R.string.script_label, counter);
        content.addView(textLabel, ui.spaced(22));
        int hint = category.hasRoles() ? R.string.script_hint : R.string.solo_hint;
        editor = field(hint, script,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, MAX_SCRIPT_LENGTH);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setMinLines(10);
        editor.setTypeface(Typeface.SERIF);
        editor.setLineSpacing(ui.dp(4), 1f);
        content.addView(editor, ui.spaced(8));
        updateCounter(counter, editor.length());
        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable value) {
                updateCounter(counter, value.length());
            }
        });

        LinearLayout tools = horizontal();
        LinearLayout clear = ui.button(getString(R.string.clear_text), R.drawable.ic_close, Kind.TEXT,
            view -> clearEditorText());
        tools.addView(clear);
        tools.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        LinearLayout preview = ui.button(getString(category.hasRoles() ? R.string.parse_preview_short
            : R.string.solo_preview_short), R.drawable.ic_list, Kind.TEXT,
            view -> showParsePreview(parseScript(editor.getText().toString())));
        tools.addView(preview);
        content.addView(tools, ui.spaced(4));

        LinearLayout tip = ui.card(SURFACE, null);
        tip.setOrientation(LinearLayout.HORIZONTAL);
        tip.addView(ui.icon(R.drawable.ic_info, TEXT_SECONDARY, 20));
        int description = category.hasRoles() ? R.string.editor_description : R.string.solo_description;
        TextView tipText = ui.text(getString(description), Type.BODY, TEXT_SECONDARY);
        tip.addView(tipText, weighted(12, ui));
        content.addView(tip, ui.spaced(12));

        LinearLayout actions = footerBar();
        LinearLayout row = horizontal();
        LinearLayout save = ui.button(getString(R.string.script_save), 0, Kind.OUTLINE, view -> saveEditor(false));
        row.addView(save, new LinearLayout.LayoutParams(-2, -2));
        int start = category.hasRoles() ? R.string.script_save_and_rehearse : R.string.solo_save_and_rehearse;
        LinearLayout primary = ui.button(getString(start), R.drawable.ic_play, Kind.PRIMARY, view -> saveEditor(true));
        row.addView(primary, weighted(10, ui));
        actions.addView(row, Ui.matchWidth());
        content.requestFocus();
    }

    private LinearLayout fieldLabel(int label, TextView trailing) {
        LinearLayout row = horizontal();
        TextView text = ui.text(getString(label), Type.LABEL, TEXT_SECONDARY);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        if (trailing != null) {
            row.addView(trailing);
        }
        return row;
    }

    private void updateCounter(TextView counter, int length) {
        NumberFormat format = NumberFormat.getIntegerInstance();
        counter.setText(getString(R.string.character_counter, format.format(length), format.format(MAX_SCRIPT_LENGTH)));
    }

    private EditText field(int hintId, String value, int inputType, int length) {
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        field.setTextColor(TEXT);
        field.setHintTextColor(TEXT_TERTIARY);
        field.setTextSize(16);
        field.setInputType(inputType);
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(length)});
        field.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        field.setBackground(ui.fieldBackground());
        field.setHint(hintId);
        field.setText(value);
        return field;
    }

    private void clearEditorText() {
        String previous = editor.getText().toString();
        if (previous.isEmpty()) {
            return;
        }
        editor.setText("");
        editor.requestFocus();
        showSnackbar(getString(R.string.text_cleared), getString(R.string.undo), () -> {
            if (editor != null) {
                editor.setText(previous);
                editor.setSelection(editor.length());
            }
        });
    }

    private boolean editorChanged() {
        return editor != null && (!editor.getText().toString().equals(savedScript)
            || !titleField.getText().toString().equals(savedTitle));
    }

    private void leaveEditor() {
        if (!editorChanged()) {
            exitEditor();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_message)
            .setPositiveButton(R.string.discard_confirm, (dialog, which) -> exitEditor())
            .setNegativeButton(R.string.keep_editing, null)
            .show();
    }

    private void exitEditor() {
        hideKeyboard();
        if (editorReturn != Screen.HOME && fullScene != null) {
            show(editorReturn);
        } else {
            returnToLibrary();
        }
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null && editor != null) {
            keyboard.hideSoftInputFromWindow(editor.getWindowToken(), 0);
        }
    }

    private void saveEditor(boolean rehearse) {
        String title = titleField.getText().toString().trim();
        if (title.isEmpty()) {
            titleField.requestFocus();
            titleField.setError(getString(R.string.script_name_required));
            return;
        }
        String candidate = editor.getText().toString().trim();
        if (candidate.isEmpty()) {
            editor.requestFocus();
            editor.setError(getString(R.string.script_text_required));
            return;
        }
        Scene parsed = parseScript(candidate);
        if (rehearse && parsed.roles.isEmpty()) {
            editor.requestFocus();
            editor.setError(getString(category.hasRoles() ? R.string.invalid_scene : R.string.script_text_required));
            return;
        }
        scriptId = library.save(scriptId, title, candidate, category);
        scriptTitle = title;
        script = candidate;
        savedTitle = title;
        savedScript = candidate;
        hideKeyboard();
        if (rehearse || (editorReturn != Screen.HOME && !parsed.roles.isEmpty())) {
            if (!rehearse) {
                notifyUser(R.string.saved);
            }
            beginScript(parsed);
        } else {
            notifyUser(R.string.saved);
            returnToLibrary();
        }
    }

    private void showParsePreview(Scene parsed) {
        if (!category.hasRoles()) {
            new AlertDialog.Builder(this).setTitle(R.string.solo_preview_title)
                .setMessage(getString(R.string.solo_preview_count, parsed.lines.size()))
                .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        StringBuilder summary = new StringBuilder(getString(
            R.string.parse_preview_counts, parsed.sections.size(), parsed.lines.size()
        ));
        summary.append("\n\n");
        if (parsed.roles.isEmpty()) {
            summary.append(getString(R.string.invalid_scene));
        } else {
            summary.append(getString(R.string.parse_preview_roles, TextUtils.join(", ", parsed.roles)));
        }
        for (Scene.Section section : parsed.sections) {
            if (!section.title.isEmpty()) {
                summary.append("\n").append(section.title);
            }
        }
        new AlertDialog.Builder(this).setTitle(R.string.parse_preview)
            .setMessage(summary).setPositiveButton(android.R.string.ok, null).show();
    }

    // ---------------------------------------------------------------- Delete

    private void confirmDelete(ScriptLibrary.Script entry) {
        String name = entry.title.isEmpty() ? getString(R.string.imported_title) : entry.title;
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_title, name))
            .setMessage(R.string.delete_message)
            .setPositiveButton(R.string.action_delete, (dialog, which) -> deleteScript(entry))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void deleteCurrent() {
        for (ScriptLibrary.Script entry : library.list()) {
            if (entry.id.equals(scriptId)) {
                confirmDelete(entry);
                return;
            }
        }
    }

    private void deleteScript(ScriptLibrary.Script entry) {
        int index = library.delete(entry.id);
        if (index < 0) {
            return;
        }
        if (entry.id.equals(scriptId)) {
            hideKeyboard();
            scriptId = null;
            scriptTitle = "";
            script = "";
            savedTitle = "";
            savedScript = "";
            fullScene = null;
            editorReturn = Screen.HOME;
        }
        String name = entry.title.isEmpty() ? getString(R.string.imported_title) : entry.title;
        snackbarMessage = getString(R.string.text_deleted, name);
        snackbarActionLabel = getString(R.string.undo);
        snackbarAction = () -> {
            library.restore(entry, index);
            if (screen == Screen.HOME) {
                render();
            }
        };
        main.removeCallbacks(hideSnackbar);
        main.postDelayed(hideSnackbar, SNACKBAR_DURATION);
        returnToLibrary();
    }

    // ---------------------------------------------------------------- Snackbar

    private void showSnackbar(String message, String actionLabel, Runnable action) {
        snackbarMessage = message;
        snackbarActionLabel = actionLabel;
        snackbarAction = action;
        if (snackbarView != null) {
            overlay.removeView(snackbarView);
        }
        addSnackbar();
        main.removeCallbacks(hideSnackbar);
        main.postDelayed(hideSnackbar, SNACKBAR_DURATION);
    }

    private void addSnackbar() {
        LinearLayout bar = horizontal();
        bar.setBackground(ui.shape(SURFACE_HIGHEST, 14));
        bar.setElevation(ui.dp(8));
        bar.setPadding(ui.dp(16), ui.dp(4), ui.dp(4), ui.dp(4));
        bar.setMinimumHeight(ui.dp(52));
        TextView message = ui.text(snackbarMessage, Type.BODY, TEXT);
        message.setMaxLines(2);
        message.setEllipsize(TextUtils.TruncateAt.END);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        bar.addView(message, new LinearLayout.LayoutParams(0, -2, 1));
        if (snackbarAction != null) {
            Runnable action = snackbarAction;
            bar.addView(ui.button(snackbarActionLabel, 0, Kind.TEXT, view -> {
                dismissSnackbar();
                action.run();
            }));
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        params.setMargins(ui.dp(12), 0, ui.dp(12), ui.dp(12));
        // Sit above the floating create button on the library.
        if (screen == Screen.HOME && tab == Tab.SCRIPTS && !library.list().isEmpty()) {
            params.bottomMargin = ui.dp(84);
        }
        overlay.addView(bar, params);
        snackbarView = bar;
    }

    private void dismissSnackbar() {
        main.removeCallbacks(hideSnackbar);
        snackbarMessage = null;
        snackbarAction = null;
        if (snackbarView != null && snackbarView.getParent() instanceof FrameLayout parent) {
            parent.removeView(snackbarView);
        }
        snackbarView = null;
    }

    // ---------------------------------------------------------------- Sections

    private void beginScript(Scene parsed) {
        fullScene = parsed;
        sectionIndex = -1;
        scene = parsed;
        ensureRole();
        show(hasNamedSections() ? Screen.SECTIONS : Screen.SETUP);
    }

    private boolean hasNamedSections() {
        if (fullScene != null) {
            for (Scene.Section section : fullScene.sections) {
                if (!section.title.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void renderSections() {
        LinearLayout bar = topBar(R.drawable.ic_back, null, this::returnToLibrary);
        barAction(bar, R.drawable.ic_edit, R.string.edit_scene, view -> editCurrent());
        pageTitle(scriptTitle, getString(R.string.choose_scene), null);

        LinearLayout whole = ui.card(ACCENT_CONTAINER, view -> selectSection(-1));
        whole.setBackground(ui.ripple(ui.outlined(ACCENT_CONTAINER, 20, 0x40F4A98A), 0x22FFFFFF));
        whole.setOrientation(LinearLayout.HORIZONTAL);
        whole.setGravity(Gravity.CENTER_VERTICAL);
        whole.addView(ui.iconTile(R.drawable.ic_book, Ui.ON_ACCENT, ACCENT, 44));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(ui.text(getString(R.string.entire_script), Type.SUBTITLE, ON_ACCENT_CONTAINER));
        TextView meta = ui.text(getResources().getQuantityString(R.plurals.line_count,
            fullScene.lines.size(), fullScene.lines.size()), Type.BODY, ON_ACCENT_CONTAINER);
        meta.setAlpha(0.75f);
        labels.addView(meta);
        whole.addView(labels, weighted(14, ui));
        whole.addView(ui.icon(R.drawable.ic_chevron_right, ON_ACCENT_CONTAINER, 22));
        content.addView(whole, ui.spaced(24));

        sectionLabel(R.string.sections_label);
        LinearLayout list = ui.card(SURFACE, null);
        list.setPadding(0, ui.dp(4), 0, ui.dp(4));
        for (int i = 0; i < fullScene.sections.size(); i++) {
            int index = i;
            Scene.Section section = fullScene.sections.get(i);
            String title = section.title.isEmpty() ? getString(R.string.untitled_scene, i + 1) : section.title;
            int count = section.end - section.start;
            list.addView(sectionRow(i + 1, title, getResources().getQuantityString(R.plurals.line_count, count, count),
                view -> selectSection(index)), Ui.matchWidth());
            if (i < fullScene.sections.size() - 1) {
                list.addView(ui.divider(68));
            }
        }
        content.addView(list, ui.spaced(10));
    }

    private LinearLayout sectionRow(int number, String title, String detail, View.OnClickListener action) {
        LinearLayout row = horizontal();
        row.setMinimumHeight(ui.dp(64));
        row.setPadding(ui.dp(16), ui.dp(12), ui.dp(12), ui.dp(12));
        row.setBackground(ui.ripple(null, 0x1FFFFFFF));
        row.setOnClickListener(action);
        row.setClickable(true);
        row.setFocusable(true);
        TextView badge = ui.text(String.format(Locale.ROOT, "%02d", number), Type.LABEL, TEXT_SECONDARY);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(ui.shape(SURFACE_HIGHEST, 12));
        row.addView(badge, new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = ui.text(title, Type.SUBTITLE, TEXT);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        labels.addView(ui.text(detail, Type.CAPTION, TEXT_TERTIARY));
        row.addView(labels, weighted(14, ui));
        row.addView(ui.icon(R.drawable.ic_chevron_right, TEXT_TERTIARY, 22));
        return row;
    }

    private void selectSection(int index) {
        sectionIndex = index;
        scene = fullScene.forSection(index);
        ensureRole();
        show(Screen.SETUP);
    }

    private void notifyUser(int message) {
        if (!isDestroyed()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    private void ensureRole() {
        if (!scene.roles.isEmpty() && !scene.roles.contains(role)) {
            role = scene.roles.get(0);
        }
    }

    // ---------------------------------------------------------------- Setup

    private String currentSectionTitle() {
        if (sectionIndex >= 0 && sectionIndex < fullScene.sections.size()) {
            String title = fullScene.sections.get(sectionIndex).title;
            return title.isEmpty() ? getString(R.string.untitled_scene, sectionIndex + 1) : title;
        }
        return getString(R.string.entire_script);
    }

    private void renderSetup() {
        LinearLayout bar = topBar(R.drawable.ic_back, null, this::onBackPressed);
        barAction(bar, R.drawable.ic_book, R.string.read_script, view -> show(Screen.READER));
        barAction(bar, R.drawable.ic_edit, R.string.edit_scene, view -> editCurrent());
        if (scriptId != null) {
            barAction(bar, R.drawable.ic_delete, R.string.action_delete, view -> deleteCurrent());
        }

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(22));
        hero.setBackground(ui.heroWash());
        hero.addView(ui.text(getString(categoryTitle(category)), Type.OVERLINE, ACCENT));
        TextView title = ui.serif(scriptTitle, 30, TEXT);
        title.setLineSpacing(0, 1.05f);
        hero.addView(title, ui.spaced(10));
        if (hasNamedSections()) {
            TextView section = ui.text(currentSectionTitle(), Type.BODY_LARGE, TEXT_SECONDARY);
            hero.addView(section, ui.spaced(6));
        }
        LinearLayout stats = horizontal();
        int lines = scene.lines.size();
        stats.addView(ui.chip(getResources().getQuantityString(category.hasRoles() ? R.plurals.line_count
            : R.plurals.part_count, lines, lines), TEXT, 0x33FFFFFF));
        if (category.hasRoles()) {
            TextView roles = ui.chip(getResources().getQuantityString(R.plurals.role_count,
                scene.roles.size(), scene.roles.size()), TEXT, 0x33FFFFFF);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.leftMargin = ui.dp(8);
            stats.addView(roles, params);
        }
        hero.addView(stats, ui.spaced(18));
        content.addView(hero, ui.spaced(4));

        sectionLabel(R.string.mode_label);
        LinearLayout modes = new LinearLayout(this);
        modes.setBackground(ui.shape(SURFACE, 999));
        modes.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        addMode(modes, Mode.LISTEN, R.string.listen_mode, R.drawable.ic_headphones);
        addMode(modes, Mode.PRACTICE, R.string.practice_mode, R.drawable.ic_mic);
        content.addView(modes, ui.spaced(10));

        int modeDescription;
        if (category.hasRoles()) {
            modeDescription = mode == Mode.LISTEN ? R.string.listen_description : R.string.practice_description;
        } else {
            modeDescription = mode == Mode.LISTEN ? R.string.solo_listen_description : R.string.solo_practice_description;
        }
        TextView description = ui.text(getString(modeDescription), Type.BODY, TEXT_SECONDARY);
        description.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        content.addView(description, ui.spaced(12));

        sectionLabel(R.string.options_label);
        LinearLayout options = ui.card(SURFACE, null);
        options.setPadding(0, ui.dp(4), 0, ui.dp(4));
        List<View> rows = new ArrayList<>();
        if (mode == Mode.PRACTICE && category.hasRoles()) {
            rows.add(ui.listRow(R.drawable.ic_person, getString(R.string.your_role), role,
                view -> showRoleOptions()));
        }
        if (mode == Mode.PRACTICE) {
            rows.add(ui.listRow(R.drawable.ic_eye, getString(R.string.visibility_title),
                getString(visibilityLabel(lineVisibility)), view -> showVisibilityOptions()));
            boolean cue = preferences.getBoolean(TURN_CUE_PREFERENCE, true);
            rows.add(ui.listRow(R.drawable.ic_volume, getString(R.string.turn_cue_title),
                getString(cue ? R.string.turn_cue_on : R.string.turn_cue_off), ui.toggle(cue), view -> {
                    preferences.edit().putBoolean(TURN_CUE_PREFERENCE, !cue).apply();
                    render();
                }));
        }
        if (hasNamedSections()) {
            rows.add(ui.listRow(R.drawable.ic_list, getString(R.string.scene_title),
                currentSectionTitle(), view -> show(Screen.SECTIONS)));
        }
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                options.addView(ui.divider(70));
            }
            options.addView(rows.get(i), Ui.matchWidth());
        }
        if (!rows.isEmpty()) {
            content.addView(options, ui.spaced(10));
        }

        sectionLabel(R.string.premium_tab);
        LinearLayout extras = ui.card(SURFACE, null);
        extras.setPadding(0, ui.dp(4), 0, ui.dp(4));
        boolean unlocked = premium();
        extras.addView(aiVoiceRow(), Ui.matchWidth());
        extras.addView(ui.divider(70));
        if (category.hasRoles()) {
            extras.addView(premiumRow(R.drawable.ic_volume, R.string.cast_title, getString(R.string.cast_value),
                null, () -> show(Screen.VOICES)), Ui.matchWidth());
            extras.addView(ui.divider(70));
        }
        if (mode == Mode.PRACTICE) {
            boolean on = handsFreeEnabled();
            extras.addView(premiumRow(R.drawable.ic_mic, R.string.premium_voice_cue,
                getString(on ? R.string.hands_free_on : R.string.hands_free_off),
                unlocked ? ui.toggle(on) : null, this::toggleHandsFree), Ui.matchWidth());
            extras.addView(ui.divider(70));
        }
        extras.addView(premiumRow(R.drawable.ic_video, R.string.selftape_title, getString(R.string.video_value),
            null, this::openVideo), Ui.matchWidth());
        content.addView(extras, ui.spaced(10));

        LinearLayout actions = footerBar();
        int startLabel = mode == Mode.LISTEN ? R.string.listen_start : R.string.practice_start;
        LinearLayout start = ui.button(getString(startLabel), R.drawable.ic_play, Kind.PRIMARY, view -> {
            ensureRole();
            resetReport();
            screen = Screen.REHEARSAL;
            moveTo(0, true);
        });
        start.setMinimumHeight(ui.dp(56));
        actions.addView(start, Ui.matchWidth());
    }

    private void addMode(LinearLayout group, Mode destination, int title, int drawable) {
        boolean selected = mode == destination;
        LinearLayout choice = horizontal();
        choice.setGravity(Gravity.CENTER);
        choice.setMinimumHeight(ui.dp(48));
        choice.setBackground(selected ? ui.ripple(ui.shape(ACCENT, 999), 0x22FFFFFF) : ui.rippleMask(0x22FFFFFF, 999));
        int color = selected ? Ui.ON_ACCENT : TEXT_SECONDARY;
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20));
        iconParams.rightMargin = ui.dp(8);
        choice.addView(ui.icon(drawable, color, 20), iconParams);
        TextView label = ui.text(getString(title), Type.LABEL, color);
        label.setTextSize(15);
        choice.addView(label);
        choice.setSelected(selected);
        choice.setClickable(true);
        choice.setFocusable(true);
        choice.setContentDescription(getString(title));
        choice.setOnClickListener(view -> {
            if (mode != destination) {
                mode = destination;
                render();
            }
        });
        group.addView(choice, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void showRoleOptions() {
        String[] choices = scene.roles.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(R.string.choose_practice_role)
            .setSingleChoiceItems(choices, scene.roles.indexOf(role), (dialog, index) -> {
                role = choices[index];
                dialog.dismiss();
                render();
            })
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showVisibilityOptions() {
        Visibility[] values = Visibility.values();
        String[] choices = new String[values.length];
        for (int index = 0; index < values.length; index++) {
            choices[index] = getString(visibilityLabel(values[index]));
        }
        new AlertDialog.Builder(this).setTitle(R.string.visibility_choose)
            .setSingleChoiceItems(choices, lineVisibility.ordinal(), (dialog, index) -> {
                lineVisibility = values[index];
                dialog.dismiss();
                render();
            })
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private int visibilityLabel(Visibility value) {
        return switch (value) {
            case FULL -> R.string.visibility_full;
            case HINT -> R.string.visibility_hint;
            case INITIALS -> R.string.visibility_initials;
            case HIDDEN -> R.string.visibility_hidden;
        };
    }

    // ---------------------------------------------------------------- Reader

    private void renderReader() {
        topBar(R.drawable.ic_back, scriptTitle, () -> show(Screen.SETUP));
        SpannableStringBuilder transcript = new SpannableStringBuilder();
        String previousSection = "";
        for (Scene.Line line : scene.lines) {
            if (!line.section.isEmpty() && !line.section.equals(previousSection)) {
                if (transcript.length() > 0) {
                    transcript.append("\n");
                }
                int start = transcript.length();
                transcript.append(line.section.toUpperCase(Locale.getDefault())).append("\n\n");
                transcript.setSpan(new TypefaceSpan("sans-serif-medium"), start, transcript.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                transcript.setSpan(new RelativeSizeSpan(0.7f), start, transcript.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                transcript.setSpan(new ForegroundColorSpan(TEXT_TERTIARY), start, transcript.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                previousSection = line.section;
            }
            for (String note : line.notes) {
                appendStyled(transcript, note + "\n", Typeface.ITALIC, TEXT_TERTIARY);
            }
            if (category.hasRoles()) {
                int start = transcript.length();
                transcript.append(line.role).append("\n");
                boolean mine = line.role.equals(role);
                transcript.setSpan(new TypefaceSpan("sans-serif-medium"), start, transcript.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                transcript.setSpan(new RelativeSizeSpan(0.72f), start, transcript.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                transcript.setSpan(new ForegroundColorSpan(mine ? ACCENT : VIOLET), start, transcript.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            transcript.append(line.text).append("\n\n");
        }
        TextView manuscript = ui.serif(transcript, 19, TEXT);
        manuscript.setLineSpacing(ui.dp(5), 1);
        manuscript.setTextIsSelectable(true);
        content.addView(manuscript, ui.spaced(8));
    }

    private void appendStyled(SpannableStringBuilder text, String value, int style, int color) {
        int start = text.length();
        text.append(value);
        text.setSpan(new StyleSpan(style), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(color), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ---------------------------------------------------------------- Rehearsal

    private void renderRehearsal() {
        int total = scene.lines.size();
        String title = reviewBase != null ? getString(R.string.results_struggled)
            : hasNamedSections() ? currentSectionTitle() : scriptTitle;
        LinearLayout bar = topBar(R.drawable.ic_close, title, () -> show(Screen.SETUP));
        if (mode == Mode.PRACTICE && position < total) {
            boolean on = handsFreeEnabled();
            barAction(bar, on ? R.drawable.ic_mic : R.drawable.ic_mic_off,
                on ? R.string.hands_free_disable : R.string.hands_free_enable, view -> toggleHandsFree());
        }
        TextView counter = ui.text(getString(R.string.line_progress, Math.min(position + 1, total), total),
            Type.LABEL, TEXT_SECONDARY);
        counter.setPadding(ui.dp(8), 0, ui.dp(12), 0);
        counter.setContentDescription(getString(R.string.line_counter, Math.min(position + 1, total), total));
        bar.addView(counter);
        LinearLayout progress = ui.progress(position, total);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, ui.dp(4));
        progressParams.setMargins(ui.dp(20), 0, ui.dp(20), ui.dp(8));
        header.addView(progress, progressParams);

        if (position == total) {
            renderCompleted(total);
            return;
        }

        Scene.Line line = scene.lines.get(position);
        boolean myTurn = !RehearsalOptions.shouldSpeak(mode, role, line.role);

        if (myTurn && position > 0 && category.hasRoles()) {
            Scene.Line cue = scene.lines.get(position - 1);
            LinearLayout cueCard = new LinearLayout(this);
            cueCard.setOrientation(LinearLayout.VERTICAL);
            cueCard.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
            cueCard.setBackground(ui.shape(SURFACE, 16));
            cueCard.addView(ui.text(getString(R.string.cue_label, cue.role), Type.CAPTION, TEXT_TERTIARY));
            TextView cueText = ui.serif(cue.text, 15, TEXT_SECONDARY);
            cueText.setMaxLines(3);
            cueText.setEllipsize(TextUtils.TruncateAt.END);
            cueCard.addView(cueText, ui.spaced(4));
            content.addView(cueCard, ui.spaced(12));
        }

        int turnLabel = myTurn ? R.string.your_turn
            : mode == Mode.LISTEN ? R.string.reading_role : R.string.partner_turn;
        String turn = category.hasRoles() ? getString(turnLabel, line.role)
            : getString(myTurn ? R.string.solo_your_turn : R.string.listen_mode);
        LinearLayout speaker = myTurn
            ? ui.chip(turn, R.drawable.ic_mic, ON_ACCENT_CONTAINER, ACCENT_CONTAINER)
            : ui.chip(turn, R.drawable.ic_volume, VIOLET, VIOLET_CONTAINER);
        content.addView(speaker, ui.wrapSpaced(myTurn && position > 0 ? 24 : 20));

        if (!line.notes.isEmpty()) {
            TextView directions = ui.serif(TextUtils.join("\n", line.notes), 15, TEXT_TERTIARY);
            directions.setTypeface(Typeface.SERIF, Typeface.ITALIC);
            content.addView(directions, ui.spaced(14));
        }

        LinearLayout lineCard = new LinearLayout(this);
        lineCard.setOrientation(LinearLayout.VERTICAL);
        lineCard.setPadding(ui.dp(22), ui.dp(24), ui.dp(22), ui.dp(24));
        lineCard.setBackground(myTurn ? ui.outlined(SURFACE, 24, 0x80F4A98A) : ui.outlined(SURFACE, 24, OUTLINE_SOFT));
        String shown = myTurn && !revealLine ? lineVisibility.visibleText(line.text) : line.text;
        TextView dialogue = ui.serif(shown.isEmpty() ? getString(R.string.line_hidden) : shown, 24,
            shown.isEmpty() ? TEXT_TERTIARY : TEXT);
        dialogue.setLineSpacing(ui.dp(6), 1);
        if (shown.isEmpty()) {
            dialogue.setTypeface(Typeface.SERIF, Typeface.ITALIC);
        }
        lineCard.addView(dialogue);
        if (myTurn && !revealLine && lineVisibility != Visibility.FULL) {
            LinearLayout reveal = ui.button(getString(R.string.line_reveal), R.drawable.ic_eye, Kind.TONAL, view -> {
                revealLine = true;
                revealedLines.add(position);
                render();
            });
            lineCard.addView(reveal, ui.wrapSpaced(20));
        }
        content.addView(lineCard, ui.spaced(12));

        heardView = null;
        if (myTurn && handsFreeEnabled()) {
            content.addView(listeningPanel(), ui.spaced(14));
        } else {
            TextView status = ui.text(getString(myTurn ? R.string.speak_then_continue : speechMessage()),
                Type.BODY, TEXT_SECONDARY);
            status.setGravity(Gravity.CENTER);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            content.addView(status, ui.spaced(18));
        }

        LinearLayout controls = footerBar();
        LinearLayout row = horizontal();
        FrameLayout previous = ui.iconButton(R.drawable.ic_previous, getString(R.string.previous_line),
            position > 0 ? TEXT : TEXT_TERTIARY, SURFACE_HIGHEST, 56, view -> {
                if (position > 0) {
                    markRetry(position - 1);
                    markRetry(position);
                    moveTo(position - 1, false);
                }
            });
        previous.setEnabled(position > 0);
        previous.setAlpha(position > 0 ? 1f : 0.5f);
        row.addView(previous);
        if (!myTurn && (speechState == SpeechState.READY || useAiVoice())) {
            boolean playing = activeUtterance != null;
            FrameLayout play = ui.iconButton(playing ? R.drawable.ic_stop : R.drawable.ic_play,
                getString(playing ? R.string.stop_line : R.string.play_line), ACCENT, ACCENT_CONTAINER, 56, view -> {
                    if (activeUtterance == null) {
                        playCurrent();
                    } else {
                        cancelSpeech();
                        render();
                    }
                });
            LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56));
            playParams.leftMargin = ui.dp(10);
            row.addView(play, playParams);
        }
        LinearLayout next = ui.button(getString(R.string.next_line), R.drawable.ic_next, Kind.PRIMARY,
            view -> moveTo(position + 1, true));
        next.setMinimumHeight(ui.dp(56));
        row.addView(next, weighted(10, ui));
        controls.addView(row, Ui.matchWidth());
    }

    private void renderCompleted(int total) {
        boolean results = mode == Mode.PRACTICE && total > 0;
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        if (!results) {
            content.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        }
        LinearLayout.LayoutParams badge = new LinearLayout.LayoutParams(ui.dp(88), ui.dp(88));
        badge.topMargin = ui.dp(results ? 16 : 0);
        content.addView(ui.iconTile(R.drawable.ic_check, Ui.ON_ACCENT, ACCENT, 88), badge);
        TextView title = ui.text(getString(R.string.completed_title), Type.HEADLINE, TEXT);
        title.setGravity(Gravity.CENTER);
        content.addView(title, ui.spaced(24));
        TextView detail = ui.text(getString(R.string.completed_detail, total), Type.BODY_LARGE, TEXT_SECONDARY);
        detail.setGravity(Gravity.CENTER);
        content.addView(detail, ui.spaced(8));
        if (total > 0) {
            LinearLayout previous = ui.button(getString(R.string.previous_line), R.drawable.ic_previous, Kind.TEXT,
                view -> moveTo(position - 1, false));
            content.addView(previous, ui.wrapSpaced(12));
        }
        if (results) {
            if (premium()) {
                renderResults();
            } else {
                LinearLayout teaser = ui.card(SURFACE, view -> showPaywall(R.string.premium_review));
                teaser.addView(ui.listRow(R.drawable.ic_insights, getString(R.string.premium_review),
                    getString(R.string.premium_review_description), ui.premiumBadge(), null), Ui.matchWidth());
                teaser.setPadding(0, ui.dp(4), 0, ui.dp(4));
                content.addView(teaser, ui.spaced(24));
            }
        } else {
            content.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        }

        LinearLayout actions = footerBar();
        LinearLayout row = horizontal();
        LinearLayout back = ui.button(getString(R.string.back_to_setup_short), 0, Kind.OUTLINE,
            view -> show(Screen.SETUP));
        row.addView(back, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout restart = ui.button(getString(R.string.restart), R.drawable.ic_replay, Kind.PRIMARY, view -> {
            resetReport();
            moveTo(0, true);
        });
        row.addView(restart, weighted(10, ui));
        actions.addView(row, Ui.matchWidth());
    }

    /** Premium: what went wrong in this run and a shortcut to practise just those lines. */
    private void renderResults() {
        LinearLayout card = ui.card(SURFACE, null);
        card.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));
        card.addView(ui.text(getString(R.string.results_title), Type.OVERLINE, ACCENT));
        LinearLayout stats = horizontal();
        addStat(stats, getResources().getQuantityString(R.plurals.hint_count,
            revealedLines.size(), revealedLines.size()));
        addStat(stats, getResources().getQuantityString(R.plurals.retry_count,
            retriedLines.size(), retriedLines.size()));
        if (handsFreeEnabled() || !missedLines.isEmpty()) {
            addStat(stats, getResources().getQuantityString(R.plurals.missed_count,
                missedLines.size(), missedLines.size()));
        }
        card.addView(stats, ui.spaced(12));

        Set<Integer> struggled = struggledLines();
        if (struggled.isEmpty()) {
            card.addView(ui.text(getString(R.string.results_clean), Type.BODY_LARGE, TEXT), ui.spaced(16));
            content.addView(card, ui.spaced(24));
            return;
        }
        card.addView(ui.text(getString(R.string.results_struggled), Type.SUBTITLE, TEXT), ui.spaced(18));
        int shown = 0;
        for (int index : struggled) {
            if (shown++ == 6) {
                break;
            }
            Scene.Line line = scene.lines.get(index);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
            item.setBackground(ui.shape(SURFACE_HIGH, 14));
            LinearLayout reasons = horizontal();
            if (category.hasRoles()) {
                TextView who = ui.text(line.role, Type.CAPTION, ACCENT);
                who.setTypeface(Ui.MEDIUM);
                reasons.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
            } else {
                reasons.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            }
            if (revealedLines.contains(index)) {
                addReason(reasons, R.string.reason_hint);
            }
            if (retriedLines.contains(index)) {
                addReason(reasons, R.string.reason_retry);
            }
            if (missedLines.contains(index)) {
                addReason(reasons, R.string.reason_missed);
            }
            item.addView(reasons, Ui.matchWidth());
            TextView text = ui.serif(line.text, 16, TEXT);
            text.setMaxLines(3);
            text.setEllipsize(TextUtils.TruncateAt.END);
            item.addView(text, ui.spaced(6));
            card.addView(item, ui.spaced(8));
        }
        LinearLayout practise = ui.button(getString(R.string.results_practice, struggled.size()),
            R.drawable.ic_replay, Kind.PRIMARY, view -> startReview(struggled));
        card.addView(practise, ui.spaced(16));
        content.addView(card, ui.spaced(24));
    }

    private void addStat(LinearLayout row, String label) {
        TextView chip = ui.chip(label, TEXT, SURFACE_HIGHEST);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        if (row.getChildCount() > 0) {
            params.leftMargin = ui.dp(8);
        }
        row.addView(chip, params);
    }

    private void addReason(LinearLayout row, int label) {
        TextView chip = ui.chip(getString(label), ON_ACCENT_CONTAINER, ACCENT_CONTAINER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = ui.dp(6);
        row.addView(chip, params);
    }

    // ---------------------------------------------------------------- Premium

    private boolean premium() {
        return Premium.isActive(preferences);
    }

    private boolean handsFreeEnabled() {
        return premium() && preferences.getBoolean(HANDS_FREE_PREFERENCE, false)
            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** A row for a Premium feature: opens it on Premium, shows a lock and the paywall otherwise. */
    private LinearLayout premiumRow(int drawable, int title, String value, View trailing, Runnable open) {
        if (!premium()) {
            return ui.listRow(drawable, getString(title), value, ui.premiumBadge(), view -> showPaywall(title));
        }
        if (trailing != null) {
            return ui.listRow(drawable, getString(title), value, trailing, view -> open.run());
        }
        return ui.listRow(drawable, getString(title), value, view -> open.run());
    }

    private void showPaywall(int feature) {
        new AlertDialog.Builder(this)
            .setTitle(feature)
            .setMessage(getString(R.string.paywall_message, getString(feature)))
            .setPositiveButton(R.string.paywall_open, (dialog, which) -> {
                tab = Tab.PREMIUM;
                show(Screen.HOME);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void setPremium(boolean active) {
        Premium.setActive(preferences, active);
        if (!active) {
            stopListening();
        }
        notifyUser(active ? R.string.premium_now_on : R.string.premium_now_off);
        render();
    }

    private void renderPremium() {
        boolean active = premium();
        pageTitle(getString(R.string.app_name), getString(R.string.premium_tab), null);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(24));
        hero.setBackground(ui.heroWash());
        LinearLayout top = horizontal();
        top.addView(ui.iconTile(active ? R.drawable.ic_check : R.drawable.ic_star, ACCENT, ACCENT_CONTAINER, 44));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        boolean testable = Premium.canSwitchForTesting(this);
        if (active || !testable) {
            top.addView(ui.chip(getString(active ? R.string.premium_active_chip : R.string.coming_soon),
                ON_ACCENT_CONTAINER, ACCENT_CONTAINER));
        }
        hero.addView(top, Ui.matchWidth());
        hero.addView(ui.text(getString(active ? R.string.premium_active_title : R.string.premium_headline),
            Type.HEADLINE, TEXT), ui.spaced(18));
        hero.addView(ui.text(getString(active ? R.string.premium_active_description : R.string.premium_description),
            Type.BODY_LARGE, TEXT_SECONDARY), ui.spaced(6));
        if (testable) {
            LinearLayout switchPlan = ui.button(getString(active ? R.string.premium_switch_off
                : R.string.premium_switch_on), active ? 0 : R.drawable.ic_star,
                active ? Kind.OUTLINE : Kind.PRIMARY, view -> setPremium(!active));
            hero.addView(switchPlan, ui.spaced(20));
        }
        content.addView(hero, ui.spaced(20));

        if (testable) {
            LinearLayout note = ui.card(SURFACE, null);
            note.setOrientation(LinearLayout.HORIZONTAL);
            note.addView(ui.icon(R.drawable.ic_info, TEXT_SECONDARY, 20));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(ui.text(getString(R.string.premium_test_title), Type.SUBTITLE, TEXT));
            labels.addView(ui.text(getString(R.string.premium_test_description), Type.BODY, TEXT_SECONDARY),
                ui.spaced(2));
            note.addView(labels, weighted(12, ui));
            content.addView(note, ui.spaced(12));
        }

        sectionLabel(R.string.premium_features);
        LinearLayout list = ui.card(SURFACE, null);
        list.setPadding(0, ui.dp(4), 0, ui.dp(4));
        feature(list, R.drawable.ic_volume, R.string.premium_voices, R.string.premium_voices_description, true);
        feature(list, R.drawable.ic_mic, R.string.premium_voice_cue, R.string.premium_voice_cue_description, true);
        feature(list, R.drawable.ic_insights, R.string.premium_review, R.string.premium_review_description, true);
        feature(list, R.drawable.ic_video, R.string.premium_video, R.string.premium_video_description, false);
        content.addView(list, ui.spaced(10));
        if (active) {
            TextView where = ui.text(getString(R.string.premium_where), Type.BODY, TEXT_TERTIARY);
            content.addView(where, ui.spaced(12));
        }
    }

    // ---------------------------------------------------------------- Character voices

    private String castKey() {
        return scriptId == null ? "sample" : scriptId;
    }

    private List<String> castVoiceNames() {
        List<String> names = new ArrayList<>();
        for (Voice voice : castVoices) {
            names.add(voice.getName());
        }
        return names;
    }

    private Map<String, VoiceCast.Part> currentCast() {
        List<String> roles = fullScene == null ? Collections.emptyList() : fullScene.roles;
        return VoiceCast.load(preferences, castKey(), roles, castVoiceNames());
    }

    private Voice defaultVoice() {
        int index = selectedVoiceIndex();
        return index > 0 ? turkishVoices.get(index - 1) : null;
    }

    private String voiceLabel(String name) {
        if (name.isEmpty()) {
            return getString(R.string.voice_default);
        }
        for (int i = 0; i < castVoices.size(); i++) {
            Voice voice = castVoices.get(i);
            if (voice.getName().equals(name)) {
                String label = getString(R.string.voice_option, i + 1);
                return voice.isNetworkConnectionRequired() ? getString(R.string.voice_online, label) : label;
            }
        }
        return getString(R.string.voice_default);
    }

    private void renderVoices() {
        LinearLayout bar = topBar(R.drawable.ic_back, getString(R.string.cast_title), () -> show(Screen.SETUP));
        barAction(bar, R.drawable.ic_replay, R.string.cast_auto, view -> {
            VoiceCast.reset(preferences, castKey());
            render();
        });
        boolean ai = useAiVoice();
        content.addView(ui.text(getString(ai ? R.string.cast_intro_ai : R.string.cast_intro), Type.BODY,
            TEXT_SECONDARY), ui.spaced(8));
        if (!ai && speechState != SpeechState.READY) {
            content.addView(ui.text(getString(speechState == SpeechState.LOADING ? R.string.speech_loading
                : R.string.speech_unavailable), Type.BODY_LARGE, TEXT), ui.spaced(20));
            return;
        }
        Map<String, VoiceCast.Part> cast = currentCast();
        for (String character : fullScene.roles) {
            VoiceCast.Part part = cast.get(character);
            LinearLayout card = ui.card(SURFACE, null);
            card.setPadding(ui.dp(16), ui.dp(10), ui.dp(8), ui.dp(16));
            LinearLayout heading = horizontal();
            TextView name = ui.text(character, Type.TITLE, TEXT);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            if (character.equals(role) && mode == Mode.PRACTICE) {
                heading.addView(ui.chip(getString(R.string.cast_you), ON_ACCENT_CONTAINER, ACCENT_CONTAINER));
            }
            heading.addView(ui.iconButton(R.drawable.ic_play, getString(R.string.cast_preview, character),
                ACCENT, 0, 48, view -> previewCharacter(character)));
            card.addView(heading, Ui.matchWidth());

            LinearLayout voiceRow = horizontal();
            voiceRow.setPadding(0, ui.dp(4), ui.dp(8), 0);
            voiceRow.addView(ui.text(getString(R.string.cast_voice), Type.LABEL, TEXT_SECONDARY),
                new LinearLayout.LayoutParams(ui.dp(56), -2));
            LinearLayout picker = ui.button(ai ? getString(R.string.ai_voice_option, part.speaker + 1)
                : voiceLabel(part.voice), ai ? R.drawable.ic_star : R.drawable.ic_volume, Kind.TONAL,
                view -> chooseCharacterVoice(character));
            picker.setMinimumHeight(ui.dp(44));
            voiceRow.addView(picker, new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(voiceRow, ui.spaced(4));

            if (!ai) {
                VoiceCast.Tone[] tones = VoiceCast.Tone.values();
                card.addView(optionRow(R.string.cast_tone, new int[] {R.string.tone_low, R.string.tone_normal,
                    R.string.tone_high}, part.tone.ordinal(), index -> updateCast(character,
                        cast.get(character).withTone(tones[index]))), ui.spaced(12));
            }
            VoiceCast.Pace[] paces = VoiceCast.Pace.values();
            card.addView(optionRow(R.string.cast_pace, new int[] {R.string.pace_slow, R.string.pace_normal,
                R.string.pace_fast}, part.pace.ordinal(), index -> updateCast(character,
                    cast.get(character).withPace(paces[index]))), ui.spaced(10));
            content.addView(card, ui.spaced(12));
        }
    }

    private interface Pick {
        void pick(int index);
    }

    private LinearLayout optionRow(int label, int[] options, int selected, Pick pick) {
        LinearLayout row = horizontal();
        row.setPadding(0, 0, ui.dp(8), 0);
        row.addView(ui.text(getString(label), Type.LABEL, TEXT_SECONDARY), new LinearLayout.LayoutParams(ui.dp(56), -2));
        LinearLayout group = new LinearLayout(this);
        group.setBackground(ui.shape(SURFACE_HIGH, 999));
        group.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
        for (int i = 0; i < options.length; i++) {
            int index = i;
            boolean on = i == selected;
            TextView option = ui.text(getString(options[i]), Type.LABEL, on ? Ui.ON_ACCENT : TEXT_SECONDARY);
            option.setGravity(Gravity.CENTER);
            option.setMinHeight(ui.dp(38));
            option.setSingleLine(true);
            option.setBackground(on ? ui.ripple(ui.shape(ACCENT, 999), 0x22FFFFFF) : ui.rippleMask(0x22FFFFFF, 999));
            option.setSelected(on);
            option.setOnClickListener(view -> {
                if (!on) {
                    pick.pick(index);
                }
            });
            group.addView(option, new LinearLayout.LayoutParams(0, -2, 1));
        }
        row.addView(group, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private void updateCast(String character, VoiceCast.Part part) {
        Map<String, VoiceCast.Part> cast = currentCast();
        cast.put(character, part);
        VoiceCast.save(preferences, castKey(), cast);
        render();
    }

    private void chooseCharacterVoice(String character) {
        if (useAiVoice()) {
            String[] speakers = new String[NeuralVoice.SPEAKERS];
            for (int i = 0; i < speakers.length; i++) {
                speakers[i] = getString(R.string.ai_voice_option, i + 1);
            }
            new AlertDialog.Builder(this).setTitle(getString(R.string.cast_voice_for, character))
                .setSingleChoiceItems(speakers, currentCast().get(character).speaker, (dialog, index) -> {
                    dialog.dismiss();
                    updateCast(character, currentCast().get(character).withSpeaker(index));
                    previewCharacter(character);
                })
                .setNegativeButton(android.R.string.cancel, null).show();
            return;
        }
        String[] labels = new String[castVoices.size() + 1];
        labels[0] = getString(R.string.voice_default);
        int selected = 0;
        String current = currentCast().get(character).voice;
        for (int i = 0; i < castVoices.size(); i++) {
            labels[i + 1] = voiceLabel(castVoices.get(i).getName());
            if (castVoices.get(i).getName().equals(current)) {
                selected = i + 1;
            }
        }
        new AlertDialog.Builder(this).setTitle(getString(R.string.cast_voice_for, character))
            .setSingleChoiceItems(labels, selected, (dialog, index) -> {
                dialog.dismiss();
                String name = index == 0 ? "" : castVoices.get(index - 1).getName();
                updateCast(character, currentCast().get(character).withVoice(name));
                previewCharacter(character);
            })
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void previewCharacter(String character) {
        if (speechState != SpeechState.READY && !useAiVoice()) {
            return;
        }
        String sample = getString(R.string.voice_preview_text);
        for (Scene.Line line : fullScene.lines) {
            if (line.role.equals(character)) {
                sample = line.text;
                break;
            }
        }
        if (useAiVoice()) {
            VoiceCast.Part part = currentCast().get(character);
            previewAiVoice(part.speaker, part.pace.rate, sample);
            return;
        }
        cancelSpeech();
        VoiceCast.apply(speech, currentCast().get(character), castVoices, defaultVoice(), TURKISH);
        activePreview = "preview-" + ++utteranceNumber;
        if (speech.speak(sample, TextToSpeech.QUEUE_FLUSH, null, activePreview) != TextToSpeech.SUCCESS) {
            activePreview = null;
            notifyUser(R.string.speech_failed);
        }
    }

    // ---------------------------------------------------------------- AI voice

    /** Whether lines are read by the on-device AI voice instead of the Android voice. */
    private boolean useAiVoice() {
        if (!premium() || !NeuralVoice.isSupported() || !preferences.getBoolean(AI_VOICE_PREFERENCE, false)) {
            return false;
        }
        NeuralVoice.State state = neural.state();
        return state == NeuralVoice.State.INSTALLED || state == NeuralVoice.State.LOADING
            || state == NeuralVoice.State.READY || state == NeuralVoice.State.FAILED;
    }

    private void onNeuralVoiceChanged() {
        NeuralVoice.State state = neural.state();
        if (screen == Screen.REHEARSAL && autoPlayPending
            && (state == NeuralVoice.State.READY || state == NeuralVoice.State.FAILED)) {
            autoPlayPending = false;
            if (state == NeuralVoice.State.FAILED) {
                notifyUser(R.string.ai_voice_failed);
            }
            playCurrent();
            return;
        }
        if (state == NeuralVoice.State.READY && screen == Screen.REHEARSAL) {
            prefetchAhead();
        }
        if ((screen == Screen.HOME && tab == Tab.SETTINGS) || screen == Screen.VOICES || screen == Screen.SETUP
            || (screen == Screen.REHEARSAL && activeUtterance == null)) {
            render();
        }
    }

    private void playWithAiVoice(Scene.Line line) {
        if (!neural.isReady()) {
            autoPlayPending = true;
            neural.load();
            render();
            return;
        }
        cancelSpeech();
        stopListening();
        playbackFailed = false;
        String id = "line-" + ++utteranceNumber;
        activeUtterance = id;
        aiSpeaking = true;
        VoiceCast.Part part = currentCast().get(line.role);
        neural.speak(line.text, part == null ? 0 : part.speaker, part == null ? 1f : part.pace.rate, 1f,
            new NeuralVoice.Playback() {
                @Override
                public void onDone() {
                    finishSpeech(id, false);
                }

                @Override
                public void onError() {
                    finishSpeech(id, true);
                }
            });
        render();
        prefetchAhead();
    }

    /** Prepares the next partner lines while the current one plays or the actor speaks. */
    private void prefetchAhead() {
        if (!useAiVoice() || !neural.isReady() || screen != Screen.REHEARSAL) {
            return;
        }
        Map<String, VoiceCast.Part> cast = currentCast();
        int found = 0;
        for (int i = position + 1; i < scene.lines.size() && found < 2; i++) {
            Scene.Line line = scene.lines.get(i);
            if (RehearsalOptions.shouldSpeak(mode, role, line.role)) {
                VoiceCast.Part part = cast.get(line.role);
                neural.prefetch(line.text, part == null ? 0 : part.speaker, part == null ? 1f : part.pace.rate);
                found++;
            }
        }
    }

    private void renderAiVoiceSettings() {
        sectionLabel(R.string.ai_voice_title);
        content.addView(ui.text(getString(R.string.ai_voice_description), Type.BODY, TEXT_SECONDARY), ui.spaced(8));
        LinearLayout card = ui.card(SURFACE, null);
        card.setPadding(0, ui.dp(4), 0, ui.dp(4));
        String size = getString(R.string.ai_voice_size, Math.round(NeuralVoice.downloadBytes() / 1_000_000.0));
        if (!NeuralVoice.isSupported()) {
            card.addView(ui.listRow(R.drawable.ic_star, getString(R.string.ai_voice_title),
                getString(R.string.ai_voice_unsupported), null), Ui.matchWidth());
        } else if (!premium()) {
            card.addView(ui.listRow(R.drawable.ic_star, getString(R.string.ai_voice_title), size,
                ui.premiumBadge(), view -> showPaywall(R.string.ai_voice_title)), Ui.matchWidth());
        } else {
            switch (neural.state()) {
                case NOT_INSTALLED -> card.addView(ui.listRow(R.drawable.ic_star,
                    getString(R.string.ai_voice_download), size, view -> confirmAiDownload()), Ui.matchWidth());
                case DOWNLOADING -> {
                    LinearLayout row = ui.listRow(R.drawable.ic_star, getString(R.string.ai_voice_downloading,
                        neural.progress()), getString(R.string.ai_voice_keep_open),
                        ui.button(getString(android.R.string.cancel), 0, Kind.TEXT, view -> neural.cancelDownload()),
                        null);
                    card.addView(row, Ui.matchWidth());
                    LinearLayout bar = ui.progress(neural.progress(), 100);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, ui.dp(4));
                    params.setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(12));
                    card.addView(bar, params);
                }
                case FAILED -> {
                    card.addView(ui.listRow(R.drawable.ic_star, getString(R.string.ai_voice_failed),
                        getString(R.string.ai_voice_failed_detail, neural.lastError()) + "\n"
                            + getString(R.string.ai_voice_retry), view -> {
                            neural.delete();
                            neural.download();
                        }), Ui.matchWidth());
                }
                default -> {
                    boolean on = preferences.getBoolean(AI_VOICE_PREFERENCE, false);
                    card.addView(ui.listRow(R.drawable.ic_star, getString(R.string.ai_voice_use),
                        getString(on ? R.string.ai_voice_on : R.string.ai_voice_off), ui.toggle(on), view -> {
                            preferences.edit().putBoolean(AI_VOICE_PREFERENCE, !on).apply();
                            if (!on) {
                                neural.load();
                            }
                            render();
                        }), Ui.matchWidth());
                    card.addView(ui.divider(70));
                    String status = neural.isReady() ? getString(R.string.ai_voice_preview_detail)
                        : getString(R.string.ai_voice_loading);
                    card.addView(ui.listRow(R.drawable.ic_play, getString(R.string.voice_preview), status,
                        view -> previewAiVoice(0)), Ui.matchWidth());
                    card.addView(ui.divider(70));
                    card.addView(ui.listRow(R.drawable.ic_delete, getString(R.string.ai_voice_delete), size,
                        view -> confirmAiDelete()), Ui.matchWidth());
                }
            }
        }
        content.addView(card, ui.spaced(12));
    }

    /** Practice options row: which voice reads the lines, with the next step to improve it. */
    private LinearLayout aiVoiceRow() {
        if (!NeuralVoice.isSupported()) {
            return ui.listRow(R.drawable.ic_star, getString(R.string.ai_voice_title),
                getString(R.string.ai_voice_unsupported), null);
        }
        if (!premium()) {
            return premiumRow(R.drawable.ic_star, R.string.ai_voice_title, getString(R.string.ai_voice_off), null,
                () -> { });
        }
        boolean on = preferences.getBoolean(AI_VOICE_PREFERENCE, false);
        return switch (neural.state()) {
            case NOT_INSTALLED -> ui.listRow(R.drawable.ic_star, getString(R.string.ai_voice_title),
                getString(R.string.ai_voice_tap_download, Math.round(NeuralVoice.downloadBytes() / 1_000_000.0)),
                ui.chip(getString(R.string.ai_voice_download_confirm), ON_ACCENT_CONTAINER, ACCENT_CONTAINER),
                view -> confirmAiDownload());
            case DOWNLOADING -> ui.listRow(R.drawable.ic_star, getString(R.string.ai_voice_title),
                getString(R.string.ai_voice_downloading, neural.progress()), null, null);
            case FAILED -> ui.listRow(R.drawable.ic_star, getString(R.string.ai_voice_failed),
                getString(R.string.ai_voice_failed_detail, neural.lastError()), view -> {
                    tab = Tab.SETTINGS;
                    show(Screen.HOME);
                });
            default -> ui.listRow(R.drawable.ic_star, getString(R.string.ai_voice_title),
                getString(on ? R.string.ai_voice_on : R.string.ai_voice_off), ui.toggle(on), view -> {
                    preferences.edit().putBoolean(AI_VOICE_PREFERENCE, !on).apply();
                    if (!on) {
                        neural.load();
                    }
                    render();
                });
        };
    }

    private void confirmAiDownload() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.ai_voice_download)
            .setMessage(getString(R.string.ai_voice_download_message,
                Math.round(NeuralVoice.downloadBytes() / 1_000_000.0)))
            .setPositiveButton(R.string.ai_voice_download_confirm, (dialog, which) -> {
                preferences.edit().putBoolean(AI_VOICE_PREFERENCE, true).apply();
                neural.download();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmAiDelete() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.ai_voice_delete)
            .setMessage(R.string.ai_voice_delete_message)
            .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                preferences.edit().putBoolean(AI_VOICE_PREFERENCE, false).apply();
                neural.delete();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void previewAiVoice(int speaker) {
        previewAiVoice(speaker, 1f, getString(R.string.voice_preview_text));
    }

    private void previewAiVoice(int speaker, float speed, String text) {
        if (!neural.isReady()) {
            neural.load();
            notifyUser(R.string.ai_voice_loading);
            return;
        }
        cancelSpeech();
        String id = "preview-" + ++utteranceNumber;
        activePreview = id;
        neural.speak(text, speaker, speed, 1f, new NeuralVoice.Playback() {
            @Override
            public void onDone() {
                finishSpeech(id, false);
            }

            @Override
            public void onError() {
                finishSpeech(id, true);
            }
        });
    }

    private void showLicenses() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.licenses_title)
            .setMessage(R.string.licenses_text)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    // ---------------------------------------------------------------- Video practice

    private void openVideo() {
        Map<String, VoiceCast.Part> cast = category.hasRoles() ? currentCast() : null;
        Voice fallback = defaultVoice();
        startActivity(VideoActivity.intent(this, scriptTitle, scene, role, category.hasRoles(), cast,
            fallback == null ? "" : fallback.getName(), useAiVoice()));
    }

    // ---------------------------------------------------------------- Hands-free cues

    private void toggleHandsFree() {
        if (!premium()) {
            showPaywall(R.string.premium_voice_cue);
            return;
        }
        if (handsFreeEnabled()) {
            preferences.edit().putBoolean(HANDS_FREE_PREFERENCE, false).apply();
            stopListening();
            render();
            return;
        }
        if (!HandsFree.isAvailable(this)) {
            notifyUser(R.string.hands_free_unavailable);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            return;
        }
        preferences.edit().putBoolean(HANDS_FREE_PREFERENCE, true).apply();
        render();
        listenIfNeeded();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_MICROPHONE) {
            return;
        }
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            preferences.edit().putBoolean(HANDS_FREE_PREFERENCE, true).apply();
            render();
            listenIfNeeded();
        } else {
            notifyUser(R.string.hands_free_permission);
        }
    }

    private boolean isMyTurn() {
        return position < scene.lines.size()
            && !RehearsalOptions.shouldSpeak(mode, role, scene.lines.get(position).role);
    }

    private void listenIfNeeded() {
        if (!handsFreeEnabled() || !visible || screen != Screen.REHEARSAL || !isMyTurn()) {
            return;
        }
        heardSoFar = "";
        listenAttempts = 0;
        listeningPaused = false;
        startListening();
    }

    private void startListening() {
        if (handsFree == null) {
            handsFree = new HandsFree(this);
        }
        int target = position;
        handsFree.start(new HandsFree.Listener() {
            private boolean stale() {
                return target != position || screen != Screen.REHEARSAL || !handsFreeEnabled() || !visible;
            }

            @Override
            public void onListening() {
                if (!stale()) {
                    listening = true;
                    listeningPaused = false;
                    updateListeningPanel("");
                }
            }

            @Override
            public void onPartial(String text) {
                if (!stale()) {
                    updateListeningPanel(text);
                }
            }

            @Override
            public void onHeard(String text) {
                listening = false;
                if (stale()) {
                    return;
                }
                heardSoFar = (heardSoFar + " " + text).trim();
                double said = HandsFree.coverage(scene.lines.get(target).text, heardSoFar);
                if (said >= LINE_SAID) {
                    if (said < LINE_CLEAN) {
                        missedLines.add(target);
                    }
                    moveTo(target + 1, true);
                } else if (++listenAttempts < LISTEN_RETRIES) {
                    startListening();
                } else {
                    missedLines.add(target);
                    pauseListening();
                }
            }

            @Override
            public void onSilence() {
                listening = false;
                if (stale()) {
                    return;
                }
                if (++listenAttempts < LISTEN_RETRIES) {
                    startListening();
                } else {
                    pauseListening();
                }
            }

            @Override
            public void onFailure(boolean permanent) {
                listening = false;
                if (stale()) {
                    return;
                }
                if (permanent) {
                    preferences.edit().putBoolean(HANDS_FREE_PREFERENCE, false).apply();
                    notifyUser(R.string.hands_free_permission);
                    render();
                } else if (++listenAttempts < LISTEN_RETRIES) {
                    main.postDelayed(() -> {
                        if (!stale()) {
                            startListening();
                        }
                    }, 700);
                } else {
                    pauseListening();
                }
            }
        });
    }

    private void pauseListening() {
        listening = false;
        listeningPaused = true;
        render();
    }

    private void stopListening() {
        listening = false;
        listeningPaused = false;
        if (handsFree != null) {
            handsFree.stop();
        }
    }

    private LinearLayout listeningPanel() {
        LinearLayout panel = horizontal();
        panel.setPadding(ui.dp(16), ui.dp(12), ui.dp(8), ui.dp(12));
        panel.setBackground(ui.shape(listeningPaused ? SURFACE : ACCENT_CONTAINER, 16));
        panel.addView(ui.icon(listeningPaused ? R.drawable.ic_mic_off : R.drawable.ic_mic,
            listeningPaused ? TEXT_SECONDARY : ACCENT, 22));
        heardView = ui.text("", Type.BODY, listeningPaused ? TEXT_SECONDARY : ON_ACCENT_CONTAINER);
        heardView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(heardView, weighted(12, ui));
        if (listeningPaused) {
            panel.addView(ui.button(getString(R.string.listen_again), 0, Kind.TEXT, view -> listenIfNeeded()));
        }
        updateListeningPanel("");
        return panel;
    }

    private void updateListeningPanel(String partial) {
        if (heardView == null) {
            return;
        }
        if (listeningPaused) {
            heardView.setText(R.string.listening_paused);
            return;
        }
        String heard = (heardSoFar + " " + partial).trim();
        heardView.setText(heard.isEmpty() ? getString(R.string.listening)
            : getString(R.string.listening) + "\n" + getString(R.string.heard, heard));
    }

    // ---------------------------------------------------------------- Practice report

    private void resetReport() {
        revealedLines.clear();
        retriedLines.clear();
        missedLines.clear();
    }

    private void markRetry(int index) {
        if (mode == Mode.PRACTICE && index >= 0 && index < scene.lines.size()
            && !RehearsalOptions.shouldSpeak(mode, role, scene.lines.get(index).role)) {
            retriedLines.add(index);
        }
    }

    private Set<Integer> struggledLines() {
        Set<Integer> struggled = new TreeSet<>(revealedLines);
        struggled.addAll(retriedLines);
        struggled.addAll(missedLines);
        for (java.util.Iterator<Integer> it = struggled.iterator(); it.hasNext();) {
            if (it.next() >= scene.lines.size()) {
                it.remove();
            }
        }
        return struggled;
    }

    /** Practises only the struggled lines, each preceded by its cue line. */
    private void startReview(Set<Integer> struggled) {
        List<Integer> base = reviewBase;
        TreeSet<Integer> keep = new TreeSet<>();
        for (int index : struggled) {
            int original = base == null ? index : base.get(index);
            keep.add(original);
            if (original > 0) {
                keep.add(original - 1);
            }
        }
        reviewBase = new ArrayList<>(keep);
        scene = fullScene.forSection(sectionIndex).subset(reviewBase);
        resetReport();
        moveTo(0, true);
    }

    private int speechMessage() {
        if (activeUtterance == null && useAiVoice() && !neural.isReady()
            && neural.state() != NeuralVoice.State.FAILED) {
            return R.string.ai_voice_loading;
        }
        if (activeUtterance != null) {
            if (aiSpeaking) {
                return R.string.ai_voice_playing;
            }
            return mode == Mode.LISTEN ? R.string.reading_playing : R.string.playing;
        }
        if (playbackFailed) {
            return R.string.speech_failed;
        }
        return switch (speechState) {
            case LOADING -> R.string.speech_loading;
            case READY -> mode == Mode.LISTEN ? R.string.reading_ready : R.string.speech_ready;
            case UNAVAILABLE -> R.string.speech_unavailable;
        };
    }
}
