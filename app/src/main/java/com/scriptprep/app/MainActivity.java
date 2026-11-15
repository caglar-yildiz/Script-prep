package com.scriptprep.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.scriptprep.app.RehearsalOptions.Mode;
import com.scriptprep.app.RehearsalOptions.Visibility;

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
    private static final int BACKGROUND = 0xFF17131C;
    private static final int INK = 0xFFF9F1EE;
    private static final int SECONDARY = 0xFFC5B7C9;
    private static final int ACCENT = 0xFFF3BDA4;
    private static final int CARD = 0xFF241D2B;
    private static final int PANEL = 0xFF34283C;
    private static final int BORDER = 0xFF58465F;
    private static final int HIGHLIGHT = 0xFFE9CDFA;
    private static final int MAX_SCRIPT_LENGTH = 150_000;
    private static final int MAX_TITLE_LENGTH = 80;
    private static final int IMPORT_TEXT = 1;
    private static final int IMPORT_PDF = 2;
    private static final String VOICE_PREFERENCE = "tts_voice";
    private static final String EXAMPLE =
        "ECE: Prova başlamadan önce ışığı bir kez daha kontrol edelim.\n"
        + "MERT: Ben hazırım. Sahneye nereden giriyorum?\n"
        + "(Ece, sahnenin solunu gösterir.)\n"
        + "ECE: Soldan. Ben perdeyi kapattığımda içeri gir.\n"
        + "MERT: Peki ilk repliği kim söylüyor?\n"
        + "ECE: Sen. Ben yanına gelene kadar yerinde kal.\n"
        + "MERT: Tamam. Bu sefer baştan alalım.";

    private enum Screen { HOME, CATEGORIES, SOURCE, EDITOR, SECTIONS, SETUP, READER, REHEARSAL }
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
    private TextCategory category = TextCategory.ROLE_REHEARSAL;
    private String scriptId;
    private String scriptTitle = "";
    private String script = "";
    private Scene fullScene;
    private Scene scene;
    private int sectionIndex = -1;
    private String role;
    private int position;
    private Mode mode = Mode.PRACTICE;
    private Visibility lineVisibility = Visibility.FULL;
    private boolean revealLine;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("script_prep", MODE_PRIVATE);
        library = new ScriptLibrary(preferences);
        if (state != null) {
            script = state.getString("script", "");
            scriptTitle = state.getString("title", "");
            scriptId = state.getString("script_id");
            String restoredScreen = state.getString("screen", Screen.HOME.name());
            screen = "ROLES".equals(restoredScreen) ? Screen.SETUP : Screen.valueOf(restoredScreen);
            tab = Tab.valueOf(state.getString("tab", Tab.SCRIPTS.name()));
            category = TextCategory.fromSavedValue(state.getString("category"));
            fullScene = parseScript(script);
            sectionIndex = state.getInt("section_index", -1);
            scene = fullScene.forSection(sectionIndex);
            role = state.getString("role");
            position = Math.min(state.getInt("position"), scene.lines.size());
            mode = Mode.valueOf(state.getString("mode", Mode.PRACTICE.name()));
            lineVisibility = Visibility.valueOf(state.getString("line_visibility", Visibility.FULL.name()));
            revealLine = state.getBoolean("reveal_line");
            if (screen == Screen.SETUP || screen == Screen.READER || screen == Screen.REHEARSAL) {
                ensureRole();
            }
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
        state.putString("category", category.name());
        state.putString("screen", screen.name());
        state.putString("tab", tab.name());
        state.putString("role", role);
        state.putInt("section_index", sectionIndex);
        state.putInt("position", position);
        state.putString("mode", mode.name());
        state.putString("line_visibility", lineVisibility.name());
        state.putBoolean("reveal_line", revealLine);
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
        if (screen == Screen.REHEARSAL || screen == Screen.READER) {
            show(Screen.SETUP);
        } else if (screen == Screen.SOURCE) {
            show(Screen.CATEGORIES);
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
        imports.execute(() -> {
            try {
                ScriptLibrary.Script imported = ScriptImport.read(getApplicationContext(), uri, pdf, MAX_SCRIPT_LENGTH);
                Scene preview = pdf ? selectedCategory.parse(imported.text, narrator) : null;
                main.post(() -> {
                    if (!isDestroyed() && screen == Screen.SOURCE && category == selectedCategory) {
                        String title = imported.title.trim();
                        openEditor(null, title.isEmpty() ? getString(R.string.imported_title)
                            : title, imported.text);
                        if (preview != null) {
                            showParsePreview(preview);
                        }
                    }
                });
            } catch (ScriptImport.LengthLimitException error) {
                main.post(() -> notifyUser(R.string.import_too_long));
            } catch (PdfScriptReader.NoTextException error) {
                main.post(() -> notifyUser(R.string.pdf_no_text));
            } catch (IOException | RuntimeException | LinkageError error) {
                Log.w("ScriptPrep", "Could not import a script", error);
                main.post(() -> notifyUser(pdf ? R.string.pdf_import_failed : R.string.import_failed));
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
        if (!RehearsalOptions.shouldSpeak(mode, role, line.role)) {
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
        revealLine = false;
        playbackFailed = false;
        if (autoPlay && position < scene.lines.size()
            && RehearsalOptions.shouldSpeak(mode, role, scene.lines.get(position).role)) {
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
        } else if (screen == Screen.SETUP) {
            root.addView(setupActions());
        }
        setContentView(root);

        if (screen != Screen.SETUP && screen != Screen.READER
            && screen != Screen.CATEGORIES && screen != Screen.SOURCE
            && (screen != Screen.HOME || tab != Tab.SCRIPTS)) {
            text(getString(R.string.app_name), 16, HIGHLIGHT, false);
        }
        switch (screen) {
            case HOME -> {
                switch (tab) {
                    case SCRIPTS -> renderLibrary();
                    case PREMIUM -> renderPremium();
                    case SETTINGS -> renderSettings();
                }
            }
            case CATEGORIES -> renderCategories();
            case SOURCE -> renderSource();
            case EDITOR -> renderEditor();
            case SECTIONS -> renderSections();
            case SETUP -> renderSetup();
            case READER -> renderReader();
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
        List<ScriptLibrary.Script> scripts = library.list();
        if (scripts.isEmpty()) {
            renderEmptyLibrary();
            return;
        }

        text(getString(R.string.app_name), 16, HIGHLIGHT, false);
        text(getString(R.string.scripts_tab), 34, INK, true);
        button(getString(R.string.create_script), true, view -> show(Screen.CATEGORIES));
        for (ScriptLibrary.Script entry : scripts) {
            button(entry.title, false, view -> openSavedScript(entry));
        }
        button(getString(R.string.sample_button), false,
            view -> openSavedScript(new ScriptLibrary.Script(null, getString(R.string.sample_title), EXAMPLE)));
    }

    private void renderEmptyLibrary() {
        content.setGravity(Gravity.CENTER);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.memo_badge);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(logo, new LinearLayout.LayoutParams(dp(120), dp(120)));

        TextView title = text(getString(R.string.app_name), 40, INK, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(12), 0, 0);

        TextView tagline = text(getString(R.string.memo_tagline), 20, SECONDARY, true);
        tagline.setGravity(Gravity.CENTER);
        tagline.setTypeface(Typeface.SERIF, Typeface.ITALIC);

        Button create = makeButton(getString(R.string.create_first_text), true, view -> show(Screen.CATEGORIES));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, -2);
        layout.topMargin = dp(24);
        content.addView(create, layout);
    }

    private void renderCategories() {
        newTextHeader(R.string.choose_text_category, this::returnToLibrary);
        categoryOption(TextCategory.ROLE_REHEARSAL, R.string.category_role_details);
        categoryOption(TextCategory.SPEECH, R.string.category_speech_details);
        categoryOption(TextCategory.POEM_OR_MONOLOGUE, R.string.category_poem_details);
    }

    private void categoryOption(TextCategory value, int detail) {
        button(getString(categoryTitle(value)), false, view -> {
            category = value;
            show(Screen.SOURCE);
        });
        TextView caption = text(getString(detail), 14, SECONDARY, false);
        caption.setPadding(dp(12), 0, dp(12), dp(8));
    }

    private void renderSource() {
        newTextHeader(R.string.choose_text_source, () -> show(Screen.CATEGORIES));
        text(getString(categoryTitle(category)), 16, HIGHLIGHT, false);
        button(getString(R.string.create_import_pdf), false, view -> openImportPicker(true));
        button(getString(R.string.create_import_txt), false, view -> openImportPicker(false));
        button(getString(R.string.create_paste), false, view -> pasteFromClipboard());
        button(getString(R.string.create_write), false, view -> openEditor(null, "", ""));
    }

    private void newTextHeader(int title, Runnable goBack) {
        Button back = makeButton(getString(R.string.create_back), false, view -> goBack.run());
        content.addView(back, new LinearLayout.LayoutParams(-2, -2));
        text(getString(title), 34, INK, true);
    }

    private int categoryTitle(TextCategory selected) {
        return switch (selected) {
            case ROLE_REHEARSAL -> R.string.category_role;
            case SPEECH -> R.string.category_speech;
            case POEM_OR_MONOLOGUE -> R.string.category_poem;
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

        int description = category.hasRoles() ? R.string.editor_description : R.string.solo_description;
        int hint = category.hasRoles() ? R.string.script_hint : R.string.solo_hint;
        text(getString(description), 16, SECONDARY, false);
        editor = editorField(R.string.script_label, hint, script,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, MAX_SCRIPT_LENGTH);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setMinLines(7);
        editor.setMaxLines(12);

        int start = category.hasRoles() ? R.string.script_save_and_rehearse : R.string.solo_save_and_rehearse;
        button(getString(start), true, view -> saveEditor(true));
        button(getString(category.hasRoles() ? R.string.parse_preview : R.string.solo_preview_title), false,
            view -> showParsePreview(parseScript(editor.getText().toString())));
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
        Scene parsed = parseScript(candidate);
        if (rehearse && parsed.roles.isEmpty()) {
            editor.requestFocus();
            editor.setError(getString(category.hasRoles() ? R.string.invalid_scene : R.string.script_text_required));
            return;
        }
        scriptId = library.save(scriptId, title, candidate, category);
        scriptTitle = title;
        script = candidate;
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(editor.getWindowToken(), 0);
        }
        if (rehearse) {
            beginScript(parsed);
        } else {
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
        text(scriptTitle, 16, SECONDARY, false);
        text(getString(R.string.choose_scene), 34, INK, true);
        button(getString(R.string.entire_script), true, view -> selectSection(-1));
        for (int i = 0; i < fullScene.sections.size(); i++) {
            int index = i;
            String title = fullScene.sections.get(i).title;
            button(title.isEmpty() ? getString(R.string.untitled_scene, i + 1) : title, false,
                view -> selectSection(index));
        }
        button(getString(R.string.edit_scene), false, view -> show(Screen.EDITOR));
        button(getString(R.string.return_to_library), false, view -> returnToLibrary());
    }

    private void selectSection(int index) {
        sectionIndex = index;
        scene = fullScene.forSection(index);
        ensureRole();
        show(Screen.SETUP);
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

    private void ensureRole() {
        if (!scene.roles.isEmpty() && !scene.roles.contains(role)) {
            role = scene.roles.get(0);
        }
    }

    private void renderSetup() {
        LinearLayout actions = new LinearLayout(this);
        Button back = addEqualButton(actions, getString(R.string.setup_back), false, view -> onBackPressed());
        Button reader = addEqualButton(actions, getString(R.string.read_script), false, view -> show(Screen.READER));
        back.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        reader.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        for (Button action : new Button[] {back, reader}) {
            action.setBackground(new RippleDrawable(ColorStateList.valueOf(0x32F3BDA4), null, null));
            action.setTextColor(ACCENT);
        }
        content.addView(actions, row());

        LinearLayout titleCard = new LinearLayout(this);
        titleCard.setOrientation(LinearLayout.VERTICAL);
        titleCard.setPadding(dp(24), dp(30), dp(24), dp(28));
        GradientDrawable wash = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {0xFF44314A, CARD, BACKGROUND}
        );
        wash.setCornerRadius(dp(24));
        wash.setStroke(dp(1), BORDER);
        titleCard.setBackground(wash);
        TextView title = label(scriptTitle, 37, INK, true);
        titleCard.addView(title);
        if (sectionIndex >= 0) {
            String selected = fullScene.sections.get(sectionIndex).title;
            if (!selected.isEmpty()) {
                TextView section = label(selected, 15, HIGHLIGHT, false);
                section.setPadding(0, dp(10), 0, 0);
                titleCard.addView(section);
            }
        }
        content.addView(titleCard, row());

        if (mode == Mode.PRACTICE && category.hasRoles()) {
            button(getString(R.string.current_role, role), false, view -> showRoleOptions());
        }

        LinearLayout modes = new LinearLayout(this);
        addMode(modes, Mode.LISTEN, R.string.listen_mode);
        addMode(modes, Mode.PRACTICE, R.string.practice_mode);
        content.addView(modes, row());

        int modeDescription;
        if (category.hasRoles()) {
            modeDescription = mode == Mode.LISTEN ? R.string.listen_description : R.string.practice_description;
        } else {
            modeDescription = mode == Mode.LISTEN ? R.string.solo_listen_description : R.string.solo_practice_description;
        }
        TextView description = text(getString(modeDescription), 16, SECONDARY, false);
        description.setLineSpacing(dp(4), 1);
        description.setPadding(dp(16), dp(14), dp(16), dp(14));
        description.setBackground(cardBackground(CARD));
        if (mode == Mode.PRACTICE) {
            button(getString(R.string.visibility_setting, getString(visibilityLabel(lineVisibility))),
                false, view -> showVisibilityOptions());
        }

        TextView upcoming = text(getString(R.string.selftape_upcoming), 14, SECONDARY, false);
        upcoming.setPadding(dp(4), dp(8), dp(4), 0);
        if (hasNamedSections()) {
            button(getString(R.string.choose_scene), false, view -> show(Screen.SECTIONS));
        }
    }

    private LinearLayout setupActions() {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setBackgroundColor(CARD);
        actions.setPadding(dp(24), dp(6), dp(24), dp(14));
        actions.addView(makeButton(getString(R.string.edit_scene), false, view -> show(Screen.EDITOR)), row());
        int startLabel = mode == Mode.LISTEN ? R.string.listen_start : R.string.practice_start;
        actions.addView(makeButton(getString(startLabel), true, view -> {
            ensureRole();
            screen = Screen.REHEARSAL;
            moveTo(0, true);
        }), row());
        return actions;
    }

    private void addMode(LinearLayout group, Mode destination, int title) {
        Button choice = addEqualButton(group, getString(title), mode == destination, view -> {
            if (mode != destination) {
                mode = destination;
                render();
            }
        });
        choice.setSelected(mode == destination);
    }

    private Button addEqualButton(LinearLayout group, String title, boolean selected, View.OnClickListener action) {
        Button button = makeButton(title, selected, action);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1);
        if (group.getChildCount() > 0) {
            layout.leftMargin = dp(8);
        }
        group.addView(button, layout);
        return button;
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

    private void renderReader() {
        button(getString(R.string.setup_back), false, view -> show(Screen.SETUP));
        text(scriptTitle, 36, INK, true);
        SpannableStringBuilder transcript = new SpannableStringBuilder();
        String previousSection = "";
        for (Scene.Line line : scene.lines) {
            if (!line.section.isEmpty() && !line.section.equals(previousSection)) {
                appendStyled(transcript, line.section + "\n\n", Typeface.BOLD, HIGHLIGHT);
                previousSection = line.section;
            }
            for (String note : line.notes) {
                appendStyled(transcript, note + "\n", Typeface.ITALIC, SECONDARY);
            }
            if (category.hasRoles()) {
                appendStyled(transcript, line.role + "\n", Typeface.BOLD, ACCENT);
            }
            transcript.append(line.text).append("\n\n");
        }
        TextView manuscript = label(transcript, 20, INK, true);
        manuscript.setLineSpacing(dp(5), 1);
        manuscript.setTextIsSelectable(true);
        content.addView(manuscript, row());
    }

    private void appendStyled(SpannableStringBuilder text, String value, int style, int color) {
        int start = text.length();
        text.append(value);
        text.setSpan(new StyleSpan(style), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(color), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void renderRehearsal() {
        if (position == scene.lines.size()) {
            text(getString(R.string.completed_title), 34, INK, true);
            button(getString(R.string.restart), true, view -> moveTo(0, true));
            if (position > 0) {
                button(getString(R.string.previous_line), false, view -> moveTo(position - 1, false));
            }
            button(getString(R.string.back_to_setup), false, view -> show(Screen.SETUP));
            return;
        }

        Scene.Line line = scene.lines.get(position);
        boolean myTurn = !RehearsalOptions.shouldSpeak(mode, role, line.role);
        if (!line.section.isEmpty()) {
            text(line.section, 16, SECONDARY, false);
        }
        text(getString(R.string.line_counter, position + 1, scene.lines.size()), 16, SECONDARY, false);
        int title = myTurn ? R.string.your_turn
            : mode == Mode.LISTEN ? R.string.reading_role : R.string.partner_turn;
        String turn = category.hasRoles() ? getString(title, line.role)
            : getString(myTurn ? R.string.solo_your_turn : R.string.listen_mode);
        text(turn, 29, INK, true);
        if (!line.notes.isEmpty()) {
            TextView directions = text(TextUtils.join("\n", line.notes), 16, SECONDARY, true);
            directions.setTypeface(Typeface.SERIF, Typeface.ITALIC);
        }
        String shown = myTurn && !revealLine ? lineVisibility.visibleText(line.text) : line.text;
        TextView dialogue = text(shown.isEmpty() ? getString(R.string.line_hidden) : shown, 25, INK, true);
        dialogue.setBackground(cardBackground(CARD));
        dialogue.setPadding(dp(18), dp(18), dp(18), dp(18));
        if (myTurn && !revealLine && lineVisibility != Visibility.FULL) {
            button(getString(R.string.line_reveal), false, view -> {
                revealLine = true;
                render();
            });
        }

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
        button(getString(R.string.back_to_setup), false, view -> show(Screen.SETUP));
    }

    private int speechMessage() {
        if (activeUtterance != null) {
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

    private TextView text(String value, int size, int color, boolean serif) {
        TextView view = label(value, size, color, serif);
        content.addView(view, row());
        return view;
    }

    private TextView label(CharSequence value, int size, int color, boolean serif) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (serif) {
            view.setTypeface(Typeface.SERIF);
        }
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
        button.setMinHeight(dp(52));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable surface = cardBackground(primary ? ACCENT : PANEL);
        if (!primary) {
            surface.setStroke(dp(1), BORDER);
        }
        button.setBackground(new RippleDrawable(
            ColorStateList.valueOf(primary ? 0x26342131 : 0x32F3BDA4), surface, null
        ));
        button.setTextColor(primary ? BACKGROUND : INK);
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
