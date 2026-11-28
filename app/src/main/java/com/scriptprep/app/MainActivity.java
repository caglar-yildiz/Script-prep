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
import android.view.WindowManager;
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
import java.io.File;
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
    private static final int MAX_SCRIPT_LENGTH = 150_000;
    /** Longer PDFs ask which pages to import. */
    private static final int PAGE_CHOICE_FROM = 30;
    private static final int MAX_TITLE_LENGTH = 80;
    private static final int IMPORT_TEXT = 1;
    private static final int IMPORT_PDF = 2;
    private static final int EXPORT_BACKUP = 3;
    private static final int IMPORT_BACKUP = 4;
    private static final int ENSEMBLE = 5;
    private static final int IMPORT_PHOTOS = 6;
    private static final int TAKE_PHOTO = 7;
    private static final int REQUEST_CAMERA = 12;
    private static final String SPEED_PREFERENCE = "reading_speed";
    private static final float[] SPEEDS = {0.75f, 1f, 1.25f, 1.5f};
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
    private enum Screen { HOME, CATEGORIES, SOURCE, EDITOR, SECTIONS, SETUP, READER, REHEARSAL, VOICES, SETTINGS, PREMIUM, LINES, NOTES }
    private enum SpeechState { LOADING, READY, UNAVAILABLE }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService imports = Executors.newSingleThreadExecutor();
    /** Every installed device voice, in all languages. */
    private final List<Voice> installedVoices = new ArrayList<>();
    private final Set<Integer> revealedLines = new TreeSet<>();
    private final Set<Integer> retriedLines = new TreeSet<>();
    private final Set<Integer> missedLines = new TreeSet<>();
    private ArrayList<Integer> reviewBase;
    private HandsFree handsFree;
    private ToneGenerator turnCue;
    private NeuralVoice neural;
    private StudioVoices studio;
    /** Studio voice preparation of the open scene: lines ready and partner lines in all; -1 until counted. */
    /** Which voice reads the lines: one choice for device, on-device AI and studio voices. */
    private static final String VOICE_ENGINE_PREFERENCE = "voice_engine";

    private enum Engine { DEVICE, AI, STUDIO }

    private static final String NOTIFICATIONS_ASKED = "notifications_asked";
    private static final int NOTIFICATIONS_REQUEST = 4713;
    private Billing billing;
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
    private ScrollView scrollView;
    private String scrollViewKey;
    /** Download progress labels and bars, updated in place so the screen is not redrawn. */
    private final List<TextView> downloadLabels = new ArrayList<>();
    /** Studio progress texts on the setup screen, updated in place while lines are prepared. */
    private final List<TextView> studioEngineLabels = new ArrayList<>();
    private final List<TextView> studioProgressLabels = new ArrayList<>();
    /** The character voices screen was opened to check the cast before studio voices are prepared. */
    private boolean castCheck;
    private boolean guessingCast;
    private final List<LinearLayout> downloadBars = new ArrayList<>();

    private TextToSpeech speech;
    private SpeechState speechState = SpeechState.LOADING;
    private String activeUtterance;
    private int utteranceNumber;
    private boolean autoPlayPending;
    private boolean playbackFailed;
    private boolean visible;
    private boolean importing;
    /** Progress shown while scanned PDF pages are read; null while the file itself is read. */
    private String importStatus;
    private TextView importLabel;
    /** Page photos taken with the camera for the import in progress, in order. */
    private final ArrayList<Uri> takenPhotos = new ArrayList<>();
    /** Where the camera app is writing the next page photo. */
    private Uri pendingPhoto;
    /** Collects unsure lines of the import in progress. */
    private OcrReview importReview;
    /** Unsure lines of the text open in the editor, and how many of them were already checked. */
    private List<OcrReview.Item> reviewItems = Collections.emptyList();
    private int reviewIndex;
    /** Pages left after an import that reached the text limit; offered in the editor. */
    private RemainingPages remainingPages;
    /** Set from the import screen's cancel button; the import thread checks it between steps. */
    private volatile boolean importCancelled;
    private String activePreview;

    private Screen screen = Screen.HOME;
    /** Where the Premium screen goes back to: Settings, or the screen that asked for Premium. */
    private Screen premiumReturn = Screen.SETTINGS;
    private TextCategory category = TextCategory.ROLE_REHEARSAL;
    /** Language of the open text. */
    private ScriptLanguage language = ScriptLanguage.TURKISH;
    /** Whether the user chose the text language, so it is not guessed again. */
    private boolean languagePicked;
    /** Language whose default voice the settings screen is showing. */
    private ScriptLanguage settingsLanguage;
    private String scriptId;
    private String scriptTitle = "";
    private String script = "";
    private String savedTitle = "";
    private String savedScript = "";
    private Screen editorReturn = Screen.HOME;
    /** Where the line editor goes back to. */
    private Screen linesReturn = Screen.SETUP;
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
    /** Whether the line being read now is a cast member's recording. */
    private boolean recordingSpeaking;
    /** Whether the line being read now is a studio voice recording. */
    private boolean studioSpeaking;
    /** Plays a studio voice sample on the character voices screen. */
    private android.media.MediaPlayer previewPlayer;
    private android.media.MediaPlayer recordingPlayer;
    /** Cast recordings of the open shared text, loaded once per text. */
    private EnsembleRecordings.Voices castRecordings;
    private String castRecordingsFor;
    /** The text being created goes to ensemble rehearsal to be shared once it is saved. */
    private boolean shareAfterSave;

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
        studio = StudioVoices.get(this);
        neural.setListener(this::onNeuralVoiceChanged);
        neural.setProgressListener(this::updateDownloadProgress);
        billing = new Billing(this, preferences);
        billing.setListener(this::onBillingChanged);
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
            premiumReturn = Screen.valueOf(state.getString("premium_return", Screen.SETTINGS.name()));
            linesReturn = Screen.valueOf(state.getString("lines_return", Screen.SETUP.name()));
            ArrayList<Uri> photos = state.getParcelableArrayList("taken_photos");
            if (photos != null) {
                takenPhotos.addAll(photos);
            }
            pendingPhoto = state.getParcelable("pending_photo");
            category = TextCategory.fromSavedValue(state.getString("category"));
            language = ScriptLanguage.fromCode(state.getString("language"));
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
            shareAfterSave = state.getBoolean("share_after_save");
            if (screen == Screen.SETUP || screen == Screen.READER || screen == Screen.REHEARSAL
                || screen == Screen.VOICES || screen == Screen.LINES) {
                ensureRole();
            }
        }
        render();
        speech = new TextToSpeech(this, status -> main.post(() -> prepareSpeech(status)));
        if (state == null) {
            openFromIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openFromIntent(intent);
    }

    /** A shared text the ensemble screen asks to rehearse when it was opened from an invite link. */
    private void openFromIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EnsembleActivity.RESULT_ADD_TEXT, false)) {
            intent.removeExtra(EnsembleActivity.RESULT_ADD_TEXT);
            startTextForEnsemble();
        } else if (intent != null && intent.hasExtra(EnsembleActivity.RESULT_SCRIPT_ID)) {
            openEnsembleCopy(intent.getStringExtra(EnsembleActivity.RESULT_SCRIPT_ID),
                intent.getStringExtra(EnsembleActivity.RESULT_ROLE));
            intent.removeExtra(EnsembleActivity.RESULT_SCRIPT_ID);
        }
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
        // Subscriptions can change outside the app, for example a cancellation in Google Play.
        billing.connect();
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
        state.putString("language", language.code);
        state.putString("screen", screen.name());
        state.putString("premium_return", premiumReturn.name());
        state.putString("lines_return", linesReturn.name());
        // The camera app can push this activity out of memory while a page photo is taken.
        state.putParcelableArrayList("taken_photos", takenPhotos);
        state.putParcelable("pending_photo", pendingPhoto);
        state.putString("role", role);
        state.putInt("section_index", sectionIndex);
        state.putInt("position", position);
        state.putString("mode", mode.name());
        state.putString("line_visibility", lineVisibility.name());
        state.putBoolean("reveal_line", revealLine);
        state.putBoolean("share_after_save", shareAfterSave);
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
        releasePreviewPlayer();
        neural.setListener(null);
        neural.setProgressListener(null);
        billing.end();
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
        if (screen == Screen.REHEARSAL || screen == Screen.READER || screen == Screen.VOICES
            || screen == Screen.NOTES) {
            show(Screen.SETUP);
        } else if (screen == Screen.SOURCE) {
            show(Screen.CATEGORIES);
        } else if (screen == Screen.EDITOR) {
            leaveEditor();
        } else if (screen == Screen.SETUP && hasNamedSections()) {
            show(Screen.SECTIONS);
        } else if (screen == Screen.LINES) {
            show(linesReturn);
        } else if (screen == Screen.PREMIUM) {
            show(premiumReturn);
        } else if (screen != Screen.HOME) {
            returnToLibrary();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ENSEMBLE) {
            if (resultCode == RESULT_OK && data != null && data.getBooleanExtra(EnsembleActivity.RESULT_ADD_TEXT, false)) {
                startTextForEnsemble();
            } else if (resultCode == RESULT_OK && data != null) {
                openEnsembleCopy(data.getStringExtra(EnsembleActivity.RESULT_SCRIPT_ID),
                    data.getStringExtra(EnsembleActivity.RESULT_ROLE));
            }
            return;
        }
        if ((requestCode == EXPORT_BACKUP || requestCode == IMPORT_BACKUP)
            && resultCode == RESULT_OK && data != null && data.getData() != null) {
            if (requestCode == EXPORT_BACKUP) {
                writeBackup(data.getData());
            } else {
                readBackup(data.getData());
            }
            return;
        }
        if (requestCode == TAKE_PHOTO) {
            if (resultCode == RESULT_OK && pendingPhoto != null) {
                takenPhotos.add(pendingPhoto);
            }
            pendingPhoto = null;
            if (!takenPhotos.isEmpty()) {
                askForMorePhotos();
            }
            return;
        }
        if (requestCode == IMPORT_PHOTOS) {
            if (resultCode == RESULT_OK && data != null) {
                List<Uri> picked = new ArrayList<>();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        picked.add(data.getClipData().getItemAt(i).getUri());
                    }
                } else if (data.getData() != null) {
                    picked.add(data.getData());
                }
                if (picked.size() > PhotoOcr.MAX_PHOTOS) {
                    notifyUser(getString(R.string.photo_too_many, PhotoOcr.MAX_PHOTOS));
                    picked = picked.subList(0, PhotoOcr.MAX_PHOTOS);
                }
                if (!picked.isEmpty()) {
                    startPhotoImport(new ArrayList<>(picked), false);
                }
            }
            return;
        }
        if ((requestCode != IMPORT_TEXT && requestCode != IMPORT_PDF)
            || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        startImport(uri, requestCode == IMPORT_PDF, 0, 0);
    }

    /**
     * Reads a picked file. For a PDF, {@code firstPage} and {@code lastPage} (from 1) choose the
     * pages; 0 reads a short PDF whole and asks which pages of a long one to read.
     */
    private void startImport(Uri uri, boolean pdf, int firstPage, int lastPage) {
        importReview = pdf ? new OcrReview(getApplicationContext()) : null;
        TextCategory selectedCategory = category;
        String narrator = getString(R.string.solo_role);
        importing = true;
        importStatus = null;
        importCancelled = false;
        render();
        runImport(pdf, () -> {
            if (!pdf) {
                deliverImport(ScriptImport.readText(getApplicationContext(), uri, MAX_SCRIPT_LENGTH),
                    null, selectedCategory, narrator);
                return;
            }
            ScriptImport.PdfFile file = ScriptImport.openPdf(getApplicationContext(), uri, () -> importCancelled);
            int pages = file.source.pageCount;
            if (firstPage > 0) {
                readPdfPages(uri, file, Math.min(firstPage, pages), Math.min(lastPage, pages),
                    selectedCategory, narrator);
            } else if (pages > PAGE_CHOICE_FROM) {
                main.post(() -> choosePages(uri, file, selectedCategory, narrator));
            } else {
                readPdfPages(uri, file, 1, pages, selectedCategory, narrator);
            }
        });
    }

    /** The pages of an imported PDF that did not fit, to import next as a separate text. */
    private static final class RemainingPages {
        final Uri uri;
        final int firstPage;
        final int lastPage;

        RemainingPages(Uri uri, int firstPage, int lastPage) {
            this.uri = uri;
            this.firstPage = firstPage;
            this.lastPage = lastPage;
        }
    }

    /** Work that runs off the main thread while the import spinner shows. */
    private interface ImportStep {
        void run() throws IOException;
    }

    private void runImport(boolean pdf, ImportStep step) {
        runImport(pdf ? R.string.pdf_import_failed : R.string.import_failed, step);
    }

    private void runImport(int failure, ImportStep step) {
        imports.execute(() -> {
            try {
                step.run();
            } catch (ScriptImport.CancelledException error) {
                main.post(this::finishImport);
            } catch (ScriptImport.LengthLimitException error) {
                main.post(() -> failImport(R.string.import_too_long));
            } catch (PdfScriptReader.NoTextException error) {
                main.post(() -> failImport(failure == R.string.photo_import_failed
                    ? R.string.photo_no_text : R.string.pdf_no_text));
            } catch (IOException | RuntimeException | LinkageError | OutOfMemoryError error) {
                Log.w("ScriptPrep", "Could not import a script", error);
                main.post(() -> failImport(failure));
            }
        });
    }

    /** Runs on the import thread. */
    private void readPdfPages(Uri uri, ScriptImport.PdfFile file, int first, int last,
        TextCategory selectedCategory, String narrator) throws IOException {
        ScriptImport.Result result = ScriptImport.readPdf(getApplicationContext(), file, first, last,
            MAX_SCRIPT_LENGTH, (done, total) -> main.post(() -> showImportStatus(
                getString(R.string.importing_scanned, done + 1, total))), () -> importCancelled, importReview);
        deliverImport(result, uri, selectedCategory, narrator);
    }

    /** A long PDF often holds more than the play, or the play starts late: ask which pages to read. */
    private void choosePages(Uri uri, ScriptImport.PdfFile file, TextCategory selectedCategory, String narrator) {
        if (importCancelled || isDestroyed() || screen != Screen.SOURCE || category != selectedCategory) {
            file.source.close();
            finishImport();
            return;
        }
        int pages = file.source.pageCount;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        form.addView(ui.text(getString(R.string.pdf_pages_message, pages, PdfScriptReader.MAX_RANGE),
            Type.BODY, TEXT_SECONDARY));
        LinearLayout row = horizontal();
        EditText firstField = pageField(R.string.pdf_pages_first, 1);
        EditText lastField = pageField(R.string.pdf_pages_last, Math.min(pages, PdfScriptReader.MAX_RANGE));
        LinearLayout firstColumn = new LinearLayout(this);
        firstColumn.setOrientation(LinearLayout.VERTICAL);
        firstColumn.addView(ui.text(getString(R.string.pdf_pages_first), Type.LABEL, TEXT_SECONDARY));
        firstColumn.addView(firstField, ui.spaced(6));
        LinearLayout lastColumn = new LinearLayout(this);
        lastColumn.setOrientation(LinearLayout.VERTICAL);
        lastColumn.addView(ui.text(getString(R.string.pdf_pages_last), Type.LABEL, TEXT_SECONDARY));
        lastColumn.addView(lastField, ui.spaced(6));
        row.addView(firstColumn, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams lastParams = new LinearLayout.LayoutParams(0, -2, 1);
        lastParams.leftMargin = ui.dp(12);
        row.addView(lastColumn, lastParams);
        form.addView(row, ui.spaced(16));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.pdf_pages_title)
            .setView(scroll)
            .setPositiveButton(R.string.pdf_pages_import, null)
            .setNegativeButton(android.R.string.cancel, (shown, which) -> {
                file.source.close();
                finishImport();
            })
            .setOnCancelListener(shown -> {
                file.source.close();
                finishImport();
            })
            .create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            int first = pageNumber(firstField);
            int last = pageNumber(lastField);
            if (first < 1 || first > pages) {
                firstField.setError(getString(R.string.pdf_pages_invalid, pages));
                firstField.requestFocus();
                return;
            }
            if (last < first || last > pages) {
                lastField.setError(getString(R.string.pdf_pages_invalid, pages));
                lastField.requestFocus();
                return;
            }
            if (last - first + 1 > PdfScriptReader.MAX_RANGE) {
                lastField.setError(getString(R.string.pdf_pages_too_many, PdfScriptReader.MAX_RANGE));
                lastField.requestFocus();
                return;
            }
            dialog.dismiss();
            hideKeyboard();
            runImport(true, () -> readPdfPages(uri, file, first, last, selectedCategory, narrator));
        }));
        dialog.show();
    }

    private EditText pageField(int hint, int value) {
        EditText field = field(hint, String.valueOf(value), InputType.TYPE_CLASS_NUMBER, 4);
        field.setSingleLine(true);
        field.setSelectAllOnFocus(true);
        return field;
    }

    private static int pageNumber(EditText field) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    /** Runs on the import thread: parses the result and opens it in the editor. {@code pdf} is null for text. */
    private void deliverImport(ScriptImport.Result result, Uri pdf, TextCategory selectedCategory,
        String narrator) {
        boolean isPdf = pdf != null || result.photos || result.converted;
        ScriptLibrary.Script imported = result.script;
        Scene preview = isPdf ? selectedCategory.parse(imported.text, narrator) : null;
        // Only unsure lines that made it into the text are worth checking.
        List<OcrReview.Item> unsure = new ArrayList<>();
        if (importReview != null) {
            for (OcrReview.Item item : importReview.items()) {
                if (OcrText.locate(imported.text, item.text, 0) != null) {
                    unsure.add(item);
                }
            }
        }
        main.post(() -> {
            boolean wanted = !importCancelled;
            finishImport();
            if (wanted && !isDestroyed() && screen == Screen.SOURCE && category == selectedCategory) {
                reviewItems = unsure;
                reviewIndex = 0;
                remainingPages = pdf != null && result.cutShort()
                    ? new RemainingPages(pdf, result.lastPage + 1, result.requestedLastPage) : null;
                String title = imported.title.trim();
                openEditor(null, title.isEmpty() ? getString(R.string.imported_title)
                    : title, imported.text);
                savedScript = "";
                savedTitle = "";
                if (preview != null) {
                    showParsePreview(preview, importNotice(result));
                }
            }
        });
    }

    /** What the preview should say about scanned pages and pages left for another import. */
    private String importNotice(ScriptImport.Result result) {
        StringBuilder notice = new StringBuilder();
        if (result.cutShort()) {
            notice.append(getString(R.string.pdf_pages_cut_short, result.firstPage, result.lastPage,
                result.lastPage + 1, result.requestedLastPage));
        }
        if (result.scannedPages > 0) {
            if (notice.length() > 0) {
                notice.append("\n\n");
            }
            notice.append(getResources().getQuantityString(result.photos ? R.plurals.photo_pages_notice
                : R.plurals.scanned_pages_notice, result.scannedPages, result.scannedPages));
        }
        return notice.toString();
    }

    private void cancelImport() {
        importCancelled = true;
        importStatus = getString(R.string.import_cancelling);
        render();
    }

    private void showImportStatus(String status) {
        if (importCancelled) {
            return;
        }
        importStatus = status;
        if (importing && importLabel != null) {
            importLabel.setText(status);
        }
    }

    private void finishImport() {
        importing = false;
        importStatus = null;
        importLabel = null;
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
        if (status == TextToSpeech.SUCCESS) {
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
                loadVoices();
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
        } else if (screen == Screen.SETTINGS) {
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
        releaseRecordingPlayer();
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
        java.io.File recorded = castRecording(line);
        if (recorded != null && playRecording(recorded, false)) {
            return;
        }
        java.io.File directed = studioAudio(line);
        if (directed != null && playRecording(directed, true)) {
            return;
        }
        if (useAiVoice() && neural.state() != NeuralVoice.State.FAILED) {
            playWithAiVoice(line);
            return;
        }
        if (speechState == SpeechState.LOADING) {
            autoPlayPending = true;
        } else if (deviceVoiceAvailable(language)) {
            cancelSpeech();
            stopListening();
            playbackFailed = false;
            aiSpeaking = false;
            recordingSpeaking = false;
            studioSpeaking = false;
            VoiceCast.Part part = premium() ? currentCast().get(line.role) : null;
            VoiceCast.apply(speech, part, castVoices(), defaultVoice(), language.locale);
            speech.setSpeechRate((part == null ? 1f : part.pace.rate) * readingSpeed());
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
        rememberPosition();
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

    /** The cast member's recording of a line in a shared text, if one is on this device. */
    private java.io.File castRecording(Scene.Line line) {
        if (scriptId == null || !scriptId.startsWith(Ensemble.LOCAL_PREFIX)) {
            return null;
        }
        if (castRecordings == null || !scriptId.equals(castRecordingsFor)) {
            castRecordings = EnsembleRecordings.Voices.load(this,
                scriptId.substring(Ensemble.LOCAL_PREFIX.length()), fullScene);
            castRecordingsFor = scriptId;
        }
        return castRecordings.find(line);
    }

    /**
     * Plays a recorded line, a cast member's or a studio voice's; returns false when it cannot, so a
     * voice reads it instead.
     */
    private boolean playRecording(java.io.File file, boolean studioLine) {
        cancelSpeech();
        stopListening();
        playbackFailed = false;
        aiSpeaking = false;
        String id = "line-" + ++utteranceNumber;
        android.media.MediaPlayer player = new android.media.MediaPlayer();
        try {
            player.setDataSource(file.getPath());
            player.prepare();
            float speed = readingSpeed();
            if (speed != 1f) {
                player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed));
            }
        } catch (java.io.IOException | RuntimeException error) {
            Log.w("ScriptPrep", "Could not play a cast recording", error);
            player.release();
            return false;
        }
        player.setOnCompletionListener(done -> main.post(() -> {
            releaseRecordingPlayer();
            finishSpeech(id, false);
        }));
        player.setOnErrorListener((failed, what, extra) -> {
            main.post(() -> {
                releaseRecordingPlayer();
                finishSpeech(id, true);
            });
            return true;
        });
        recordingPlayer = player;
        activeUtterance = id;
        recordingSpeaking = !studioLine;
        studioSpeaking = studioLine;
        player.start();
        render();
        return true;
    }

    private void releaseRecordingPlayer() {
        if (recordingPlayer != null) {
            try {
                recordingPlayer.stop();
            } catch (RuntimeException ignored) {
                // Already finished.
            }
            recordingPlayer.release();
            recordingPlayer = null;
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
        if (target == Screen.HOME || target == Screen.CATEGORIES) {
            // Leaving the text creation screens: a text made later is not for the cast.
            shareAfterSave = false;
        }
        cancelSpeech();
        stopListening();
        if (target != Screen.REHEARSAL && reviewBase != null) {
            reviewBase = null;
            scene = fullScene.forSection(sectionIndex);
        }
        if (target != Screen.VOICES) {
            castCheck = false;
        }
        screen = target;
        render();
    }

    private void returnToLibrary() {
        show(Screen.HOME);
    }

    /** Opens the Premium screen; back returns to the screen it was opened from. */
    private void openPremium() {
        premiumReturn = screen == Screen.PREMIUM ? premiumReturn : screen;
        show(Screen.PREMIUM);
    }

    /**
     * Identifies what the screen shows. Redrawing the same view keeps the scroll position;
     * moving to another screen, text or rehearsal line starts at the top.
     */
    private String scrollKey() {
        return screen + "|" + scriptId + "|" + sectionIndex
            + (screen == Screen.REHEARSAL ? "|" + position : "");
    }

    private void render() {
        int keptScroll = scrollView != null && scrollKey().equals(scrollViewKey) ? scrollView.getScrollY() : 0;
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
        scrollView = scroll;
        scrollViewKey = scrollKey();

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
        downloadLabels.clear();
        studioEngineLabels.clear();
        studioProgressLabels.clear();
        downloadBars.clear();
        snackbarView = null;
        switch (screen) {
            case HOME -> renderLibrary();
            case SETTINGS -> renderSettings();
            case PREMIUM -> renderPremium();
            case CATEGORIES -> renderCategories();
            case SOURCE -> renderSource();
            case EDITOR -> renderEditor();
            case SECTIONS -> renderSections();
            case SETUP -> renderSetup();
            case READER -> renderReader();
            case REHEARSAL -> renderRehearsal();
            case VOICES -> renderVoices();
            case LINES -> renderLines();
            case NOTES -> renderNotes();
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
        if (keptScroll > 0) {
            scroll.post(() -> scroll.scrollTo(0, keptScroll));
        }
        keepScreenOnWhileRehearsing();
    }

    /**
     * Like a video player, keeps the screen on while a rehearsal runs, so the phone does not
     * lock in the middle of a scene; it may sleep again once the scene ends or the user leaves.
     */
    private void keepScreenOnWhileRehearsing() {
        boolean rehearsing = screen == Screen.REHEARSAL && scene != null && position < scene.lines.size();
        if (rehearsing) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
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

    private void renderLibrary() {
        LinearLayout bar = topBar(0, null, null);
        barAction(bar, R.drawable.ic_settings, R.string.settings_tab, view -> show(Screen.SETTINGS));
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
        content.addView(heading, ui.spaced(0));

        LinearLayout.LayoutParams first = ui.spaced(20);
        boolean isFirst = true;
        for (ScriptLibrary.Script entry : scripts) {
            content.addView(scriptCard(entry), isFirst ? first : ui.spaced(10));
            isFirst = false;
        }

        addEnsembleCard();
        // The library is not empty, so the sample scene is only a small link now.
        LinearLayout sample = ui.button(getString(R.string.sample_button), R.drawable.ic_play, Kind.TEXT,
            view -> openSample());
        content.addView(sample, ui.wrapSpaced(6));

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

        boolean shared = entry.id.startsWith(Ensemble.LOCAL_PREFIX);
        int[] colors = shared ? new int[] {VIOLET, VIOLET_CONTAINER} : categoryColors(entry.category);
        card.addView(ui.iconTile(shared ? R.drawable.ic_people : categoryIcon(entry.category),
            colors[0], colors[1], 48));

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
                    language = entry.language;
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
        StringBuilder summary = new StringBuilder(getString(entry.id.startsWith(Ensemble.LOCAL_PREFIX)
            ? R.string.ensemble_title : categoryTitle(entry.category)));
        if (entry.language != interfaceLanguage()) {
            summary.append(" · ").append(getString(entry.language.label));
        }
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
            getString(R.string.sample_script), TextCategory.ROLE_REHEARSAL, interfaceLanguage()));
        if (fullScene != null) {
            studio.installSample(fullScene, language);
            render();
        }
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
        if (Ensemble.available(this)) {
            content.addView(ui.button(getString(R.string.ensemble_title), R.drawable.ic_people,
                Kind.TEXT, view -> openEnsemble()), ui.spaced(0));
        }
        content.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
    }

    /** Adds a text with characters to share with the cast: paste, type or import it. */
    private void startTextForEnsemble() {
        category = TextCategory.ROLE_REHEARSAL;
        shareAfterSave = true;
        show(Screen.SOURCE);
    }

    @SuppressWarnings("deprecation")
    private void shareWithEnsemble(String id) {
        startActivityForResult(new Intent(this, EnsembleActivity.class)
            .putExtra(EnsembleActivity.EXTRA_SHARE_SCRIPT, id), ENSEMBLE);
    }

    /** Entry to ensemble rehearsal, shown when this build carries a Firebase project. */
    private void addEnsembleCard() {
        if (!Ensemble.available(this)) {
            return;
        }
        LinearLayout card = ui.card(SURFACE, view -> openEnsemble());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(ui.iconTile(R.drawable.ic_people, VIOLET, VIOLET_CONTAINER, 44));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(ui.text(getString(R.string.ensemble_title), Type.SUBTITLE, TEXT));
        TextView detail = ui.text(getString(R.string.ensemble_home_detail), Type.BODY, TEXT_TERTIARY);
        detail.setPadding(0, ui.dp(2), 0, 0);
        labels.addView(detail);
        card.addView(labels, weighted(14, ui));
        card.addView(ui.icon(R.drawable.ic_chevron_right, TEXT_TERTIARY, 22));
        content.addView(card, ui.spaced(10));
    }

    /** Settings: the ensemble identity, Google linking and sign-in, name and sign-out. */
    private void renderEnsembleAccount() {
        if (!Ensemble.available(this)) {
            return;
        }
        Ensemble ensemble = new Ensemble(this);
        EnsembleAccount.Listener redraw = new EnsembleAccount.Listener() {
            @Override
            public void busy() {}

            @Override
            public void changed(Ensemble.Connection connection) {
                if (screen == Screen.SETTINGS && !isDestroyed()) {
                    render();
                }
            }
        };
        sectionLabel(R.string.ensemble_account_section);
        content.addView(ui.text(EnsembleAccount.status(this, ensemble), Type.BODY, TEXT_SECONDARY), ui.spaced(8));
        LinearLayout account = ui.card(SURFACE, null);
        account.setPadding(0, ui.dp(4), 0, ui.dp(4));
        if (ensemble.user() == null) {
            account.addView(ui.listRow(R.drawable.ic_people, getString(R.string.ensemble_title),
                getString(R.string.ensemble_home_detail), view -> openEnsemble()), Ui.matchWidth());
            account.addView(ui.divider(70));
            account.addView(ui.listRow(R.drawable.ic_person, getString(R.string.ensemble_sign_in),
                getString(R.string.ensemble_returning), view -> EnsembleAccount.connectGoogle(this, ensemble, redraw)),
                Ui.matchWidth());
        } else {
            String name = ensemble.name();
            account.addView(ui.listRow(R.drawable.ic_person, getString(R.string.ensemble_rename),
                name.isEmpty() ? getString(R.string.ensemble_unnamed) : name,
                view -> EnsembleAccount.askName(this, ensemble, redraw)), Ui.matchWidth());
            account.addView(ui.divider(70));
            if (ensemble.linked()) {
                account.addView(ui.listRow(R.drawable.ic_close, getString(R.string.ensemble_sign_out),
                    ensemble.user().getEmail(), view -> EnsembleAccount.confirmSignOut(this, ensemble, redraw)),
                    Ui.matchWidth());
            } else {
                account.addView(ui.listRow(R.drawable.ic_people, getString(R.string.ensemble_link),
                    getString(R.string.ensemble_link_detail), view -> EnsembleAccount.connectGoogle(this, ensemble, redraw)),
                    Ui.matchWidth());
            }
        }
        content.addView(account, ui.spaced(10));
    }

    @SuppressWarnings("deprecation")
    private void openEnsemble() {
        startActivityForResult(new Intent(this, EnsembleActivity.class), ENSEMBLE);
    }

    /** Opens the local copy of a shared text, starting with the actor's first role. */
    private void openEnsembleCopy(String id, String ensembleRole) {
        if (id == null) {
            return;
        }
        // The ensemble screen has just synced; read the recordings again.
        castRecordings = null;
        for (ScriptLibrary.Script entry : library.list()) {
            if (entry.id.equals(id)) {
                if (ensembleRole != null) {
                    role = ensembleRole;
                }
                openSavedScript(entry);
                return;
            }
        }
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

    private void loadVoices() {
        Set<Voice> voices = speech.getVoices();
        if (voices != null) {
            for (Voice voice : voices) {
                Set<String> features = voice.getFeatures();
                if (voice.getLocale() != null
                    && (features == null || !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))) {
                    installedVoices.add(voice);
                }
            }
        }
        Collections.sort(installedVoices, (first, second) -> first.getName().compareTo(second.getName()));
    }

    /** Offline voices for a language, in a stable order that the settings numbering follows. */
    private List<Voice> offlineVoices(ScriptLanguage target) {
        List<Voice> voices = new ArrayList<>();
        for (Voice voice : installedVoices) {
            if (target.code.equals(voice.getLocale().getLanguage()) && !voice.isNetworkConnectionRequired()) {
                voices.add(voice);
            }
        }
        return voices;
    }

    /** Character voices for a language: offline voices first, then online ones. */
    private List<Voice> castVoices(ScriptLanguage target) {
        List<Voice> voices = offlineVoices(target);
        for (Voice voice : installedVoices) {
            if (target.code.equals(voice.getLocale().getLanguage()) && voice.isNetworkConnectionRequired()) {
                voices.add(voice);
            }
        }
        return voices;
    }

    private List<Voice> castVoices() {
        return castVoices(language);
    }

    /** Turkish keeps the original key so earlier choices survive. */
    private static String voiceKey(ScriptLanguage target) {
        return target == ScriptLanguage.TURKISH ? VOICE_PREFERENCE : VOICE_PREFERENCE + "_" + target.code;
    }

    private int selectedVoiceIndex(ScriptLanguage target) {
        String name = preferences.getString(voiceKey(target), "");
        List<Voice> voices = offlineVoices(target);
        for (int i = 0; i < voices.size(); i++) {
            if (voices.get(i).getName().equals(name)) {
                return i + 1;
            }
        }
        return 0;
    }

    private boolean deviceVoiceAvailable(ScriptLanguage target) {
        return speechState == SpeechState.READY
            && speech.isLanguageAvailable(target.locale) >= TextToSpeech.LANG_AVAILABLE;
    }

    /** The interface language mapped to a text language. */
    @SuppressWarnings("deprecation")
    private ScriptLanguage interfaceLanguage() {
        return ScriptLanguage.forLocale(getResources().getConfiguration().locale);
    }

    private void renderSettings() {
        topBar(R.drawable.ic_back, getString(R.string.settings_tab), this::returnToLibrary);

        sectionLabel(R.string.premium_tab);
        LinearLayout premiumCard = ui.card(premium() ? ACCENT_CONTAINER : SURFACE, null);
        premiumCard.setPadding(0, ui.dp(4), 0, ui.dp(4));
        premiumCard.addView(ui.listRow(R.drawable.ic_star, getString(R.string.app_name) + " " + getString(R.string.premium_tab),
            getString(premium() ? R.string.premium_active_chip : R.string.premium_see_plans),
            view -> openPremium()), Ui.matchWidth());
        content.addView(premiumCard, ui.spaced(10));

        sectionLabel(R.string.settings_general);
        LinearLayout general = ui.card(SURFACE, null);
        general.setPadding(0, ui.dp(4), 0, ui.dp(4));
        general.addView(ui.listRow(R.drawable.ic_translate, getString(R.string.language_title),
            getString(AppLanguage.current(this).label), view -> showLanguageOptions()), Ui.matchWidth());
        content.addView(general, ui.spaced(10));

        renderEnsembleAccount();

        sectionLabel(R.string.voice_settings);
        TextView explanation = ui.text(getString(R.string.voice_settings_description), Type.BODY, TEXT_SECONDARY);
        content.addView(explanation, ui.spaced(8));
        LinearLayout voice = ui.card(SURFACE, null);
        voice.setPadding(0, ui.dp(4), 0, ui.dp(4));
        if (settingsLanguage == null) {
            settingsLanguage = interfaceLanguage();
        }
        voice.addView(ui.listRow(R.drawable.ic_translate, getString(R.string.voice_language),
            getString(settingsLanguage.label), view -> chooseLanguage(settingsLanguage, picked -> {
                settingsLanguage = picked;
                render();
            })), Ui.matchWidth());
        voice.addView(ui.divider(70));
        if (!deviceVoiceAvailable(settingsLanguage)) {
            voice.addView(ui.listRow(R.drawable.ic_volume, getString(R.string.voice_settings),
                getString(speechState == SpeechState.LOADING ? R.string.speech_loading : R.string.speech_unavailable),
                null), Ui.matchWidth());
        } else {
            int selected = selectedVoiceIndex(settingsLanguage);
            String current = selected == 0 ? getString(R.string.voice_default)
                : getString(R.string.voice_option, selected);
            if (offlineVoices(settingsLanguage).isEmpty()) {
                voice.addView(ui.listRow(R.drawable.ic_volume, getString(R.string.voice_choose),
                    current + "\n" + getString(R.string.voice_no_alternatives), null), Ui.matchWidth());
            } else {
                voice.addView(ui.listRow(R.drawable.ic_volume, getString(R.string.voice_choose), current,
                    view -> showVoiceOptions()), Ui.matchWidth());
            }
            voice.addView(ui.divider(70));
            voice.addView(ui.listRow(R.drawable.ic_play, getString(R.string.voice_preview),
                settingsLanguage.sample, view -> previewVoice()), Ui.matchWidth());
        }
        content.addView(voice, ui.spaced(12));

        renderAiVoiceSettings();


        sectionLabel(R.string.backup_title);
        content.addView(ui.text(getString(R.string.backup_description), Type.BODY, TEXT_SECONDARY), ui.spaced(8));
        LinearLayout backup = ui.card(SURFACE, null);
        backup.setPadding(0, ui.dp(4), 0, ui.dp(4));
        int count = library.list().size();
        backup.addView(ui.listRow(R.drawable.ic_file, getString(R.string.backup_export),
            getResources().getQuantityString(R.plurals.library_count, count, count), view -> startBackupExport()),
            Ui.matchWidth());
        backup.addView(ui.divider(70));
        backup.addView(ui.listRow(R.drawable.ic_texts, getString(R.string.backup_import),
            getString(R.string.backup_import_detail), view -> startBackupImport()), Ui.matchWidth());
        content.addView(backup, ui.spaced(12));

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
        VoiceCast.apply(speech, null, castVoices(settingsLanguage), defaultVoice(settingsLanguage),
            settingsLanguage.locale);
        activePreview = "preview-" + ++utteranceNumber;
        if (speech.speak(settingsLanguage.sample, TextToSpeech.QUEUE_FLUSH,
            null, activePreview) != TextToSpeech.SUCCESS) {
            activePreview = null;
            notifyUser(R.string.speech_failed);
        }
    }

    private interface LanguagePick {
        void picked(ScriptLanguage value);
    }

    private void chooseLanguage(ScriptLanguage current, LanguagePick pick) {
        ScriptLanguage[] options = ScriptLanguage.values();
        String[] labels = new String[options.length];
        for (int i = 0; i < options.length; i++) {
            labels[i] = getString(options[i].label);
        }
        new AlertDialog.Builder(this).setTitle(R.string.text_language)
            .setSingleChoiceItems(labels, current.ordinal(), (dialog, index) -> {
                dialog.dismiss();
                if (options[index] != current) {
                    pick.picked(options[index]);
                }
            })
            .setNegativeButton(android.R.string.cancel, null).show();
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
        ScriptLanguage target = settingsLanguage;
        List<Voice> voices = offlineVoices(target);
        String[] labels = new String[voices.size() + 1];
        labels[0] = getString(R.string.voice_default);
        for (int i = 0; i < voices.size(); i++) {
            labels[i + 1] = getString(R.string.voice_option, i + 1);
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.voice_choose)
            .setSingleChoiceItems(labels, selectedVoiceIndex(target), (dialog, choice) -> {
                cancelSpeech();
                if (choice == 0) {
                    if (speech.setLanguage(target.locale) < TextToSpeech.LANG_AVAILABLE) {
                        notifyUser(R.string.voice_change_failed);
                        return;
                    }
                    preferences.edit().remove(voiceKey(target)).apply();
                } else {
                    Voice voice = voices.get(choice - 1);
                    if (speech.setVoice(voice) != TextToSpeech.SUCCESS) {
                        notifyUser(R.string.voice_change_failed);
                        return;
                    }
                    preferences.edit().putString(voiceKey(target), voice.getName()).apply();
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
            language = interfaceLanguage();
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
            TextView label = ui.text(importStatus == null ? getString(R.string.importing) : importStatus,
                Type.SUBTITLE, TEXT);
            importLabel = label;
            label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            loading.addView(label, weighted(16, ui));
            content.addView(loading, ui.spaced(24));
            if (!importCancelled) {
                LinearLayout cancel = ui.button(getString(android.R.string.cancel), R.drawable.ic_close,
                    Kind.TEXT, view -> cancelImport());
                content.addView(cancel, ui.wrapSpaced(8));
            }
            return;
        }

        LinearLayout first = horizontal();
        first.setGravity(Gravity.TOP);
        sourceTile(first, R.drawable.ic_pdf, R.string.create_import_pdf, R.string.source_pdf_details,
            view -> openImportPicker(true));
        sourceTile(first, R.drawable.ic_photo_camera, R.string.create_import_photo,
            R.string.source_photo_details, view -> choosePhotoSource());
        content.addView(first, ui.spaced(24));
        LinearLayout second = horizontal();
        second.setGravity(Gravity.TOP);
        sourceTile(second, R.drawable.ic_file, R.string.create_import_txt, R.string.source_txt_details,
            view -> openImportPicker(false));
        sourceTile(second, R.drawable.ic_paste, R.string.create_paste, R.string.source_paste_details,
            view -> pasteFromClipboard());
        content.addView(second, ui.spaced(12));
        LinearLayout third = horizontal();
        third.setGravity(Gravity.TOP);
        sourceTile(third, R.drawable.ic_edit, R.string.create_write, R.string.source_write_details,
            view -> openEditor(null, "", ""));
        third.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        ((LinearLayout.LayoutParams) third.getChildAt(1).getLayoutParams()).leftMargin = ui.dp(12);
        content.addView(third, ui.spaced(12));
    }

    // ---------------------------------------------------------------- Photo import

    private void choosePhotoSource() {
        String[] choices = {getString(R.string.photo_take), getString(R.string.photo_pick)};
        new AlertDialog.Builder(this)
            .setTitle(R.string.create_import_photo)
            .setItems(choices, (dialog, which) -> {
                if (which == 0) {
                    takenPhotos.clear();
                    clearPhotoFolder();
                    takePhoto();
                } else {
                    openPhotoPicker();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @SuppressWarnings("deprecation")
    private void openPhotoPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, IMPORT_PHOTOS);
        } catch (ActivityNotFoundException error) {
            notifyUser(R.string.photo_import_failed);
        }
    }

    /** The camera permission is declared for video practice, so the camera app needs it granted. */
    private void takePhoto() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA);
            return;
        }
        File folder = new File(getCacheDir(), "photos");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            notifyUser(R.string.photo_import_failed);
            return;
        }
        File file = new File(folder, "page-" + (takenPhotos.size() + 1) + "-" + System.currentTimeMillis() + ".jpg");
        Uri target = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".photos", file);
        Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, target)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(android.content.ClipData.newRawUri("", target));
        pendingPhoto = target;
        try {
            startActivityForResult(intent, TAKE_PHOTO);
        } catch (ActivityNotFoundException error) {
            pendingPhoto = null;
            notifyUser(R.string.photo_camera_missing);
        }
    }

    private void askForMorePhotos() {
        int count = takenPhotos.size();
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(getResources().getQuantityString(R.plurals.photo_taken, count, count))
            .setMessage(R.string.photo_more_message)
            .setPositiveButton(R.string.photo_read, (dialog, which) ->
                startPhotoImport(new ArrayList<>(takenPhotos), true))
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                takenPhotos.clear();
                clearPhotoFolder();
            })
            .setCancelable(false);
        if (count < PhotoOcr.MAX_PHOTOS) {
            builder.setNeutralButton(R.string.photo_more, (dialog, which) -> takePhoto());
        }
        builder.show();
    }

    private void startPhotoImport(List<Uri> photos, boolean taken) {
        importReview = new OcrReview(getApplicationContext());
        TextCategory selectedCategory = category;
        String narrator = getString(R.string.solo_role);
        String title = getString(R.string.photo_title);
        takenPhotos.clear();
        importing = true;
        importStatus = null;
        importCancelled = false;
        if (screen != Screen.SOURCE) {
            show(Screen.SOURCE);
        } else {
            render();
        }
        runImport(R.string.photo_import_failed, () -> {
            try {
                ScriptImport.Result result = ScriptImport.readPhotos(getApplicationContext(), photos, title,
                    MAX_SCRIPT_LENGTH, (done, total) -> main.post(() -> showImportStatus(
                        getString(R.string.importing_photo, done + 1, total))), () -> importCancelled,
                    importReview);
                deliverImport(result, null, selectedCategory, narrator);
            } finally {
                if (taken) {
                    clearPhotoFolder();
                }
            }
        });
    }

    private void clearPhotoFolder() {
        File[] files = new File(getCacheDir(), "photos").listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
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
            .setType(pdf ? "application/pdf" : "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!pdf) {
            // Fountain and Final Draft files often have no specific type, so any file is offered.
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"text/plain",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/xml", "text/xml", "application/octet-stream", "text/*"});
        }
        try {
            startActivityForResult(intent, pdf ? IMPORT_PDF : IMPORT_TEXT);
        } catch (ActivityNotFoundException error) {
            notifyUser(pdf ? R.string.pdf_import_failed : R.string.import_failed);
        }
    }

    private void openSavedScript(ScriptLibrary.Script entry) {
        category = entry.category;
        language = entry.language;
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
        languagePicked = id != null;
        ScriptLanguage detected = id == null ? LanguageGuess.guess(text) : null;
        if (detected != null) {
            language = detected;
        }
        show(Screen.EDITOR);
        if (detected != null && detected != interfaceLanguage()) {
            notifyUser(getString(R.string.language_detected, getString(detected.label)));
        }
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
        LinearLayout chips = horizontal();
        chips.addView(ui.chip(getString(categoryTitle(category)), categoryIcon(category), colors[0], colors[1]));
        LinearLayout languageChip = ui.chip(getString(language.label), R.drawable.ic_translate, TEXT, SURFACE_HIGHEST);
        languageChip.setBackground(ui.ripple(ui.shape(SURFACE_HIGHEST, 999), 0x22FFFFFF));
        languageChip.setClickable(true);
        languageChip.setFocusable(true);
        languageChip.setContentDescription(getString(R.string.text_language) + ": " + getString(language.label));
        languageChip.setOnClickListener(view -> chooseLanguage(language, picked -> {
            // Keep what was typed while the screen is rebuilt.
            scriptTitle = titleField.getText().toString();
            script = editor.getText().toString();
            language = picked;
            languagePicked = true;
            render();
        }));
        LinearLayout.LayoutParams languageParams = new LinearLayout.LayoutParams(-2, -2);
        languageParams.leftMargin = ui.dp(8);
        chips.addView(languageChip, languageParams);
        content.addView(chips, ui.wrapSpaced(8));

        int unsureLeft = reviewItems.size() - reviewIndex;
        if (unsureLeft > 0) {
            LinearLayout unsure = ui.card(SURFACE, null);
            unsure.addView(ui.text(getResources().getQuantityString(R.plurals.ocr_review_message, unsureLeft,
                unsureLeft), Type.BODY, TEXT), Ui.matchWidth());
            unsure.addView(ui.button(getString(R.string.ocr_review_start), R.drawable.ic_eye, Kind.TEXT,
                view -> reviewLine(reviewIndex)), ui.wrapSpaced(4));
            content.addView(unsure, ui.spaced(14));
        }

        if (remainingPages != null) {
            LinearLayout rest = ui.card(ACCENT_CONTAINER, null);
            rest.addView(ui.text(getString(R.string.pdf_rest_message, remainingPages.firstPage,
                remainingPages.lastPage), Type.BODY, ON_ACCENT_CONTAINER), Ui.matchWidth());
            rest.addView(ui.button(getString(R.string.pdf_rest_import), R.drawable.ic_pdf, Kind.TEXT,
                view -> saveAndImportRest()), ui.wrapSpaced(4));
            content.addView(rest, ui.spaced(14));
        }

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
        remainingPages = null;
        reviewItems = Collections.emptyList();
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

    /** Shows an unsure line next to its picture from the page, to correct it in the text. */
    private void reviewLine(int index) {
        if (editor == null || screen != Screen.EDITOR) {
            return;
        }
        if (index >= reviewItems.size()) {
            reviewIndex = reviewItems.size();
            notifyUser(R.string.ocr_review_done);
            script = editor.getText().toString();
            render();
            return;
        }
        reviewIndex = index;
        OcrReview.Item item = reviewItems.get(index);
        String text = editor.getText().toString();
        int cursor = Math.max(0, editor.getSelectionEnd());
        int[] found = OcrText.locate(text, item.text, cursor);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        form.addView(ui.text(getString(R.string.ocr_review_page, item.page), Type.LABEL, TEXT_SECONDARY));
        android.widget.ImageView picture = new android.widget.ImageView(this);
        picture.setAdjustViewBounds(true);
        picture.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        picture.setBackgroundColor(0xFFFFFFFF);
        picture.setImageBitmap(android.graphics.BitmapFactory.decodeFile(item.image.getPath()));
        picture.setContentDescription(getString(R.string.ocr_review_picture));
        form.addView(picture, ui.spaced(8));
        EditText lineField = field(R.string.line_text, found == null ? item.text : text.substring(found[0], found[1]),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            4000);
        lineField.setTypeface(Typeface.SERIF);
        lineField.setEnabled(found != null);
        form.addView(lineField, ui.spaced(12));
        if (found == null) {
            form.addView(ui.text(getString(R.string.ocr_review_missing), Type.BODY, TEXT_SECONDARY), ui.spaced(8));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.ocr_review_title, index + 1, reviewItems.size()))
            .setView(scroll)
            .setPositiveButton(found == null ? R.string.ocr_review_next : R.string.ocr_review_save, (dialog, which) -> {
                if (found != null) {
                    String fixed = lineField.getText().toString().trim().replaceAll("[ \\t]+", " ");
                    if (!fixed.isEmpty() && editor.getText().toString().equals(text)) {
                        editor.getText().replace(found[0], found[1], fixed);
                        editor.setSelection(Math.min(editor.length(), found[0] + fixed.length()));
                    }
                }
                reviewLine(index + 1);
            })
            .setNeutralButton(R.string.ocr_review_skip, (dialog, which) -> {
                if (found != null) {
                    editor.setSelection(Math.min(editor.length(), found[1]));
                }
                reviewLine(index + 1);
            })
            .setNegativeButton(R.string.action_close, (dialog, which) -> {
                script = editor.getText().toString();
                render();
            })
            .show();
    }

    /** Saves this part of a long PDF and imports the pages that did not fit as the next text. */
    private void saveAndImportRest() {
        RemainingPages rest = remainingPages;
        String title = titleField.getText().toString().trim();
        String candidate = editor.getText().toString().trim();
        if (rest == null || title.isEmpty() || candidate.isEmpty()) {
            saveEditor(false);
            return;
        }
        if (scriptId == null && !languagePicked) {
            ScriptLanguage detected = LanguageGuess.guess(candidate);
            if (detected != null) {
                language = detected;
            }
        }
        library.save(scriptId, title, candidate, category, language);
        notifyUser(R.string.saved);
        hideKeyboard();
        remainingPages = null;
        reviewItems = Collections.emptyList();
        scriptId = null;
        show(Screen.SOURCE);
        startImport(rest.uri, true, rest.firstPage, rest.lastPage);
    }

    private void saveEditor(boolean rehearse) {
        remainingPages = null;
        reviewItems = Collections.emptyList();
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
        if ((rehearse || shareAfterSave) && parsed.roles.isEmpty()) {
            editor.requestFocus();
            editor.setError(getString(category.hasRoles() ? R.string.invalid_scene : R.string.script_text_required));
            return;
        }
        if (scriptId == null && !languagePicked) {
            ScriptLanguage detected = LanguageGuess.guess(candidate);
            if (detected != null) {
                language = detected;
            }
        }
        scriptId = library.save(scriptId, title, candidate, category, language);
        scriptTitle = title;
        script = candidate;
        savedTitle = title;
        savedScript = candidate;
        hideKeyboard();
        if (shareAfterSave) {
            shareAfterSave = false;
            returnToLibrary();
            shareWithEnsemble(scriptId);
            return;
        }
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
        showParsePreview(parsed, "");
    }

    private void showParsePreview(Scene parsed, String notice) {
        String scanned = notice.isEmpty() ? "" : notice + "\n\n";
        if (!category.hasRoles()) {
            new AlertDialog.Builder(this).setTitle(R.string.solo_preview_title)
                .setMessage(scanned + getString(R.string.solo_preview_count, parsed.lines.size()))
                .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        StringBuilder summary = new StringBuilder(scanned).append(getString(
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
        AlertDialog.Builder preview = new AlertDialog.Builder(this).setTitle(R.string.parse_preview)
            .setMessage(summary).setPositiveButton(android.R.string.ok, null);
        if (screen == Screen.EDITOR && editor != null && !parsed.roles.isEmpty()) {
            preview.setNeutralButton(R.string.roles_edit, (dialog, which) -> editEditorRoles());
        }
        preview.show();
    }

    // ---------------------------------------------------------------- Backup file

    @SuppressWarnings("deprecation")
    private void startBackupExport() {
        String date = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "memo-everything-" + date + ".json");
        try {
            startActivityForResult(intent, EXPORT_BACKUP);
        } catch (ActivityNotFoundException error) {
            notifyUser(R.string.backup_write_failed);
        }
    }

    @SuppressWarnings("deprecation")
    private void startBackupImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/json", "text/plain",
                "application/octet-stream"});
        try {
            startActivityForResult(intent, IMPORT_BACKUP);
        } catch (ActivityNotFoundException error) {
            notifyUser(R.string.backup_read_failed);
        }
    }

    private void writeBackup(Uri target) {
        imports.execute(() -> {
            try {
                String json = library.exportJson();
                try (java.io.OutputStream output = getContentResolver().openOutputStream(target, "wt")) {
                    if (output == null) {
                        throw new IOException("No output stream");
                    }
                    output.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                int count = library.list().size();
                main.post(() -> notifyUser(getResources().getQuantityString(R.plurals.backup_exported, count, count)));
            } catch (IOException | org.json.JSONException | RuntimeException error) {
                Log.w("ScriptPrep", "Could not write a backup", error);
                main.post(() -> notifyUser(R.string.backup_write_failed));
            }
        });
    }

    private void readBackup(Uri source) {
        imports.execute(() -> {
            try {
                StringBuilder json = new StringBuilder();
                try (java.io.InputStream input = getContentResolver().openInputStream(source)) {
                    if (input == null) {
                        throw new IOException("No input stream");
                    }
                    java.io.Reader reader = new java.io.InputStreamReader(input,
                        java.nio.charset.StandardCharsets.UTF_8);
                    char[] buffer = new char[8192];
                    int read;
                    while ((read = reader.read(buffer)) != -1) {
                        json.append(buffer, 0, read);
                        if (json.length() > 64L * 1024 * 1024) {
                            throw new IOException("Backup too large");
                        }
                    }
                }
                int count = library.importJson(json.toString());
                main.post(() -> {
                    parsedCache.clear();
                    notifyUser(getResources().getQuantityString(R.plurals.backup_imported, count, count));
                    render();
                });
            } catch (IOException | org.json.JSONException | RuntimeException error) {
                Log.w("ScriptPrep", "Could not read a backup", error);
                main.post(() -> notifyUser(R.string.backup_read_failed));
            }
        });
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
        if (screen == Screen.HOME && !library.list().isEmpty()) {
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
        barAction(bar, R.drawable.ic_edit, R.string.edit_scene, view -> editLines());
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

    private void notifyUser(String message) {
        if (!isDestroyed()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
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
        barAction(bar, R.drawable.ic_edit, R.string.edit_scene, view -> editLines());
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
        resumeCard();

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
        }
        rows.add(ui.listRow(R.drawable.ic_next, getString(R.string.speed_title), speedLabel(readingSpeed()),
            view -> showSpeedOptions()));
        if (mode == Mode.PRACTICE) {
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
        addVoiceRows(extras);
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

    /** How fast partner lines are read, on top of each character's own pace. */
    private float readingSpeed() {
        float value = preferences.getFloat(SPEED_PREFERENCE, 1f);
        return value > 0.5f && value < 2f ? value : 1f;
    }

    private String speedLabel(float value) {
        int name = value < 0.9f ? R.string.speed_slow : value < 1.1f ? R.string.speed_normal
            : value < 1.4f ? R.string.speed_fast : R.string.speed_faster;
        NumberFormat format = NumberFormat.getNumberInstance();
        format.setMaximumFractionDigits(2);
        return getString(name) + " · " + format.format(value) + "×";
    }

    private void showSpeedOptions() {
        String[] labels = new String[SPEEDS.length];
        int selected = 1;
        for (int i = 0; i < SPEEDS.length; i++) {
            labels[i] = speedLabel(SPEEDS[i]);
            if (Math.abs(SPEEDS[i] - readingSpeed()) < 0.01f) {
                selected = i;
            }
        }
        new AlertDialog.Builder(this).setTitle(R.string.speed_title)
            .setSingleChoiceItems(labels, selected, (dialog, index) -> {
                dialog.dismiss();
                preferences.edit().putFloat(SPEED_PREFERENCE, SPEEDS[index]).apply();
                render();
            })
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    // ---------------------------------------------------------------- Line editor

    /** Role texts open line by line; speeches and poems keep the plain text editor. */
    private void editLines() {
        if (!category.hasRoles()) {
            editCurrent();
            return;
        }
        linesReturn = screen;
        show(Screen.LINES);
    }

    private void renderLines() {
        LinearLayout bar = topBar(R.drawable.ic_back, getString(R.string.lines_title), () -> show(linesReturn));
        barAction(bar, R.drawable.ic_people, R.string.roles_edit, view -> editSavedRoles());
        barAction(bar, R.drawable.ic_texts, R.string.edit_as_text, view -> editCurrent());
        content.addView(ui.text(getString(R.string.lines_hint), Type.BODY, TEXT_SECONDARY), ui.spaced(4));

        List<Scene.Line> lines = fullScene.lines;
        String section = "";
        for (int i = 0; i < lines.size(); i++) {
            Scene.Line line = lines.get(i);
            if (!line.section.equals(section) && !line.section.isEmpty()) {
                content.addView(ui.text(line.section, Type.OVERLINE, TEXT_TERTIARY), ui.spaced(24));
            }
            section = line.section;
            content.addView(lineCard(i, line), ui.spaced(8));
        }

        LinearLayout actions = footerBar();
        actions.addView(ui.button(getString(R.string.line_add), R.drawable.ic_add, Kind.PRIMARY,
            view -> editLine(lines.size(), null, true)), Ui.matchWidth());
    }

    private LinearLayout lineCard(int index, Scene.Line line) {
        boolean mine = line.role.equals(role);
        LinearLayout card = ui.card(mine ? ACCENT_CONTAINER : SURFACE, view -> editLine(index, line, false));
        card.setPadding(ui.dp(16), ui.dp(10), ui.dp(4), ui.dp(12));
        LinearLayout heading = horizontal();
        TextView who = ui.text(line.role, Type.LABEL, mine ? ACCENT : VIOLET);
        heading.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout more = ui.iconButton(R.drawable.ic_more, getString(R.string.text_actions, line.role),
            TEXT_SECONDARY, 0, 40, null);
        more.setOnClickListener(view -> showLineMenu(view, index, line));
        heading.addView(more);
        card.addView(heading, Ui.matchWidth());
        TextView text = ui.serif(line.text, 16, mine ? ON_ACCENT_CONTAINER : TEXT);
        text.setPadding(0, 0, ui.dp(12), 0);
        card.addView(text, Ui.matchWidth());
        for (String note : line.notes) {
            TextView direction = ui.serif(note, 14, TEXT_TERTIARY);
            direction.setTypeface(Typeface.SERIF, Typeface.ITALIC);
            direction.setPadding(0, ui.dp(4), ui.dp(12), 0);
            card.addView(direction, Ui.matchWidth());
        }
        card.setOnLongClickListener(view -> {
            showLineMenu(more, index, line);
            return true;
        });
        return card;
    }

    private void showLineMenu(View anchor, int index, Scene.Line line) {
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.END);
        menu.getMenu().add(0, 1, 0, R.string.line_edit);
        menu.getMenu().add(0, 2, 1, R.string.line_add_below);
        menu.getMenu().add(0, 3, 2, R.string.line_note_below);
        menu.getMenu().add(0, 4, 3, R.string.line_add_above);
        menu.getMenu().add(0, 5, 4, R.string.line_delete);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1 -> editLine(index, line, false);
                case 2 -> editLine(index + 1, line, true);
                case 3 -> addNote(index, line);
                case 4 -> editLine(index, line, true);
                case 5 -> deleteLine(index);
                default -> {
                    return false;
                }
            }
            return true;
        });
        menu.show();
    }

    /**
     * Opens the line dialog. With {@code insert} a new line goes in at {@code index}, taking the
     * scene of {@code near}; otherwise the line at {@code index} changes.
     */
    private void editLine(int index, Scene.Line near, boolean insert) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);

        form.addView(ui.text(getString(R.string.line_role), Type.LABEL, TEXT_SECONDARY));
        EditText roleField = field(R.string.line_role, insert ? suggestedRole(index) : near.role,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, 40);
        roleField.setSingleLine(true);
        form.addView(roleField, ui.spaced(6));
        android.widget.HorizontalScrollView roleScroll = new android.widget.HorizontalScrollView(this);
        roleScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout roles = horizontal();
        for (String name : fullScene.roles) {
            TextView option = ui.chip(name, TEXT, SURFACE_HIGHEST);
            option.setBackground(ui.ripple(ui.shape(SURFACE_HIGHEST, 999), 0x22FFFFFF));
            option.setOnClickListener(view -> {
                roleField.setText(name);
                roleField.setSelection(name.length());
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.rightMargin = ui.dp(6);
            roles.addView(option, params);
        }
        roleScroll.addView(roles);
        form.addView(roleScroll, ui.spaced(8));

        form.addView(ui.text(getString(R.string.line_text), Type.LABEL, TEXT_SECONDARY), ui.spaced(16));
        EditText textField = field(R.string.line_text, insert ? "" : near.text,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            4000);
        textField.setMinLines(3);
        textField.setGravity(Gravity.TOP | Gravity.START);
        textField.setTypeface(Typeface.SERIF);
        form.addView(textField, ui.spaced(6));

        form.addView(ui.text(getString(R.string.line_notes), Type.LABEL, TEXT_SECONDARY), ui.spaced(16));
        EditText notesField = field(R.string.line_note, insert ? "" : TextUtils.join("\n", near.notes),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            2000);
        notesField.setGravity(Gravity.TOP | Gravity.START);
        form.addView(notesField, ui.spaced(6));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(insert ? R.string.line_add : R.string.line_edit)
            .setView(scroll)
            .setPositiveButton(R.string.script_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = roleField.getText().toString().trim().replaceAll("\\s+", " ");
            String spoken = textField.getText().toString().trim().replaceAll("\\s+", " ");
            if (!Scene.isRoleName(name)) {
                roleField.setError(getString(R.string.line_role_invalid));
                roleField.requestFocus();
                return;
            }
            if (spoken.isEmpty()) {
                textField.setError(getString(R.string.line_text_required));
                textField.requestFocus();
                return;
            }
            List<String> notes = new ArrayList<>();
            for (String note : notesField.getText().toString().split("\n")) {
                if (!note.trim().isEmpty()) {
                    notes.add(note.trim());
                }
            }
            List<Scene.Line> lines = new ArrayList<>(fullScene.lines);
            String section = near != null ? near.section : lines.isEmpty() ? "" : lines.get(lines.size() - 1).section;
            Scene.Line changed = Scene.Line.of(existingRole(name), spoken, section, notes);
            if (insert) {
                lines.add(Math.max(0, Math.min(index, lines.size())), changed);
            } else {
                lines.set(index, changed);
            }
            if (saveLines(lines)) {
                dialog.dismiss();
            }
        }));
        dialog.show();
    }

    // ---------------------------------------------------------------- Characters

    /** Applies renamed lines and returns the text as parsed again, or null when it was not saved. */
    private interface RoleEdit {
        Scene apply(List<Scene.Line> lines, String from, String to);
    }

    /** Renames or merges characters in the text open in the editor, before it is saved. */
    private void editEditorRoles() {
        showRoles(parseScript(editor.getText().toString()), (lines, from, to) -> {
            String before = editor.getText().toString();
            String text = Scene.toText(lines);
            if (text.length() > MAX_SCRIPT_LENGTH) {
                notifyUser(R.string.import_too_long);
                return null;
            }
            EditText field = editor;
            field.setText(text);
            script = text;
            showSnackbar(getString(R.string.role_renamed, from, to), getString(R.string.undo), () -> {
                field.setText(before);
                script = before;
            });
            return parseScript(text);
        });
    }

    /** Renames or merges characters in the saved text; the voice of a renamed character stays. */
    private void editSavedRoles() {
        showRoles(fullScene, (lines, from, to) -> {
            String before = script;
            String previousRole = role;
            boolean merged = fullScene.roles.contains(to);
            VoiceCast.Part voice = currentCast().get(from);
            if (from.equals(role)) {
                role = to;
            }
            if (!saveLines(lines)) {
                role = previousRole;
                return null;
            }
            if (!merged && voice != null) {
                Map<String, VoiceCast.Part> cast = currentCast();
                cast.put(to, voice);
                VoiceCast.save(preferences, castKey(), cast);
            }
            showSnackbar(getString(R.string.role_renamed, from, to), getString(R.string.undo), () -> {
                role = previousRole;
                saveText(before);
            });
            return fullScene;
        });
    }

    /** Lists the characters with their line counts; tapping one renames it or merges it into another. */
    private void showRoles(Scene parsed, RoleEdit edit) {
        if (parsed == null || parsed.roles.isEmpty()) {
            notifyUser(R.string.invalid_scene);
            return;
        }
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (Scene.Line line : parsed.lines) {
            counts.merge(line.role, 1, Integer::sum);
        }
        String[] items = new String[parsed.roles.size()];
        for (int i = 0; i < items.length; i++) {
            String name = parsed.roles.get(i);
            int count = counts.getOrDefault(name, 0);
            items[i] = name + " · " + getResources().getQuantityString(R.plurals.line_count, count, count);
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.roles_edit)
            .setItems(items, (dialog, which) -> renameRole(parsed, parsed.roles.get(which), edit))
            .setNegativeButton(R.string.action_close, null)
            .show();
    }

    private void renameRole(Scene parsed, String from, RoleEdit edit) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        form.addView(ui.text(getString(R.string.role_rename_hint), Type.BODY, TEXT_SECONDARY));
        EditText nameField = field(R.string.line_role, from,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, 40);
        nameField.setSingleLine(true);
        nameField.setSelection(nameField.length());
        form.addView(nameField, ui.spaced(12));

        List<String> others = new ArrayList<>(parsed.roles);
        others.remove(from);
        if (!others.isEmpty()) {
            form.addView(ui.text(getString(R.string.role_merge_into), Type.LABEL, TEXT_SECONDARY), ui.spaced(16));
            android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = horizontal();
            for (String name : others) {
                TextView option = ui.chip(name, TEXT, SURFACE_HIGHEST);
                option.setBackground(ui.ripple(ui.shape(SURFACE_HIGHEST, 999), 0x22FFFFFF));
                option.setOnClickListener(view -> {
                    nameField.setText(name);
                    nameField.setSelection(name.length());
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
                params.rightMargin = ui.dp(6);
                chips.addView(option, params);
            }
            scroll.addView(chips);
            form.addView(scroll, ui.spaced(8));
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(from)
            .setView(form)
            .setPositiveButton(R.string.script_save, null)
            .setNegativeButton(android.R.string.cancel, (shown, which) -> showRoles(parsed, edit))
            .create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String typed = nameField.getText().toString().trim().replaceAll("\\s+", " ");
            if (!Scene.isRoleName(typed)) {
                nameField.setError(getString(R.string.line_role_invalid));
                nameField.requestFocus();
                return;
            }
            // Typing another character's name in any case merges into that character's spelling.
            String to = typed;
            for (String name : others) {
                if (name.toLowerCase(language.locale).equals(typed.toLowerCase(language.locale))) {
                    to = name;
                }
            }
            dialog.dismiss();
            hideKeyboard();
            if (to.equals(from)) {
                showRoles(parsed, edit);
                return;
            }
            List<Scene.Line> lines = new ArrayList<>();
            for (Scene.Line line : parsed.lines) {
                lines.add(line.role.equals(from)
                    ? Scene.Line.of(to, line.text, line.section, line.notes) : line);
            }
            Scene updated = edit.apply(lines, from, to);
            if (updated != null && updated.roles.size() > 1) {
                showRoles(updated, edit);
            }
        }));
        dialog.show();
    }

    /** A new line most likely belongs to whoever answers the line before it. */
    private String suggestedRole(int index) {
        List<Scene.Line> lines = fullScene.lines;
        if (index > 0 && index - 2 >= 0 && index - 2 < lines.size()) {
            return lines.get(index - 2).role;
        }
        return role == null ? "" : role;
    }

    /** Reuses the spelling of a character that already exists, whatever case was typed. */
    private String existingRole(String name) {
        for (String existing : fullScene.roles) {
            if (existing.toLowerCase(language.locale).equals(name.toLowerCase(language.locale))) {
                return existing;
            }
        }
        return name;
    }

    private void addNote(int index, Scene.Line line) {
        EditText noteField = field(R.string.line_note, "",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            2000);
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        frame.addView(noteField);
        new AlertDialog.Builder(this)
            .setTitle(R.string.line_note_below)
            .setView(frame)
            .setPositiveButton(R.string.script_save, (dialog, which) -> {
                String note = noteField.getText().toString().trim().replaceAll("\\s+", " ");
                if (note.isEmpty()) {
                    return;
                }
                List<String> notes = new ArrayList<>(line.notes);
                notes.add(note);
                List<Scene.Line> lines = new ArrayList<>(fullScene.lines);
                lines.set(index, Scene.Line.of(line.role, line.text, line.section, notes));
                saveLines(lines);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        noteField.requestFocus();
    }

    private void deleteLine(int index) {
        String before = script;
        List<Scene.Line> lines = new ArrayList<>(fullScene.lines);
        lines.remove(index);
        if (saveLines(lines)) {
            showSnackbar(getString(R.string.line_deleted), getString(R.string.undo), () -> saveText(before));
        }
    }

    /** Writes edited lines back as the text, saves it and keeps the rehearsal state in step. */
    private boolean saveLines(List<Scene.Line> lines) {
        if (lines.isEmpty()) {
            notifyUser(R.string.invalid_scene);
            return false;
        }
        return saveText(Scene.toText(lines));
    }

    private boolean saveText(String text) {
        if (text.length() > MAX_SCRIPT_LENGTH) {
            notifyUser(R.string.import_too_long);
            return false;
        }
        String title = scriptTitle.trim().isEmpty() ? getString(R.string.sample_title) : scriptTitle;
        scriptId = library.save(scriptId, title, text, category, language);
        scriptTitle = title;
        script = text;
        savedTitle = title;
        savedScript = text;
        fullScene = parseScript(text);
        if (sectionIndex >= fullScene.sections.size()) {
            sectionIndex = -1;
        }
        scene = fullScene.forSection(sectionIndex);
        reviewBase = null;
        position = Math.min(position, scene.lines.size());
        ensureRole();
        parsedCache.clear();
        render();
        return true;
    }

    // ---------------------------------------------------------------- Rehearsal navigation

    /** The next line the actor says after the current one, or -1 when there is none. */
    private int nextTurn() {
        for (int i = position + 1; i < scene.lines.size(); i++) {
            if (!RehearsalOptions.shouldSpeak(mode, role, scene.lines.get(i).role)) {
                return i;
            }
        }
        return -1;
    }

    /** Lists every line of the scene; tapping one rehearses from there. */
    private void showOverview() {
        List<Scene.Line> lines = scene.lines;
        android.widget.ListView list = new android.widget.ListView(this);
        list.setDivider(null);
        list.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(8));
        list.setClipToPadding(false);
        AlertDialog[] dialog = new AlertDialog[1];
        list.setAdapter(new android.widget.BaseAdapter() {
            @Override
            public int getCount() {
                return lines.size();
            }

            @Override
            public Object getItem(int index) {
                return lines.get(index);
            }

            @Override
            public long getItemId(int index) {
                return index;
            }

            @Override
            public View getView(int index, View reuse, android.view.ViewGroup parent) {
                Scene.Line line = lines.get(index);
                boolean mine = mode == Mode.PRACTICE && line.role.equals(role);
                boolean current = index == position;
                LinearLayout item = new LinearLayout(MainActivity.this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
                GradientDrawable shape = ui.shape(mine ? ACCENT_CONTAINER : 0x00000000, 14);
                if (current) {
                    shape.setStroke(ui.dp(2), ACCENT);
                }
                item.setBackground(shape);
                LinearLayout top = horizontal();
                top.addView(ui.text(String.valueOf(index + 1), Type.CAPTION, TEXT_TERTIARY),
                    new LinearLayout.LayoutParams(ui.dp(34), -2));
                TextView who = ui.text(line.role, Type.LABEL, mine ? ACCENT : VIOLET);
                top.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
                item.addView(top, Ui.matchWidth());
                TextView text = ui.text(line.text, Type.BODY, mine ? ON_ACCENT_CONTAINER : TEXT_SECONDARY);
                text.setMaxLines(2);
                text.setEllipsize(TextUtils.TruncateAt.END);
                text.setPadding(ui.dp(34), ui.dp(2), 0, 0);
                item.addView(text, Ui.matchWidth());
                android.widget.AbsListView.LayoutParams params = new android.widget.AbsListView.LayoutParams(-1, -2);
                item.setLayoutParams(params);
                FrameLayout spaced = new FrameLayout(MainActivity.this);
                spaced.setPadding(0, ui.dp(2), 0, ui.dp(2));
                spaced.addView(item);
                return spaced;
            }
        });
        list.setOnItemClickListener((parent, view, index, id) -> {
            if (dialog[0] != null) {
                dialog[0].dismiss();
            }
            moveTo(index, true);
        });
        dialog[0] = new AlertDialog.Builder(this)
            .setTitle(R.string.overview_title)
            .setMessage(R.string.overview_hint)
            .setView(list)
            .setNegativeButton(R.string.action_close, null)
            .show();
        list.setSelection(Math.max(0, position - 2));
    }

    // ---------------------------------------------------------------- Resume

    private static final String RESUME_PREFIX = "resume_";

    private String resumeKey() {
        return RESUME_PREFIX + (scriptId == null ? "sample" : scriptId);
    }

    /** Saves where the rehearsal is, so the text can continue from there later; a finished scene clears it. */
    private void rememberPosition() {
        if (screen != Screen.REHEARSAL || reviewBase != null) {
            return;
        }
        if (position >= scene.lines.size()) {
            preferences.edit().remove(resumeKey()).apply();
            return;
        }
        if (position <= 0) {
            // Starting over keeps the old place until the new run moves past the first line.
            return;
        }
        try {
            preferences.edit().putString(resumeKey(), new org.json.JSONObject()
                .put("section", sectionIndex)
                .put("position", position)
                .put("total", scene.lines.size())
                .put("role", role == null ? "" : role)
                .put("mode", mode.name())
                .toString()).apply();
        } catch (org.json.JSONException ignored) {
            // Resuming is a convenience; the rehearsal goes on without it.
        }
    }

    /** The saved place in this text, or null when there is none or the text no longer has it. */
    private org.json.JSONObject savedPosition() {
        String value = preferences.getString(resumeKey(), null);
        if (value == null || fullScene == null) {
            return null;
        }
        try {
            org.json.JSONObject saved = new org.json.JSONObject(value);
            int section = saved.optInt("section", -1);
            if (section >= fullScene.sections.size()) {
                return null;
            }
            int lines = fullScene.forSection(section).lines.size();
            return saved.optInt("position") > 0 && saved.optInt("position") < lines ? saved : null;
        } catch (org.json.JSONException error) {
            return null;
        }
    }

    private void resumeCard() {
        org.json.JSONObject saved = savedPosition();
        if (saved == null) {
            return;
        }
        int section = saved.optInt("section", -1);
        Scene target = fullScene.forSection(section);
        int line = saved.optInt("position");
        String savedRole = saved.optString("role");
        Mode savedMode = "LISTEN".equals(saved.optString("mode")) ? Mode.LISTEN : Mode.PRACTICE;
        StringBuilder detail = new StringBuilder(getString(R.string.line_progress, line + 1, target.lines.size()));
        if (section >= 0) {
            String title = fullScene.sections.get(section).title;
            detail.append(" · ").append(title.isEmpty() ? getString(R.string.untitled_scene, section + 1) : title);
        }
        if (category.hasRoles() && savedMode == Mode.PRACTICE && target.roles.contains(savedRole)) {
            detail.append(" · ").append(savedRole);
        }
        LinearLayout card = ui.card(ACCENT_CONTAINER, view -> {
            sectionIndex = section;
            scene = target;
            reviewBase = null;
            mode = savedMode;
            if (target.roles.contains(savedRole)) {
                role = savedRole;
            }
            ensureRole();
            resetReport();
            screen = Screen.REHEARSAL;
            moveTo(line, true);
        });
        card.setBackground(ui.ripple(ui.outlined(ACCENT_CONTAINER, 20, 0x80F4A98A), 0x22FFFFFF));
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(ui.iconTile(R.drawable.ic_play, Ui.ON_ACCENT, ACCENT, 44));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(ui.text(getString(R.string.resume_title), Type.SUBTITLE, ON_ACCENT_CONTAINER));
        TextView info = ui.text(detail.toString(), Type.BODY, ON_ACCENT_CONTAINER);
        info.setAlpha(0.8f);
        labels.addView(info, ui.spaced(2));
        TextView preview = ui.serif(target.lines.get(line).text, 14, ON_ACCENT_CONTAINER);
        preview.setAlpha(0.7f);
        preview.setMaxLines(2);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(preview, ui.spaced(4));
        card.addView(labels, weighted(14, ui));
        card.addView(ui.icon(R.drawable.ic_chevron_right, ON_ACCENT_CONTAINER, 22));
        content.addView(card, ui.spaced(12));
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
        if (total > 0) {
            barAction(bar, R.drawable.ic_list, R.string.overview_title, view -> showOverview());
        }
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
        // A long wait for the actor's next line: jump to three lines before it to hear the cues.
        int nextLine = mode == Mode.PRACTICE && !myTurn ? nextTurn() : -1;
        if (nextLine - position > 3) {
            int away = nextLine - position;
            LinearLayout skip = ui.button(getResources().getQuantityString(R.plurals.jump_to_turn, away, away),
                R.drawable.ic_next, Kind.TONAL, view -> moveTo(nextLine - 3, true));
            content.addView(skip, ui.spaced(16));
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
        if (!myTurn && (speechState == SpeechState.READY || useAiVoice()
            || (position < scene.lines.size() && (castRecording(scene.lines.get(position)) != null
                || studioAudio(scene.lines.get(position)) != null)))) {
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
            // A studio line that did not come out right can be fixed from here.
            Scene.Line heard = position < scene.lines.size() ? scene.lines.get(position) : null;
            if (heard != null && premium() && studioAudio(heard) != null) {
                FrameLayout fix = ui.iconButton(R.drawable.ic_edit, getString(R.string.fix_title), TEXT_SECONDARY, 0, 48,
                    view -> openFix(heard));
                LinearLayout.LayoutParams fixParams = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48));
                fixParams.leftMargin = ui.dp(4);
                row.addView(fix, fixParams);
            }
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
            .setPositiveButton(R.string.paywall_open, (dialog, which) -> openPremium())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void setPremium(boolean active) {
        Premium.setTesting(preferences, active);
        if (!active) {
            stopListening();
        }
        notifyUser(active ? R.string.premium_now_on : R.string.premium_now_off);
        render();
    }

    private void renderPremium() {
        boolean active = premium();
        boolean purchased = Premium.isPurchased(preferences);
        topBar(R.drawable.ic_back, getString(R.string.premium_tab), () -> show(premiumReturn));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(24));
        hero.setBackground(ui.heroWash());
        LinearLayout top = horizontal();
        top.addView(ui.iconTile(active ? R.drawable.ic_check : R.drawable.ic_star, ACCENT, ACCENT_CONTAINER, 44));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (active) {
            top.addView(ui.chip(getString(R.string.premium_active_chip), ON_ACCENT_CONTAINER, ACCENT_CONTAINER));
        }
        hero.addView(top, Ui.matchWidth());
        hero.addView(ui.text(getString(active ? R.string.premium_active_title : R.string.premium_headline),
            Type.HEADLINE, TEXT), ui.spaced(18));
        hero.addView(ui.text(getString(active ? R.string.premium_active_description : R.string.premium_description),
            Type.BODY_LARGE, TEXT_SECONDARY), ui.spaced(6));
        if (purchased) {
            hero.addView(ui.button(getString(R.string.billing_manage), 0, Kind.OUTLINE,
                view -> manageSubscription()), ui.spaced(20));
        }
        content.addView(hero, ui.spaced(20));

        if (!active) {
            renderPlans();
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

        if (Premium.canSwitchForTesting(this)) {
            boolean testing = Premium.isTesting(preferences);
            LinearLayout note = ui.card(SURFACE, null);
            LinearLayout heading = horizontal();
            heading.addView(ui.icon(R.drawable.ic_info, TEXT_SECONDARY, 20));
            TextView title = ui.text(getString(R.string.premium_test_title), Type.SUBTITLE, TEXT);
            heading.addView(title, weighted(12, ui));
            note.addView(heading, Ui.matchWidth());
            note.addView(ui.text(getString(R.string.premium_test_description), Type.BODY, TEXT_SECONDARY),
                ui.spaced(6));
            note.addView(ui.button(getString(testing ? R.string.premium_switch_off : R.string.premium_switch_on),
                0, Kind.TONAL, view -> setPremium(!testing)), ui.wrapSpaced(12));
            content.addView(note, ui.spaced(24));
        }
    }

    /** The subscription plans from Google Play, or why they cannot be shown. */
    private void renderPlans() {
        sectionLabel(R.string.premium_choose_plan);
        Billing.Status status = billing.status();
        Billing.Plan yearly = billing.plan(Billing.YEARLY);
        Billing.Plan monthly = billing.plan(Billing.MONTHLY);
        if (billing.isPending()) {
            content.addView(ui.text(getString(R.string.billing_pending), Type.BODY_LARGE, ON_ACCENT_CONTAINER),
                ui.spaced(10));
        }
        if (yearly == null && monthly == null) {
            LinearLayout card = ui.card(SURFACE, null);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            boolean loading = status == Billing.Status.CONNECTING;
            if (loading) {
                ProgressBar spinner = new ProgressBar(this);
                spinner.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
                card.addView(spinner, new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
            }
            TextView message = ui.text(getString(loading ? R.string.billing_loading : R.string.billing_unavailable),
                Type.BODY, TEXT_SECONDARY);
            card.addView(message, weighted(loading ? 14 : 0, ui));
            if (!loading) {
                card.addView(ui.button(getString(R.string.billing_retry), 0, Kind.TEXT, view -> {
                    billing.connect();
                    render();
                }));
            }
            content.addView(card, ui.spaced(10));
        } else {
            if (yearly != null) {
                content.addView(planCard(yearly, R.string.plan_yearly, true), ui.spaced(10));
            }
            if (monthly != null) {
                content.addView(planCard(monthly, R.string.plan_monthly, false), ui.spaced(10));
            }
        }
        LinearLayout restore = ui.button(getString(R.string.billing_restore), 0, Kind.TEXT, view -> {
            billing.restore();
            notifyUser(R.string.billing_loading);
        });
        content.addView(restore, ui.wrapSpaced(6));
        TextView terms = ui.text(getString(R.string.billing_terms), Type.CAPTION, TEXT_TERTIARY);
        terms.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        content.addView(terms, ui.spaced(2));
    }

    private LinearLayout planCard(Billing.Plan plan, int name, boolean highlight) {
        LinearLayout card = ui.card(highlight ? ACCENT_CONTAINER : SURFACE, view -> {
            if (!billing.purchase(this, plan)) {
                notifyUser(R.string.billing_failed);
            }
        });
        if (highlight) {
            card.setBackground(ui.ripple(ui.outlined(ACCENT_CONTAINER, 20, 0x80F4A98A), 0x22FFFFFF));
        }
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(18), ui.dp(16), ui.dp(12), ui.dp(16));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout heading = horizontal();
        heading.addView(ui.text(getString(name), Type.TITLE, highlight ? ON_ACCENT_CONTAINER : TEXT));
        if (highlight) {
            TextView best = ui.chip(getString(R.string.plan_best_value), Ui.ON_ACCENT, ACCENT);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.leftMargin = ui.dp(8);
            heading.addView(best, params);
        }
        labels.addView(heading);
        labels.addView(ui.text(priceLabel(plan), Type.BODY_LARGE, highlight ? ON_ACCENT_CONTAINER : TEXT_SECONDARY),
            ui.spaced(4));
        String trial = trialLabel(plan.trial);
        if (trial != null) {
            labels.addView(ui.text(trial, Type.LABEL, ACCENT), ui.spaced(4));
        }
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(ui.icon(R.drawable.ic_chevron_right, highlight ? ON_ACCENT_CONTAINER : TEXT_TERTIARY, 22));
        card.setContentDescription(getString(name) + ", " + priceLabel(plan) + (trial == null ? "" : ", " + trial));
        return card;
    }

    private String priceLabel(Billing.Plan plan) {
        int[] period = Billing.duration(plan.period);
        if (period == null || period[0] != 1) {
            return plan.price;
        }
        return switch ((char) period[1]) {
            case 'W' -> getString(R.string.price_per_week, plan.price);
            case 'M' -> getString(R.string.price_per_month, plan.price);
            case 'Y' -> getString(R.string.price_per_year, plan.price);
            default -> plan.price;
        };
    }

    private String trialLabel(String period) {
        int[] trial = Billing.duration(period);
        if (trial == null) {
            return null;
        }
        int amount = trial[0];
        return switch ((char) trial[1]) {
            case 'D' -> getResources().getQuantityString(R.plurals.trial_days, amount, amount);
            case 'W' -> getResources().getQuantityString(R.plurals.trial_weeks, amount, amount);
            case 'M' -> getResources().getQuantityString(R.plurals.trial_months, amount, amount);
            default -> null;
        };
    }

    private void manageSubscription() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(
            "https://play.google.com/store/account/subscriptions?sku=" + Billing.PRODUCT
                + "&package=" + getPackageName()));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            notifyUser(R.string.billing_failed);
        }
    }

    private void onBillingChanged() {
        if (!premium()) {
            stopListening();
        }
        if (screen == Screen.PREMIUM || screen == Screen.SETTINGS || screen == Screen.SETUP) {
            render();
        }
    }

    // ---------------------------------------------------------------- Character voices

    private String castKey() {
        return scriptId == null ? "sample" : scriptId;
    }

    private List<String> castVoiceNames() {
        List<String> names = new ArrayList<>();
        for (Voice voice : castVoices()) {
            names.add(voice.getName());
        }
        return names;
    }

    private Map<String, VoiceCast.Part> currentCast() {
        List<String> roles = fullScene == null ? Collections.emptyList() : fullScene.roles;
        return VoiceCast.load(preferences, castKey(), roles, castVoiceNames(), studio.genders(roles));
    }

    private Voice defaultVoice(ScriptLanguage target) {
        int index = selectedVoiceIndex(target);
        return index > 0 ? offlineVoices(target).get(index - 1) : null;
    }

    private Voice defaultVoice() {
        return defaultVoice(language);
    }

    private String voiceLabel(String name) {
        if (name.isEmpty()) {
            return getString(R.string.voice_default);
        }
        List<Voice> voices = castVoices();
        for (int i = 0; i < voices.size(); i++) {
            Voice voice = voices.get(i);
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
        // One voice reads the lines, so only its choices are shown: studio, AI or device voices.
        boolean studioCast = studioCastShown();
        boolean ai = !studioCast && useAiVoice();
        if (castCheck && studioCast) {
            LinearLayout check = ui.card(ACCENT_CONTAINER, null);
            check.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
            check.addView(ui.text(getString(R.string.cast_check_title), Type.SUBTITLE, ON_ACCENT_CONTAINER));
            check.addView(ui.text(getString(guessingCast ? R.string.cast_check_guessing : R.string.cast_check_body),
                Type.BODY, ON_ACCENT_CONTAINER), ui.spaced(4));
            content.addView(check, ui.spaced(8));
            LinearLayout actions = footerBar();
            LinearLayout confirm = ui.button(getString(R.string.cast_check_confirm), R.drawable.ic_check, Kind.PRIMARY,
                view -> confirmCastAndPrepare());
            confirm.setEnabled(!guessingCast);
            confirm.setAlpha(guessingCast ? 0.5f : 1f);
            actions.addView(confirm, Ui.matchWidth());
        }
        content.addView(ui.text(getString(studioCast ? R.string.cast_intro_studio : ai ? R.string.cast_intro_ai
            : R.string.cast_intro), Type.BODY, TEXT_SECONDARY), ui.spaced(8));
        if (!studioCast && !ai && speechState != SpeechState.READY) {
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
            LinearLayout picker = ui.button(studioCast ? studioVoiceLabel(part.speaker)
                : ai ? aiVoiceName(part.speaker) + " · " + aiVoiceGender(part.speaker)
                : voiceLabel(part.voice), ai || studioCast ? 0 : R.drawable.ic_volume, Kind.TONAL,
                view -> {
                    if (studioCast) {
                        chooseStudioVoice(character);
                    } else {
                        chooseCharacterVoice(character);
                    }
                });
            picker.setMinimumHeight(ui.dp(44));
            voiceRow.addView(picker, new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(voiceRow, ui.spaced(4));
            if (ai) {
                TextView trait = ui.text(aiVoiceTrait(part.speaker), Type.CAPTION, TEXT_TERTIARY);
                trait.setPadding(ui.dp(56), ui.dp(4), 0, 0);
                card.addView(trait, Ui.matchWidth());
            }
            if (studioCast) {
                // The director sets each line's pace, so studio voices have no tone or pace to choose.
                content.addView(card, ui.spaced(12));
                continue;
            }
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

    /** "Kore · Female · Firm": a studio voice with its gender and character. */
    private String studioVoiceLabel(int speaker) {
        String[] traits = getResources().getStringArray(R.array.studio_voice_traits);
        int index = Math.max(0, Math.min(StudioVoices.CAST_VOICES.length - 1, speaker));
        return StudioVoices.CAST_VOICES[index] + " · " + aiVoiceGender(index) + " · " + traits[index];
    }

    /**
     * Plays how a character sounds with a studio voice: their own prepared line when there is one,
     * otherwise the voice's sample that comes with the app.
     */
    private void previewStudioVoice(String character, String voice) {
        cancelSpeech();
        releasePreviewPlayer();
        android.media.MediaPlayer player = new android.media.MediaPlayer();
        try {
            java.io.File prepared = null;
            for (Scene.Line line : fullScene.lines) {
                if (line.role.equals(character) && (prepared = studio.audio(line, language, voice)) != null) {
                    break;
                }
            }
            if (prepared != null) {
                player.setDataSource(prepared.getPath());
            } else {
                try (android.content.res.AssetFileDescriptor clip = getAssets().openFd(
                    StudioVoices.previewAsset(language, voice))) {
                    player.setDataSource(clip.getFileDescriptor(), clip.getStartOffset(), clip.getLength());
                }
            }
            player.prepare();
        } catch (java.io.IOException | RuntimeException error) {
            player.release();
            notifyUser(R.string.speech_failed);
            return;
        }
        player.setOnCompletionListener(done -> releasePreviewPlayer());
        previewPlayer = player;
        player.start();
    }

    private void releasePreviewPlayer() {
        if (previewPlayer != null) {
            previewPlayer.release();
            previewPlayer = null;
        }
    }

    private void chooseStudioVoice(String character) {
        CharSequence[] voices = new CharSequence[StudioVoices.CAST_VOICES.length];
        for (int i = 0; i < voices.length; i++) {
            voices[i] = studioVoiceLabel(i);
        }
        new AlertDialog.Builder(this).setTitle(getString(R.string.cast_studio_voice_for, character))
            .setSingleChoiceItems(voices, currentCast().get(character).speaker, (dialog, index) -> {
                dialog.dismiss();
                updateCast(character, currentCast().get(character).withSpeaker(index));
                previewStudioVoice(character, StudioVoices.CAST_VOICES[index]);
            })
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseCharacterVoice(String character) {
        if (useAiVoice()) {
            CharSequence[] speakers = new CharSequence[NeuralVoice.SPEAKERS];
            for (int i = 0; i < speakers.length; i++) {
                SpannableStringBuilder item = new SpannableStringBuilder(aiVoiceName(i)).append('\n');
                int start = item.length();
                item.append(aiVoiceGender(i)).append(" · ").append(aiVoiceTrait(i));
                item.setSpan(new RelativeSizeSpan(0.82f), start, item.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                item.setSpan(new ForegroundColorSpan(TEXT_SECONDARY), start, item.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                speakers[i] = item;
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
        List<Voice> castVoices = castVoices();
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
        if (studioCastShown()) {
            previewStudioVoice(character, StudioVoices.voiceOf(currentCast().get(character)));
            return;
        }
        if (speechState != SpeechState.READY && !useAiVoice()) {
            return;
        }
        String sample = language.sample;
        for (Scene.Line line : fullScene.lines) {
            if (line.role.equals(character)) {
                sample = line.text;
                break;
            }
        }
        if (useAiVoice()) {
            VoiceCast.Part part = currentCast().get(character);
            previewAiVoice(language, part.speaker, part.pace.rate, sample);
            return;
        }
        cancelSpeech();
        VoiceCast.apply(speech, currentCast().get(character), castVoices(), defaultVoice(), language.locale);
        activePreview = "preview-" + ++utteranceNumber;
        if (speech.speak(sample, TextToSpeech.QUEUE_FLUSH, null, activePreview) != TextToSpeech.SUCCESS) {
            activePreview = null;
            notifyUser(R.string.speech_failed);
        }
    }

    // ---------------------------------------------------------------- Studio voices

    /**
     * The studio voice audio of a line, when studio voices are chosen and the line is prepared. The
     * sample scene's come with the app and play for everyone, so they can be heard before buying.
     */
    private java.io.File studioAudio(Scene.Line line) {
        if ((!premium() && scriptId != null) || fullScene == null || voiceEngine() != Engine.STUDIO) {
            return null;
        }
        return studio.audio(line, language, studioCasting().voice(line.role));
    }

    /**
     * Each character's studio voice follows their voice in the cast (female or male). Read from
     * the saved cast only, so it can be asked from the preparing thread.
     */
    private StudioVoices.Casting studioCasting() {
        List<String> roles = fullScene == null ? Collections.emptyList() : new ArrayList<>(fullScene.roles);
        String key = castKey();
        return character -> StudioVoices.voiceOf(VoiceCast.load(preferences, key, roles, Collections.emptyList(),
            studio.genders(roles)).get(character));
    }

    private Engine voiceEngine() {
        String saved = preferences.getString(VOICE_ENGINE_PREFERENCE, null);
        if (saved != null) {
            try {
                return Engine.valueOf(saved);
            } catch (IllegalArgumentException ignored) {
                // A value from a later version.
            }
        }
        // Studio voices are the default; until a scene is prepared the AI or device voice reads.
        return Engine.STUDIO;
    }

    /** Whether the studio voice choices apply here: on Premium, or in the sample scene for everyone. */
    private boolean studioCastShown() {
        return voiceEngine() == Engine.STUDIO && (premium() || scriptId == null);
    }

    /** Practice options: the one choice of voice, then what that voice needs (download, preparing). */
    private void addVoiceRows(LinearLayout extras) {
        Engine engine = voiceEngine();
        Scene voiced = studioScene();
        int total = voiced == null ? 0 : voiced.lines.size();
        int done = engine != Engine.STUDIO || fullScene == null ? 0
            : studio.isPreparing(studioJobKey()) ? studio.currentDone() : studio.readyCount(voiced, language, studioCasting());
        String value = switch (engine) {
            case DEVICE -> getString(R.string.voice_engine_device) + "\n" + getString(R.string.voice_engine_device_detail);
            case AI -> getString(R.string.ai_voice_title) + "\n" + getString(R.string.voice_engine_ai_detail);
            case STUDIO -> getString(R.string.studio_title) + "\n" + (premium() && category.hasRoles()
                ? getString(R.string.studio_ready, done, total)
                : scriptId == null && done > 0 ? getString(R.string.studio_sample)
                : getString(R.string.voice_engine_studio_detail));
        };
        LinearLayout engineRow = ui.listRow(R.drawable.ic_star, getString(R.string.voice_engine_title), value,
            view -> chooseVoiceEngine());
        if (engine == Engine.STUDIO && premium() && category.hasRoles()) {
            studioEngineLabels.add(rowDetail(engineRow));
        }
        extras.addView(engineRow, Ui.matchWidth());
        extras.addView(ui.divider(70));
        if (engine == Engine.AI && premium() && NeuralVoice.isSupported()
            && neural.state() != NeuralVoice.State.INSTALLED && neural.state() != NeuralVoice.State.LOADING
            && neural.state() != NeuralVoice.State.READY) {
            extras.addView(aiVoiceRow(), Ui.matchWidth());
            extras.addView(ui.divider(70));
        }
        if (engine != Engine.STUDIO || !category.hasRoles()) {
            return;
        }
        if (!premium()) {
            if (scriptId != null) {
                extras.addView(premiumRow(R.drawable.ic_star, R.string.studio_title, getString(R.string.studio_value),
                    null, () -> { }), Ui.matchWidth());
                extras.addView(ui.divider(70));
            }
            return;
        }
        // Only this scene's own preparation shows here; another scene's may run, or this one may wait.
        boolean preparing = studio.isPreparing(studioJobKey());
        boolean waiting = studio.isQueued(studioJobKey());
        if (preparing) {
            boolean stopping = studio.isStopping();
            LinearLayout progressRow = ui.listRow(R.drawable.ic_star, getString(R.string.studio_title),
                getString(stopping ? R.string.studio_stopping : R.string.studio_progress, done, total),
                stopping ? null : ui.button(getString(R.string.studio_stop), 0, Kind.TEXT, view -> {
                    studio.cancel();
                    render();
                }), null);
            studioProgressLabels.add(rowDetail(progressRow));
            extras.addView(progressRow, Ui.matchWidth());
            extras.addView(ui.divider(70));
        } else if (waiting) {
            extras.addView(ui.listRow(R.drawable.ic_star, getString(R.string.studio_title),
                getString(R.string.studio_waiting), null), Ui.matchWidth());
            extras.addView(ui.divider(70));
        }
        boolean idle = !preparing && !waiting;
        if (idle && done > 0) {
            extras.addView(ui.listRow(R.drawable.ic_list, getString(R.string.notes_title),
                getString(R.string.notes_value), view -> show(Screen.NOTES)), Ui.matchWidth());
            extras.addView(ui.divider(70));
        }
        if (idle && done < total) {
            String detail = done == 0 ? getString(R.string.studio_none) : getString(R.string.studio_ready, done, total);
            extras.addView(ui.listRow(R.drawable.ic_star, getString(R.string.studio_prepare),
                detail + "\n" + getString(R.string.studio_allowance), view -> startStudio()), Ui.matchWidth());
            extras.addView(ui.divider(70));
        }
    }

    // ---------------------------------------------------------------- Director's notes

    /** Every line of the scene with what the director decided and how its voice was made. */
    private void renderNotes() {
        LinearLayout bar = topBar(R.drawable.ic_back, getString(R.string.notes_title), () -> show(Screen.SETUP));
        barAction(bar, R.drawable.ic_share, R.string.notes_share, view -> shareNotes());
        content.addView(ui.text(getString(R.string.notes_intro), Type.BODY, TEXT_SECONDARY), ui.spaced(8));
        LinearLayout reading = ui.card(ACCENT_CONTAINER, null);
        reading.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(14));
        reading.addView(ui.text(getString(R.string.notes_reading), Type.SUBTITLE, ON_ACCENT_CONTAINER));
        reading.addView(ui.text(readingText(), Type.CAPTION, ON_ACCENT_CONTAINER), ui.spaced(6));
        content.addView(reading, ui.spaced(10));
        if (!studio.isPreparing(studioJobKey()) && !studio.isQueued(studioJobKey())) {
            LinearLayout actions = footerBar();
            actions.addView(ui.button(getString(R.string.notes_redirect), R.drawable.ic_replay, Kind.TONAL,
                view -> confirmRedirect()), Ui.matchWidth());
        }
        for (Scene.Line line : scene.lines) {
            LinearLayout card = ui.card(SURFACE, null);
            card.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(14));
            card.addView(ui.text(line.role, Type.LABEL, ACCENT));
            card.addView(ui.text(line.text, Type.BODY_LARGE, TEXT), ui.spaced(2));
            card.addView(ui.text(noteText(line), Type.CAPTION, TEXT_SECONDARY), ui.spaced(8));
            if (premium() && studioAudio(line) != null) {
                card.addView(ui.button(getString(R.string.fix_action), R.drawable.ic_edit, Kind.TEXT, view -> openFix(line)),
                    ui.wrapSpaced(4));
            }
            content.addView(card, ui.spaced(10));
        }
    }

    /** A line's note and voicing as plain text, for the screen and for sharing. */
    private String noteText(Scene.Line line) {
        StringBuilder text = new StringBuilder();
        for (String direction : line.notes) {
            text.append(getString(R.string.notes_script_direction, direction)).append('\n');
        }
        org.json.JSONObject note = studio.noteOf(line, language);
        if (note == null) {
            return text.append(getString(R.string.notes_not_directed)).toString();
        }
        text.append(getString(R.string.notes_delivery, note.optString("emotion"), note.optString("pace"))).append('\n');
        if (!note.optString("undertone").isEmpty()) {
            text.append(getString(R.string.notes_undertone, note.optString("undertone"))).append('\n');
        }
        if (!note.optString("note").isEmpty()) {
            text.append(getString(R.string.notes_subtext, note.optString("note"))).append('\n');
        }
        org.json.JSONArray segments = note.optJSONArray("segments");
        for (int i = 0; segments != null && i < segments.length(); i++) {
            org.json.JSONObject segment = segments.optJSONObject(i);
            if (segment == null) {
                continue;
            }
            text.append("• [").append(segment.optString("volume"));
            if (!segment.optString("style").isEmpty()) {
                text.append(", ").append(segment.optString("style"));
            }
            text.append("] ").append(segment.optString("text"));
            if (segment.optInt("pauseAfterMs") > 0) {
                text.append(" (").append(segment.optInt("pauseAfterMs")).append(" ms)");
            }
            text.append('\n');
        }
        org.json.JSONArray sayings = note.optJSONArray("pronunciations");
        for (int i = 0; sayings != null && i < sayings.length(); i++) {
            org.json.JSONObject saying = sayings.optJSONObject(i);
            if (saying != null) {
                text.append(getString(R.string.notes_pronounce, saying.optString("word"), saying.optString("say")))
                    .append('\n');
            }
        }
        String voice = studioCasting().voice(line.role);
        org.json.JSONObject voiced = studio.voicedOf(line, language, voice);
        if (voiced == null) {
            text.append(studio.audio(line, language, voice) == null ? getString(R.string.notes_not_voiced, voice)
                : getString(R.string.notes_voiced_unknown, voice));
        } else {
            text.append(getString(R.string.notes_voiced, voiced.optString("voice", voice),
                voiced.optString("model", "?"), voiced.optString("source", "?")));
            if (!voiced.optString("style").isEmpty()) {
                text.append('\n').append(getString(R.string.notes_style, voiced.optString("style")));
            }
            if (!voiced.optString("text").isEmpty()) {
                text.append('\n').append(getString(R.string.notes_sent, voiced.optString("text")));
            }
        }
        return text.toString();
    }

    /** What the director made of the play and of this scene, or why there is nothing yet. */
    private String readingText() {
        org.json.JSONObject analysis = studio.analysis(fullScene, language, StudioVoices.scriptId(castKey()));
        if (analysis == null) {
            return getString(R.string.notes_no_reading);
        }
        String context = StudioVoices.contextFor(analysis, scene.lines.isEmpty() ? "" : scene.lines.get(0).section);
        return context.isEmpty() ? getString(R.string.notes_no_reading) : context.trim();
    }

    /** Directs and voices the scene again, after the play is read again. */
    private void confirmRedirect() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.notes_redirect)
            .setMessage(R.string.notes_redirect_message)
            .setPositiveButton(R.string.notes_redirect, (dialog, which) -> {
                studio.forget(fullScene, scene, language, castKey());
                show(Screen.SETUP);
                prepareStudio();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void shareNotes() {
        StringBuilder all = new StringBuilder(getString(R.string.notes_title)).append(" · ").append(scriptTitle)
            .append(" · ").append(language.code).append("\n\n").append(getString(R.string.notes_reading))
            .append(":\n").append(readingText()).append("\n\n");
        for (Scene.Line line : scene.lines) {
            all.append(line.role).append(": ").append(line.text).append('\n').append(noteText(line)).append("\n\n");
        }
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, all.toString());
        startActivity(Intent.createChooser(send, getString(R.string.notes_share)));
    }

    // ---------------------------------------------------------------- Fixing one line

    private static final String[] FIX_WISHES = {"angrier", "calmer", "whisper", "shout", "tired", "scared", "sadder",
        "slower", "faster"};
    private static final int[] FIX_LABELS = {R.string.fix_angrier, R.string.fix_calmer, R.string.fix_whisper,
        R.string.fix_shout, R.string.fix_tired, R.string.fix_scared, R.string.fix_sadder, R.string.fix_slower,
        R.string.fix_faster};

    /** How a line is played now, in a few words: mood, undertone and each part's style. */
    private String currentReading(Scene.Line line) {
        org.json.JSONObject note = studio.noteOf(line, language);
        if (note == null) {
            return "";
        }
        StringBuilder reading = new StringBuilder(note.optString("emotion"));
        if (!note.optString("undertone").isEmpty()) {
            reading.append(" · ").append(note.optString("undertone"));
        }
        org.json.JSONArray segments = note.optJSONArray("segments");
        for (int i = 0; segments != null && i < segments.length(); i++) {
            org.json.JSONObject segment = segments.optJSONObject(i);
            if (segment != null && !segment.optString("style").isEmpty()) {
                reading.append(" · ").append(segment.optString("style"));
            }
        }
        return reading.toString();
    }

    /**
     * "Fix this line": the actor says how a studio line should change (quick wishes, own words, a
     * word said another way), hears the new take and keeps it or goes back to the old one.
     */
    private void openFix(Scene.Line line) {
        cancelSpeech();
        int left = studio.fixesLeft(line, language);
        if (left == 0) {
            notifyUser(R.string.fix_no_tries);
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        box.addView(ui.text(line.role + ": " + line.text, Type.BODY, TEXT));
        String reading = currentReading(line);
        if (!reading.isEmpty()) {
            box.addView(ui.text(getString(R.string.fix_now, reading), Type.CAPTION, TEXT_SECONDARY), ui.spaced(6));
        }
        boolean[] chosen = new boolean[FIX_WISHES.length];
        LinearLayout wishRow = null;
        for (int i = 0; i < FIX_WISHES.length; i++) {
            if (i % 3 == 0) {
                wishRow = horizontal();
                box.addView(wishRow, ui.spaced(i == 0 ? 12 : 6));
            }
            int index = i;
            TextView wish = ui.text(getString(FIX_LABELS[i]), Type.LABEL, TEXT_SECONDARY);
            wish.setGravity(Gravity.CENTER);
            wish.setMinHeight(ui.dp(38));
            wish.setBackground(ui.shape(SURFACE_HIGH, 999));
            wish.setOnClickListener(view -> {
                chosen[index] = !chosen[index];
                wish.setTextColor(chosen[index] ? Ui.ON_ACCENT : TEXT_SECONDARY);
                wish.setBackground(ui.shape(chosen[index] ? ACCENT : SURFACE_HIGH, 999));
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.rightMargin = ui.dp(6);
            wishRow.addView(wish, params);
        }
        EditText request = field(R.string.fix_request_hint, "", android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 300);
        box.addView(request, ui.spaced(12));
        LinearLayout sayRow = horizontal();
        EditText word = field(R.string.fix_word_hint, "", android.text.InputType.TYPE_CLASS_TEXT, 40);
        EditText say = field(R.string.fix_say_hint, "", android.text.InputType.TYPE_CLASS_TEXT, 60);
        sayRow.addView(word, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams sayParams = new LinearLayout.LayoutParams(0, -2, 1);
        sayParams.leftMargin = ui.dp(8);
        sayRow.addView(say, sayParams);
        box.addView(sayRow, ui.spaced(8));
        box.addView(ui.text(getString(R.string.fix_tries, left), Type.CAPTION, TEXT_TERTIARY), ui.spaced(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.fix_title).setView(scroll)
            .setPositiveButton(R.string.fix_go, null).setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            List<String> wishes = new ArrayList<>();
            for (int i = 0; i < chosen.length; i++) {
                if (chosen[i]) {
                    wishes.add(FIX_WISHES[i]);
                }
            }
            String asked = request.getText().toString().trim();
            String spoken = word.getText().toString().trim();
            String saidAs = say.getText().toString().trim();
            if (wishes.isEmpty() && asked.isEmpty() && (spoken.isEmpty() || saidAs.isEmpty())) {
                notifyUser(R.string.fix_empty);
                return;
            }
            dialog.dismiss();
            makeTake(line, wishes, asked, spoken, saidAs);
        }));
        dialog.show();
    }

    private void makeTake(Scene.Line line, List<String> wishes, String asked, String spoken, String saidAs) {
        AlertDialog working = new AlertDialog.Builder(this).setTitle(R.string.fix_title)
            .setMessage(R.string.fix_working).setNegativeButton(android.R.string.cancel, null).create();
        working.show();
        String voice = studioCasting().voice(line.role);
        studio.fix(fullScene, line, language, scriptTitle, castKey(), voice, wishes, asked, spoken, saidAs,
            new StudioVoices.TakeResult() {
                @Override
                public void ready(StudioVoices.Take take) {
                    if (!working.isShowing() || isDestroyed()) {
                        studio.discard(take);
                        return;
                    }
                    working.dismiss();
                    chooseTake(line, take);
                }

                @Override
                public void failed(StudioVoices.Stop reason) {
                    working.dismiss();
                    if (!isDestroyed()) {
                        notifyUser(reason == StudioVoices.Stop.FAILED ? R.string.fix_failed
                            : reason == StudioVoices.Stop.SCRIPT_LIMIT ? R.string.studio_script_limit
                            : R.string.studio_monthly_limit);
                    }
                }
            });
    }

    /** The new take plays at once; the actor keeps it or keeps the old one. */
    private void chooseTake(Scene.Line line, StudioVoices.Take take) {
        playPreview(take.audio);
        String reading = take.note.optString("emotion")
            + (take.note.optString("undertone").isEmpty() ? "" : " · " + take.note.optString("undertone"));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.fix_ready_title)
            .setMessage(getString(R.string.fix_now, reading))
            .setPositiveButton(R.string.fix_keep, (shown, which) -> {
                releasePreviewPlayer();
                studio.keep(take, line, language);
                render();
            })
            .setNegativeButton(R.string.fix_revert, (shown, which) -> {
                releasePreviewPlayer();
                studio.discard(take);
            })
            .setNeutralButton(R.string.fix_listen, null)
            .setCancelable(false)
            .create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            .setOnClickListener(view -> playPreview(take.audio)));
        dialog.show();
    }

    private void playPreview(java.io.File file) {
        cancelSpeech();
        releasePreviewPlayer();
        android.media.MediaPlayer player = new android.media.MediaPlayer();
        try {
            player.setDataSource(file.getPath());
            player.prepare();
        } catch (java.io.IOException | RuntimeException error) {
            player.release();
            return;
        }
        player.setOnCompletionListener(done -> releasePreviewPlayer());
        previewPlayer = player;
        player.start();
    }

    /** The value text of a list row, to change it without drawing the screen again. */
    private static TextView rowDetail(LinearLayout row) {
        LinearLayout labels = (LinearLayout) row.getChildAt(1);
        return (TextView) labels.getChildAt(labels.getChildCount() - 1);
    }

    /** Shows how many lines are ready without redrawing the setup screen, which would flicker. */
    private void updateStudioProgress(int done, int total) {
        for (TextView label : studioEngineLabels) {
            label.setText(getString(R.string.studio_title) + "\n" + getString(R.string.studio_ready, done, total));
        }
        if (!studio.isStopping()) {
            for (TextView label : studioProgressLabels) {
                label.setText(getString(R.string.studio_progress, done, total));
            }
        }
    }

    /**
     * The lines studio voices are made for: in an ensemble, lines a cast member has recorded play
     * that recording, so they are not voiced (nor paid for).
     */
    private Scene studioScene() {
        return scene == null ? null : scene.only(line -> castRecording(line) == null);
    }

    /** The scene on screen as studio voices see it: script, section and language. */
    private String studioJobKey() {
        return castKey() + "#" + sectionIndex + "#" + language.code;
    }

    private String castConfirmedKey() {
        return "studio_cast_confirmed_" + castKey();
    }

    /**
     * Preparing studio voices starts on the character voices screen: each voice is fixed once
     * prepared, so the user first checks that women and men have voices of their gender. The
     * director's gender guesses fill in the cast while the screen opens.
     */
    private void startStudio() {
        if (fullScene == null) {
            return;
        }
        if (preferences.getBoolean(castConfirmedKey(), false)) {
            confirmStudio();
            return;
        }
        if (studio.analysis(fullScene, language, StudioVoices.scriptId(castKey())) == null && !guessingCast) {
            guessingCast = true;
            studio.analyzeFirst(fullScene, language, scriptTitle, castKey(), () -> {
                guessingCast = false;
                if (screen == Screen.VOICES && !isDestroyed()) {
                    render();
                }
            });
        }
        show(Screen.VOICES);
        castCheck = true;
        render();
    }

    /** Keeps the checked cast, so later guesses cannot change a voice, and goes on to prepare. */
    private void confirmCastAndPrepare() {
        VoiceCast.save(preferences, castKey(), currentCast());
        preferences.edit().putBoolean(castConfirmedKey(), true).apply();
        show(Screen.SETUP);
        confirmStudio();
    }

    private void chooseVoiceEngine() {
        int[] titles = {R.string.voice_engine_device, R.string.ai_voice_title, R.string.studio_title};
        int[] details = {R.string.voice_engine_device_detail, R.string.voice_engine_ai_detail,
            R.string.voice_engine_studio_detail};
        CharSequence[] items = new CharSequence[titles.length];
        for (int i = 0; i < items.length; i++) {
            SpannableStringBuilder item = new SpannableStringBuilder(getString(titles[i])).append('\n');
            int start = item.length();
            item.append(getString(details[i]));
            if (i > 0 && !premium() && !(i == 2 && scriptId == null)) {
                item.append(" · ").append(getString(R.string.premium_tab));
            }
            item.setSpan(new RelativeSizeSpan(0.82f), start, item.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            item.setSpan(new ForegroundColorSpan(TEXT_SECONDARY), start, item.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            items[i] = item;
        }
        new AlertDialog.Builder(this).setTitle(R.string.voice_engine_title)
            .setSingleChoiceItems(items, voiceEngine().ordinal(), (dialog, index) -> {
                dialog.dismiss();
                pickVoiceEngine(Engine.values()[index]);
            })
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void pickVoiceEngine(Engine engine) {
        if (engine == Engine.AI) {
            if (!premium()) {
                showPaywall(R.string.ai_voice_title);
                return;
            }
            if (!NeuralVoice.isSupported()) {
                notifyUser(R.string.ai_voice_unsupported);
                return;
            }
            preferences.edit().putBoolean(AI_VOICE_PREFERENCE, true).apply();
            if (neural.state() == NeuralVoice.State.NOT_INSTALLED) {
                confirmAiDownload();
            } else {
                neural.load();
            }
        }
        if (engine == Engine.STUDIO && !premium() && scriptId != null) {
            showPaywall(R.string.studio_title);
            return;
        }
        preferences.edit().putString(VOICE_ENGINE_PREFERENCE, engine.name()).apply();
        render();
    }

    private void confirmStudio() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.studio_prepare)
            .setMessage(getString(R.string.studio_value) + "\n\n" + getString(R.string.studio_privacy))
            .setPositiveButton(R.string.studio_start, (dialog, which) -> prepareStudio())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void prepareStudio() {
        String key = studioJobKey();
        if (fullScene == null || studio.isPreparing(key) || studio.isQueued(key)) {
            return;
        }
        studio.prepare(key, fullScene, studioScene(), language, scriptTitle, castKey(), studioCasting(),
            new StudioVoices.Progress() {
            @Override
            public void progress(int done, int total) {
                // Only the screen of the scene being prepared shows its progress.
                if (screen == Screen.SETUP && !isDestroyed() && key.equals(studioJobKey())) {
                    updateStudioProgress(done, total);
                }
            }

            @Override
            public void finished(StudioVoices.Stop reason) {
                switch (reason) {
                    case DAILY_LIMIT -> notifyUser(R.string.studio_daily_limit);
                    case MONTHLY_LIMIT -> notifyUser(R.string.studio_monthly_limit);
                    case SCRIPT_LIMIT -> notifyUser(R.string.studio_script_limit);
                    case PARTIAL -> notifyUser(getString(R.string.studio_partial, studio.skippedLines()));
                    case FAILED -> notifyUser(R.string.studio_failed);
                    default -> { }
                }
                if (screen == Screen.SETUP && !isDestroyed()) {
                    render();
                }
            }
        });
        // Keeps preparing while the user is in another app, with progress in a notification.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !preferences.getBoolean(NOTIFICATIONS_ASKED, false)) {
            preferences.edit().putBoolean(NOTIFICATIONS_ASKED, true).apply();
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS_REQUEST);
        }
        StudioService.start(this, scriptTitle);
        render();
    }

    // ---------------------------------------------------------------- AI voice

    /** Whether lines are read by the on-device AI voice instead of the Android voice. */
    private boolean useAiVoice() {
        // With studio voices chosen, the AI voice (when the user has it) reads lines not yet prepared.
        if (!premium() || !NeuralVoice.isSupported() || !preferences.getBoolean(AI_VOICE_PREFERENCE, false)
            || voiceEngine() == Engine.DEVICE) {
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
        if (screen == Screen.SETTINGS || screen == Screen.VOICES || screen == Screen.SETUP
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
        recordingSpeaking = false;
        studioSpeaking = false;
        VoiceCast.Part part = currentCast().get(line.role);
        neural.speak(line.text, language.code, part == null ? 0 : part.speaker,
            (part == null ? 1f : part.pace.rate) * readingSpeed(), 1f,
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
                neural.prefetch(line.text, language.code, part == null ? 0 : part.speaker,
                    (part == null ? 1f : part.pace.rate) * readingSpeed());
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
                case DOWNLOADING -> card.addView(downloadProgress(), Ui.matchWidth());
                case FAILED -> {
                    card.addView(ui.listRow(R.drawable.ic_star, getString(R.string.ai_voice_failed),
                        getString(R.string.ai_voice_failed_detail, neural.lastError()) + "\n"
                            + getString(R.string.ai_voice_retry), view -> {
                            neural.retry();
                        }), Ui.matchWidth());
                }
                default -> {
                    Engine engine = voiceEngine();
                    card.addView(ui.listRow(R.drawable.ic_star, getString(R.string.voice_engine_title),
                        getString(engine == Engine.DEVICE ? R.string.voice_engine_device
                            : engine == Engine.AI ? R.string.ai_voice_title : R.string.studio_title),
                        view -> chooseVoiceEngine()), Ui.matchWidth());
                    card.addView(ui.divider(70));
                    String status = neural.isReady() ? getString(R.string.ai_voice_preview_detail, aiVoiceName(0))
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

    /** Premium AI voices are named and marked with a star; the names follow the app language. */
    private String aiVoiceName(int speaker) {
        String[] names = getResources().getStringArray(R.array.ai_voice_names);
        return "★ " + names[Math.max(0, Math.min(names.length - 1, speaker))];
    }

    /** Speakers 0–4 are Supertonic's female voices F1–F5, 5–9 the male voices M1–M5. */
    private String aiVoiceGender(int speaker) {
        return getString(speaker < NeuralVoice.SPEAKERS / 2 ? R.string.voice_female : R.string.voice_male);
    }

    private String aiVoiceTrait(int speaker) {
        String[] traits = getResources().getStringArray(R.array.ai_voice_traits);
        return traits[Math.max(0, Math.min(traits.length - 1, speaker))];
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
            case DOWNLOADING -> downloadProgress();
            case FAILED -> ui.listRow(R.drawable.ic_star, getString(R.string.ai_voice_failed),
                getString(R.string.ai_voice_failed_detail, neural.lastError()) + "\n"
                    + getString(R.string.ai_voice_retry), view -> neural.retry());
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

    /** Download status that updates itself in place while the files arrive. */
    private LinearLayout downloadProgress() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = ui.listRow(R.drawable.ic_star, getString(R.string.ai_voice_title),
            getString(R.string.ai_voice_keep_open),
            ui.button(getString(android.R.string.cancel), 0, Kind.TEXT, view -> neural.cancelDownload()), null);
        box.addView(row, Ui.matchWidth());
        TextView label = ui.text("", Type.LABEL, ACCENT);
        label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams labelParams = Ui.matchWidth();
        labelParams.setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(6));
        box.addView(label, labelParams);
        LinearLayout bar = ui.progress(0, 100);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, ui.dp(4));
        barParams.setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(12));
        box.addView(bar, barParams);
        downloadLabels.add(label);
        downloadBars.add(bar);
        updateDownloadProgress();
        return box;
    }

    private void updateDownloadProgress() {
        String text = neural.isWaitingForNetwork() ? getString(R.string.ai_voice_waiting)
            : getString(R.string.ai_voice_downloading, neural.progress());
        for (TextView label : downloadLabels) {
            label.setText(text);
        }
        for (LinearLayout bar : downloadBars) {
            Ui.setProgress(bar, neural.progress(), 100);
        }
    }

    private void confirmAiDownload() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.ai_voice_download)
            .setMessage(getString(R.string.ai_voice_download_message,
                Math.round(NeuralVoice.downloadBytes() / 1_000_000.0)))
            .setPositiveButton(R.string.ai_voice_download_confirm, (dialog, which) -> {
                preferences.edit().putBoolean(AI_VOICE_PREFERENCE, true)
                    .putString(VOICE_ENGINE_PREFERENCE, Engine.AI.name()).apply();
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
                SharedPreferences.Editor edit = preferences.edit().putBoolean(AI_VOICE_PREFERENCE, false);
                if (voiceEngine() == Engine.AI) {
                    edit.putString(VOICE_ENGINE_PREFERENCE, Engine.DEVICE.name());
                }
                edit.apply();
                neural.delete();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void previewAiVoice(int speaker) {
        ScriptLanguage target = settingsLanguage == null ? interfaceLanguage() : settingsLanguage;
        previewAiVoice(target, speaker, 1f, target.sample);
    }

    private void previewAiVoice(ScriptLanguage target, int speaker, float speed, String text) {
        if (!neural.isReady()) {
            neural.load();
            notifyUser(R.string.ai_voice_loading);
            return;
        }
        cancelSpeech();
        String id = "preview-" + ++utteranceNumber;
        activePreview = id;
        neural.speak(text, target.code, speaker, speed, 1f, new NeuralVoice.Playback() {
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
        startActivity(VideoActivity.intent(this, scriptTitle, scene, role, category.hasRoles(), language, cast,
            fallback == null ? "" : fallback.getName(), useAiVoice(), readingSpeed()));
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
        if (requestCode == REQUEST_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                takePhoto();
            } else {
                notifyUser(R.string.photo_camera_permission);
            }
            return;
        }
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
                double said = HandsFree.coverage(scene.lines.get(target).text, heardSoFar, language.locale);
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
        }, language.locale);
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
            if (recordingSpeaking) {
                return R.string.ensemble_recording_playing;
            }
            if (studioSpeaking) {
                return R.string.studio_playing;
            }
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
            case READY -> !deviceVoiceAvailable(language) ? R.string.speech_unavailable
                : mode == Mode.LISTEN ? R.string.reading_ready : R.string.speech_ready;
            case UNAVAILABLE -> R.string.speech_unavailable;
        };
    }
}
