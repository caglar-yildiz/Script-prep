package com.scriptprep.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");
    private static final int BACKGROUND = 0xFFF8F3E9;
    private static final int INK = 0xFF24362A;
    private static final int SECONDARY = 0xFF4B5D50;
    private static final int ACCENT = 0xFF36513E;
    private static final int CARD = 0xFFFFFCF6;
    private static final int MAX_SCRIPT_LENGTH = 20_000;
    private static final int MAX_TITLE_LENGTH = 80;
    private static final int IMPORT_REQUEST = 1;
    private static final String VOICE_PREFERENCE = "tts_voice";
    private static final String EXAMPLE =
        "ECE: Prova başlamadan önce ışığı bir kez daha kontrol edelim.\n"
        + "MERT: Ben hazırım. Sahneye nereden giriyorum?\n"
        + "(Ece, sahnenin solunu gösterir.)\n"
        + "ECE: Soldan. Ben perdeyi kapattığımda içeri gir.\n"
        + "MERT: Peki ilk repliği kim söylüyor?\n"
        + "ECE: Sen. Ben yanına gelene kadar yerinde kal.\n"
        + "MERT: Tamam. Bu sefer baştan alalım.";

    private enum Screen { HOME, EDITOR, ROLES, REHEARSAL }
    private enum Tab { SCRIPTS, PREMIUM, SETTINGS }
    private enum SpeechState { LOADING, READY, UNAVAILABLE }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService imports = Executors.newSingleThreadExecutor();
    private final List<Voice> turkishVoices = new ArrayList<>();
    private SharedPreferences preferences;
    private ScriptLibrary library;
    private LinearLayout content;
    private EditText titleField;
    private EditText editor;
    private TextToSpeech speech;
    private SpeechState speechState = SpeechState.LOADING;
    private String activeUtterance;
    private int utteranceNumber;
    private boolean autoPlayPending;
    private boolean playbackFailed;
    private boolean visible;
    private String activePreview;

    private Screen screen = Screen.HOME;
    private Tab tab = Tab.SCRIPTS;
    private String scriptId;
    private String scriptTitle = "";
    private String script = "";
    private Scene scene;
    private String role;
    private int position;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("script_prep", MODE_PRIVATE);
        library = new ScriptLibrary(preferences);
        if (state != null) {
            script = state.getString("script", "");
            scriptTitle = state.getString("title", "");
            scriptId = state.getString("script_id");
            screen = Screen.valueOf(state.getString("screen", Screen.HOME.name()));
            tab = Tab.valueOf(state.getString("tab", Tab.SCRIPTS.name()));
            scene = Scene.parse(script);
            role = state.getString("role");
            position = Math.min(state.getInt("position"), scene.lines.size());
        }
        render();
        speech = new TextToSpeech(this, status -> main.post(() -> prepareSpeech(status)));
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("script", screen == Screen.EDITOR ? editor.getText().toString() : script);
        state.putString("title", screen == Screen.EDITOR ? titleField.getText().toString() : scriptTitle);
        state.putString("script_id", scriptId);
        state.putString("screen", screen.name());
        state.putString("tab", tab.name());
        state.putString("role", role);
        state.putInt("position", position);
    }

    @Override
    protected void onStop() {
        visible = false;
        cancelSpeech();
        if (screen == Screen.REHEARSAL) {
            render();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cancelSpeech();
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
        if (screen == Screen.REHEARSAL) {
            show(Screen.ROLES);
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
        if (requestCode != IMPORT_REQUEST || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        imports.execute(() -> {
            try {
                ScriptLibrary.Script imported = ScriptImport.read(getContentResolver(), uri, MAX_SCRIPT_LENGTH);
                main.post(() -> {
                    if (!isDestroyed() && screen == Screen.HOME && tab == Tab.SCRIPTS) {
                        String title = imported.title.trim();
                        openEditor(null, title.isEmpty() ? getString(R.string.imported_title)
                            : title, imported.text);
                    }
                });
            } catch (ScriptImport.LengthLimitException error) {
                main.post(() -> notifyUser(R.string.import_too_long));
            } catch (IOException | SecurityException error) {
                main.post(() -> notifyUser(R.string.import_failed));
            }
        });
    }

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
        }
    }

    private void playCurrent() {
        if (!visible || screen != Screen.REHEARSAL || position >= scene.lines.size()) {
            return;
        }
        Scene.Line line = scene.lines.get(position);
        if (line.role.equals(role)) {
            return;
        }
        if (speechState == SpeechState.LOADING) {
            autoPlayPending = true;
        } else if (speechState == SpeechState.READY) {
            cancelSpeech();
            playbackFailed = false;
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
        position = target;
        playbackFailed = false;
        if (autoPlay && position < scene.lines.size() && !scene.lines.get(position).role.equals(role)) {
            playCurrent();
        } else {
            render();
        }
    }

    private void show(Screen target) {
        cancelSpeech();
        screen = target;
        render();
    }

    private void returnToLibrary() {
        tab = Tab.SCRIPTS;
        show(Screen.HOME);
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(BACKGROUND);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(24), dp(24), dp(24));
        content.setFocusableInTouchMode(true);
        scroll.addView(content);

        if (screen == Screen.HOME) {
            root.addView(bottomNavigation());
        }
        setContentView(root);

        text(getString(R.string.app_name), 16, SECONDARY, false);
        switch (screen) {
            case HOME -> {
                switch (tab) {
                    case SCRIPTS -> renderLibrary();
                    case PREMIUM -> renderPremium();
                    case SETTINGS -> renderSettings();
                }
            }
            case EDITOR -> renderEditor();
            case ROLES -> renderRoles();
            case REHEARSAL -> renderRehearsal();
        }
    }

    private LinearLayout bottomNavigation() {
        LinearLayout navigation = new LinearLayout(this);
        navigation.setBackgroundColor(CARD);
        navigation.setPadding(dp(8), dp(5), dp(8), dp(5));
        addTab(navigation, Tab.SCRIPTS, R.string.scripts_tab);
        addTab(navigation, Tab.PREMIUM, R.string.premium_tab);
        addTab(navigation, Tab.SETTINGS, R.string.settings_tab);
        return navigation;
    }

    private void addTab(LinearLayout navigation, Tab destination, int label) {
        boolean selected = tab == destination;
        Button item = makeButton(getString(label), selected, view -> {
            if (tab != destination) {
                cancelSpeech();
                tab = destination;
                render();
            }
        });
        item.setSelected(selected);
        item.setTextSize(14);
        navigation.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void renderLibrary() {
        text(getString(R.string.scripts_tab), 34, INK, true);
        button(getString(R.string.create_script), true, view -> showCreateOptions());
        List<ScriptLibrary.Script> scripts = library.list();
        if (scripts.isEmpty()) {
            text(getString(R.string.empty_library), 16, SECONDARY, false);
        }
        for (ScriptLibrary.Script entry : scripts) {
            button(entry.title, false, view -> openSavedScript(entry));
        }
        button(getString(R.string.sample_button), false,
            view -> openSavedScript(new ScriptLibrary.Script(null, getString(R.string.sample_title), EXAMPLE)));
    }

    private void showCreateOptions() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.create_script_title)
            .setItems(new CharSequence[] {getString(R.string.import_script), getString(R.string.write_script)},
                (dialog, choice) -> {
                    if (choice == 0) {
                        openImportPicker();
                    } else {
                        openEditor(null, "", "");
                    }
                })
            .show();
    }

    @SuppressWarnings("deprecation")
    private void openImportPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, IMPORT_REQUEST);
        } catch (ActivityNotFoundException error) {
            notifyUser(R.string.import_failed);
        }
    }

    private void openSavedScript(ScriptLibrary.Script entry) {
        Scene parsed = Scene.parse(entry.text);
        if (parsed.roles.size() < 2) {
            openEditor(entry.id, entry.title, entry.text);
            return;
        }
        scriptId = entry.id;
        scriptTitle = entry.title;
        script = entry.text;
        scene = parsed;
        show(Screen.ROLES);
    }

    private void openEditor(String id, String title, String text) {
        scriptId = id;
        scriptTitle = title;
        script = text;
        show(Screen.EDITOR);
    }

    private void renderEditor() {
        text(getString(R.string.editor_title), 34, INK, true);
        titleField = editorField(R.string.script_title_label, R.string.script_title_hint, scriptTitle,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, MAX_TITLE_LENGTH);
        titleField.setSingleLine(true);

        text(getString(R.string.editor_description), 16, SECONDARY, false);
        editor = editorField(R.string.script_label, R.string.script_hint, script,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, MAX_SCRIPT_LENGTH);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setMinLines(7);
        editor.setMaxLines(12);

        button(getString(R.string.script_save_and_rehearse), true, view -> saveEditor(true));
        button(getString(R.string.script_save), false, view -> saveEditor(false));
        button(getString(R.string.return_to_library), false, view -> returnToLibrary());
        content.requestFocus();
    }

    private EditText editorField(int labelId, int hintId, String value, int inputType, int length) {
        TextView label = text(getString(labelId), 16, INK, false);
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        label.setLabelFor(field.getId());
        field.setTextColor(INK);
        field.setHintTextColor(SECONDARY);
        field.setTextSize(16);
        field.setInputType(inputType);
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(length)});
        field.setPadding(dp(16), dp(14), dp(16), dp(14));
        field.setBackground(cardBackground(CARD));
        field.setHint(hintId);
        field.setText(value);
        content.addView(field, row());
        return field;
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
        Scene parsed = Scene.parse(candidate);
        if (rehearse && parsed.roles.size() < 2) {
            editor.requestFocus();
            editor.setError(getString(R.string.invalid_scene));
            return;
        }
        scriptId = library.save(scriptId, title, candidate);
        scriptTitle = title;
        script = candidate;
        scene = parsed;
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(editor.getWindowToken(), 0);
        }
        if (rehearse) {
            show(Screen.ROLES);
        } else {
            returnToLibrary();
        }
    }

    private void renderPremium() {
        text(getString(R.string.premium_tab), 34, INK, true);
        text(getString(R.string.premium_description), 16, SECONDARY, false);
        feature(R.string.premium_voices, R.string.premium_voices_description);
        feature(R.string.premium_voice_cue, R.string.premium_voice_cue_description);
        feature(R.string.premium_review, R.string.premium_review_description);
        feature(R.string.premium_video, R.string.premium_video_description);
    }

    private void feature(int title, int description) {
        TextView heading = text(getString(title), 22, INK, true);
        heading.setPadding(0, dp(12), 0, 0);
        text(getString(description), 16, SECONDARY, false);
    }

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
        text(getString(R.string.settings_tab), 34, INK, true);
        text(getString(R.string.voice_settings), 22, INK, true);
        text(getString(R.string.voice_settings_description), 16, SECONDARY, false);
        if (speechState != SpeechState.READY) {
            text(getString(speechState == SpeechState.LOADING
                ? R.string.speech_loading : R.string.speech_unavailable), 16, SECONDARY, false);
            return;
        }

        int selected = selectedVoiceIndex();
        String current = selected == 0 ? getString(R.string.voice_default)
            : getString(R.string.voice_option, selected);
        text(getString(R.string.voice_current, current), 16, INK, false);
        if (turkishVoices.isEmpty()) {
            text(getString(R.string.voice_no_alternatives), 16, SECONDARY, false);
        } else {
            button(getString(R.string.voice_choose), false, view -> showVoiceOptions());
        }
        button(getString(R.string.voice_preview), true, view -> {
            cancelSpeech();
            activePreview = "preview-" + ++utteranceNumber;
            if (speech.speak(getString(R.string.voice_preview_text), TextToSpeech.QUEUE_FLUSH,
                null, activePreview) != TextToSpeech.SUCCESS) {
                activePreview = null;
                notifyUser(R.string.speech_failed);
            }
        });
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

    private void notifyUser(int message) {
        if (!isDestroyed()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    private void renderRoles() {
        text(scriptTitle, 16, SECONDARY, false);
        text(getString(R.string.role_title), 34, INK, true);
        for (String candidate : scene.roles) {
            button(candidate, true, view -> {
                role = candidate;
                screen = Screen.REHEARSAL;
                moveTo(0, true);
            });
        }
        button(getString(R.string.edit_scene), false, view -> show(Screen.EDITOR));
        button(getString(R.string.return_to_library), false, view -> returnToLibrary());
    }

    private void renderRehearsal() {
        if (position == scene.lines.size()) {
            text(getString(R.string.completed_title), 34, INK, true);
            button(getString(R.string.restart), true, view -> moveTo(0, true));
            if (position > 0) {
                button(getString(R.string.previous_line), false, view -> moveTo(position - 1, false));
            }
            button(getString(R.string.leave_rehearsal), false, view -> show(Screen.ROLES));
            return;
        }

        Scene.Line line = scene.lines.get(position);
        boolean myTurn = line.role.equals(role);
        text(getString(R.string.line_counter, position + 1, scene.lines.size()), 16, SECONDARY, false);
        int title = myTurn ? R.string.your_turn : R.string.partner_turn;
        text(getString(title, line.role), 29, INK, true);
        TextView dialogue = text(line.text, 25, INK, true);
        dialogue.setBackground(cardBackground(CARD));
        dialogue.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView status = text(getString(myTurn ? R.string.speak_then_continue : speechMessage()),
            16, SECONDARY, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (!myTurn && speechState == SpeechState.READY) {
            if (activeUtterance == null) {
                button(getString(R.string.play_line), false, view -> playCurrent());
            } else {
                button(getString(R.string.stop_line), false, view -> {
                    cancelSpeech();
                    render();
                });
            }
        }

        button(getString(R.string.next_line), true, view -> moveTo(position + 1, true));
        if (position > 0) {
            button(getString(R.string.previous_line), false, view -> moveTo(position - 1, false));
        }
        button(getString(R.string.leave_rehearsal), false, view -> show(Screen.ROLES));
    }

    private int speechMessage() {
        if (activeUtterance != null) {
            return R.string.playing;
        }
        if (playbackFailed) {
            return R.string.speech_failed;
        }
        return switch (speechState) {
            case LOADING -> R.string.speech_loading;
            case READY -> R.string.speech_ready;
            case UNAVAILABLE -> R.string.speech_unavailable;
        };
    }

    private TextView text(String value, int size, int color, boolean serif) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (serif) {
            view.setTypeface(Typeface.SERIF);
        }
        content.addView(view, row());
        return view;
    }

    private void button(String label, boolean primary, View.OnClickListener action) {
        content.addView(makeButton(label, primary, action), row());
    }

    private Button makeButton(String label, boolean primary, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setMinHeight(dp(48));
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? ACCENT : CARD));
        button.setTextColor(primary ? CARD : INK);
        button.setOnClickListener(action);
        return button;
    }

    private GradientDrawable cardBackground(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(16));
        return background;
    }

    private LinearLayout.LayoutParams row() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
