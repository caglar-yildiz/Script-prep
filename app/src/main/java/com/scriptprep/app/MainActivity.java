package com.scriptprep.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
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

import java.util.Locale;

public final class MainActivity extends Activity {
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");
    private static final int BACKGROUND = 0xFFF8F3E9;
    private static final int INK = 0xFF24362A;
    private static final int SECONDARY = 0xFF4B5D50;
    private static final int ACCENT = 0xFF36513E;
    private static final int CARD = 0xFFFFFCF6;
    private static final int MAX_SCRIPT_LENGTH = 20_000;
    private static final String EXAMPLE =
        "ECE: Prova başlamadan önce ışığı bir kez daha kontrol edelim.\n"
        + "MERT: Ben hazırım. Sahneye nereden giriyorum?\n"
        + "(Ece, sahnenin solunu gösterir.)\n"
        + "ECE: Soldan. Ben perdeyi kapattığımda içeri gir.\n"
        + "MERT: Peki ilk repliği kim söylüyor?\n"
        + "ECE: Sen. Ben yanına gelene kadar yerinde kal.\n"
        + "MERT: Tamam. Bu sefer baştan alalım.";

    private enum Screen { EDITOR, ROLES, REHEARSAL }
    private enum SpeechState { LOADING, READY, UNAVAILABLE }

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private EditText editor;
    private TextToSpeech speech;
    private SpeechState speechState = SpeechState.LOADING;
    private String activeUtterance;
    private int utteranceNumber;
    private boolean autoPlayPending;
    private boolean playbackFailed;
    private boolean visible;

    private Screen screen = Screen.EDITOR;
    private String script = "";
    private Scene scene;
    private String role;
    private int position;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            script = state.getString("script", "");
            screen = Screen.valueOf(state.getString("screen", Screen.EDITOR.name()));
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
        state.putString("screen", screen.name());
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
        } else if (screen == Screen.ROLES) {
            show(Screen.EDITOR);
        } else {
            super.onBackPressed();
        }
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
        }
    }

    private void finishSpeech(String id, boolean failed) {
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
        if (activeUtterance != null) {
            activeUtterance = null;
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

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(BACKGROUND);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(24), dp(24), dp(24));
        content.setFocusableInTouchMode(true);
        scroll.addView(content);
        setContentView(scroll);

        text(getString(R.string.app_name), 16, SECONDARY, false);
        switch (screen) {
            case EDITOR -> renderEditor();
            case ROLES -> renderRoles();
            case REHEARSAL -> renderRehearsal();
        }
    }

    private void renderEditor() {
        text(getString(R.string.editor_title), 34, INK, true);
        text(getString(R.string.editor_description), 16, SECONDARY, false);
        button(getString(R.string.sample_button), false, view -> {
            editor.setText(EXAMPLE);
            editor.setSelection(0);
            editor.setError(null);
        });

        TextView label = text(getString(R.string.script_label), 16, INK, false);
        editor = new EditText(this);
        editor.setId(View.generateViewId());
        label.setLabelFor(editor.getId());
        editor.setTextColor(INK);
        editor.setHintTextColor(SECONDARY);
        editor.setTextSize(16);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setFilters(new InputFilter[] {new InputFilter.LengthFilter(MAX_SCRIPT_LENGTH)});
        editor.setMinLines(7);
        editor.setMaxLines(12);
        editor.setPadding(dp(16), dp(14), dp(16), dp(14));
        editor.setBackground(cardBackground(CARD));
        editor.setHint(R.string.script_hint);
        editor.setText(script);
        content.addView(editor, row());
        button(getString(R.string.choose_role), true, view -> {
            String candidate = editor.getText().toString();
            Scene parsed = Scene.parse(candidate);
            if (parsed.roles.size() < 2) {
                editor.requestFocus();
                editor.setError(getString(R.string.invalid_scene));
                return;
            }
            script = candidate;
            scene = parsed;
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (keyboard != null) {
                keyboard.hideSoftInputFromWindow(editor.getWindowToken(), 0);
            }
            show(Screen.ROLES);
        });
        content.requestFocus();
    }

    private void renderRoles() {
        text(getString(R.string.role_title), 34, INK, true);
        for (String candidate : scene.roles) {
            button(candidate, true, view -> {
                role = candidate;
                screen = Screen.REHEARSAL;
                moveTo(0, true);
            });
        }
        button(getString(R.string.edit_scene), false, view -> show(Screen.EDITOR));
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
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setMinHeight(dp(48));
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? ACCENT : CARD));
        button.setTextColor(primary ? CARD : INK);
        button.setOnClickListener(action);
        content.addView(button, row());
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
