package com.scriptprep.app;

import static com.scriptprep.app.Ui.ACCENT;
import static com.scriptprep.app.Ui.ACCENT_CONTAINER;
import static com.scriptprep.app.Ui.BACKGROUND;
import static com.scriptprep.app.Ui.ON_ACCENT_CONTAINER;
import static com.scriptprep.app.Ui.SURFACE;
import static com.scriptprep.app.Ui.SURFACE_HIGHEST;
import static com.scriptprep.app.Ui.TEXT;
import static com.scriptprep.app.Ui.TEXT_SECONDARY;
import static com.scriptprep.app.Ui.TEXT_TERTIARY;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseUser;
import com.scriptprep.app.Ui.Kind;
import com.scriptprep.app.Ui.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ensemble rehearsal: sign in, share a text with the cast or join one with an invite code,
 * and take roles. Rehearsing hands a local copy of the text back to {@link MainActivity}.
 */
public final class EnsembleActivity extends Activity {
    static final String RESULT_SCRIPT_ID = "script_id";
    static final String RESULT_ROLE = "role";

    private enum Screen { LIST, PICK, DETAIL }

    private Ui ui;
    private Ensemble ensemble;
    private ScriptLibrary library;
    private Screen screen = Screen.LIST;
    private boolean busy;

    private List<Ensemble.Entry> entries;
    private Ensemble.Production production;
    private List<Ensemble.Member> members;
    private String openId;

    private LinearLayout header;
    private LinearLayout content;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.wrapLegacyContext(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        ensemble = new Ensemble(this);
        library = new ScriptLibrary(getSharedPreferences("script_prep", MODE_PRIVATE));
        drawBehindSystemBars();
        if (state != null) {
            screen = Screen.valueOf(state.getString("screen", Screen.LIST.name()));
            openId = state.getString("open_id");
        }
        if (screen == Screen.DETAIL && openId != null) {
            open(openId);
        } else {
            if (screen == Screen.DETAIL) {
                screen = Screen.LIST;
            }
            refreshList();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("screen", screen.name());
        state.putString("open_id", openId);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (screen == Screen.LIST) {
            super.onBackPressed();
        } else {
            showList();
        }
    }

    // ------------------------------------------------------------ Navigation

    private void showList() {
        screen = Screen.LIST;
        production = null;
        members = null;
        openId = null;
        refreshList();
    }

    private void refreshList() {
        if (ensemble.user() == null) {
            entries = null;
            render();
            return;
        }
        busy = true;
        render();
        ensemble.list(new Ensemble.Callback<>() {
            @Override
            public void done(List<Ensemble.Entry> value) {
                busy = false;
                entries = value;
                render();
            }

            @Override
            public void failed(Ensemble.Failure failure) {
                busy = false;
                entries = new ArrayList<>();
                report(failure);
                render();
            }
        });
    }

    private void open(String productionId) {
        screen = Screen.DETAIL;
        openId = productionId;
        busy = true;
        render();
        ensemble.load(productionId, new Ensemble.Callback<>() {
            @Override
            public void done(Ensemble.Production value) {
                production = value;
                // Keep a local copy, so the text can be rehearsed offline.
                saveLocalCopy(value);
                loadMembers();
            }

            @Override
            public void failed(Ensemble.Failure failure) {
                busy = false;
                report(failure);
                showList();
            }
        });
    }

    private void loadMembers() {
        ensemble.members(production.id, new Ensemble.Callback<>() {
            @Override
            public void done(List<Ensemble.Member> value) {
                busy = false;
                members = value;
                render();
            }

            @Override
            public void failed(Ensemble.Failure failure) {
                busy = false;
                members = new ArrayList<>();
                report(failure);
                render();
            }
        });
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
                | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
            return new int[] {value.left, value.top, value.right, value.bottom};
        }
        return new int[] {insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
            insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()};
    }

    private void render() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(BACKGROUND);
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        column.addView(header, Ui.matchWidth());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(28));
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        if (ensemble.user() == null) {
            renderSignIn();
        } else {
            switch (screen) {
                case LIST -> renderList();
                case PICK -> renderPick();
                case DETAIL -> renderDetail();
            }
        }
        column.setOnApplyWindowInsetsListener((view, insets) -> {
            int[] bars = systemInsets(insets);
            view.setPadding(bars[0], bars[1], bars[2], bars[3]);
            return insets;
        });
        setContentView(column);
    }

    private LinearLayout topBar(String title, Runnable navigate) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
        bar.setMinimumHeight(ui.dp(60));
        bar.addView(ui.iconButton(R.drawable.ic_back, getString(R.string.action_back), TEXT, 0, 48,
            view -> navigate.run()));
        TextView label = ui.text(title == null ? "" : title, Type.TITLE, TEXT);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.leftMargin = ui.dp(4);
        bar.addView(label, labelParams);
        header.addView(bar, Ui.matchWidth());
        return bar;
    }

    private void pageTitle(String overline, String title, String subtitle) {
        if (overline != null) {
            content.addView(ui.text(overline, Type.OVERLINE, ACCENT), ui.spaced(12));
        }
        content.addView(ui.text(title, Type.DISPLAY, TEXT), ui.spaced(overline == null ? 16 : 6));
        if (subtitle != null) {
            content.addView(ui.text(subtitle, Type.BODY_LARGE, TEXT_SECONDARY), ui.spaced(8));
        }
    }

    private void sectionLabel(String label) {
        content.addView(ui.text(label, Type.OVERLINE, TEXT_TERTIARY), ui.spaced(28));
    }

    private void spinner() {
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40));
        params.topMargin = ui.dp(40);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(progress, params);
    }

    // ------------------------------------------------------------ Sign-in

    private void renderSignIn() {
        topBar(null, this::finish);
        pageTitle(getString(R.string.ensemble_title), getString(R.string.ensemble_sign_in_title),
            getString(R.string.ensemble_sign_in_body));
        if (busy) {
            spinner();
            return;
        }
        content.addView(ui.button(getString(R.string.ensemble_sign_in), R.drawable.ic_person, Kind.PRIMARY,
            view -> signIn()), ui.spaced(28));
        TextView note = ui.text(getString(R.string.ensemble_privacy_note), Type.CAPTION, TEXT_TERTIARY);
        content.addView(note, ui.spaced(16));
    }

    private void signIn() {
        busy = true;
        render();
        ensemble.signIn(this, new Ensemble.Callback<>() {
            @Override
            public void done(FirebaseUser value) {
                busy = false;
                refreshList();
            }

            @Override
            public void failed(Ensemble.Failure failure) {
                busy = false;
                report(failure);
                render();
            }
        });
    }

    private void signOut() {
        ensemble.signOut(this, () -> {
            entries = null;
            screen = Screen.LIST;
            render();
        });
    }

    // ------------------------------------------------------------ List

    private void renderList() {
        LinearLayout bar = topBar(null, this::finish);
        bar.addView(ui.iconButton(R.drawable.ic_more, getString(R.string.ensemble_account),
            TEXT_SECONDARY, 0, 48, view -> showAccount()));
        pageTitle(getString(R.string.app_name), getString(R.string.ensemble_title), null);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.addView(ui.button(getString(R.string.ensemble_share), R.drawable.ic_add, Kind.PRIMARY,
            view -> {
                screen = Screen.PICK;
                render();
            }), Ui.matchWidth());
        actions.addView(ui.button(getString(R.string.ensemble_join), R.drawable.ic_people, Kind.TONAL,
            view -> askCode()), ui.spaced(10));
        content.addView(actions, ui.spaced(24));

        sectionLabel(getString(R.string.ensemble_groups));
        if (busy || entries == null) {
            spinner();
            return;
        }
        if (entries.isEmpty()) {
            content.addView(ui.text(getString(R.string.ensemble_empty), Type.BODY_LARGE, TEXT_SECONDARY),
                ui.spaced(10));
            return;
        }
        boolean first = true;
        for (Ensemble.Entry entry : entries) {
            LinearLayout card = ui.card(SURFACE, view -> open(entry.id));
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(ui.iconTile(R.drawable.ic_people, ACCENT, ACCENT_CONTAINER, 44));
            TextView name = ui.text(entry.title.isEmpty() ? getString(R.string.imported_title) : entry.title,
                Type.SUBTITLE, TEXT);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
            nameParams.leftMargin = ui.dp(14);
            card.addView(name, nameParams);
            card.addView(ui.icon(R.drawable.ic_chevron_right, TEXT_TERTIARY, 22));
            content.addView(card, ui.spaced(first ? 10 : 8));
            first = false;
        }
    }

    private void showAccount() {
        FirebaseUser user = ensemble.user();
        String who = user == null ? "" : (user.getEmail() == null ? "" : user.getEmail());
        new AlertDialog.Builder(this)
            .setTitle(R.string.ensemble_account)
            .setMessage(getString(R.string.ensemble_signed_in_as, who))
            .setPositiveButton(R.string.ensemble_sign_out, (dialog, which) -> signOut())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void askCode() {
        EditText field = new EditText(this);
        field.setHint(R.string.ensemble_join_hint);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(12)});
        field.setTextColor(TEXT);
        field.setHintTextColor(TEXT_TERTIARY);
        field.setLetterSpacing(0.2f);
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        frame.addView(field);
        new AlertDialog.Builder(this)
            .setTitle(R.string.ensemble_join)
            .setView(frame)
            .setPositiveButton(R.string.ensemble_join_action, (dialog, which) -> join(field.getText().toString()))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        field.requestFocus();
    }

    private void join(String code) {
        busy = true;
        render();
        ensemble.join(code, new Ensemble.Callback<>() {
            @Override
            public void done(String productionId) {
                busy = false;
                open(productionId);
            }

            @Override
            public void failed(Ensemble.Failure failure) {
                busy = false;
                report(failure);
                refreshList();
            }
        });
    }

    // ------------------------------------------------------------ Share a text

    private void renderPick() {
        topBar(getString(R.string.ensemble_share), this::showList);
        pageTitle(null, getString(R.string.ensemble_pick_text), getString(R.string.ensemble_pick_text_detail));
        if (busy) {
            spinner();
            return;
        }
        List<ScriptLibrary.Script> shareable = new ArrayList<>();
        for (ScriptLibrary.Script script : library.list()) {
            // Copies of shared texts are already shared; texts without roles have no cast.
            if (!script.id.startsWith(Ensemble.LOCAL_PREFIX) && script.category.hasRoles()
                && !Scene.parse(script.text).roles.isEmpty()) {
                shareable.add(script);
            }
        }
        if (shareable.isEmpty()) {
            content.addView(ui.text(getString(R.string.ensemble_no_texts), Type.BODY_LARGE, TEXT_SECONDARY),
                ui.spaced(24));
            return;
        }
        boolean first = true;
        for (ScriptLibrary.Script script : shareable) {
            Scene parsed = Scene.parse(script.text);
            LinearLayout card = ui.card(SURFACE, view -> share(script));
            card.addView(ui.text(script.title.isEmpty() ? getString(R.string.imported_title) : script.title,
                Type.SUBTITLE, TEXT));
            TextView meta = ui.text(getResources().getQuantityString(R.plurals.role_count,
                parsed.roles.size(), parsed.roles.size()) + " · "
                + getResources().getQuantityString(R.plurals.line_count, parsed.lines.size(), parsed.lines.size()),
                Type.BODY, TEXT_SECONDARY);
            meta.setPadding(0, ui.dp(3), 0, 0);
            card.addView(meta);
            content.addView(card, ui.spaced(first ? 24 : 8));
            first = false;
        }
    }

    private void share(ScriptLibrary.Script script) {
        busy = true;
        render();
        String title = script.title.isEmpty() ? getString(R.string.imported_title) : script.title;
        ensemble.create(title, script.text, script.category, script.language, new Ensemble.Callback<>() {
            @Override
            public void done(String productionId) {
                busy = false;
                open(productionId);
            }

            @Override
            public void failed(Ensemble.Failure failure) {
                busy = false;
                report(failure);
                render();
            }
        });
    }

    // ------------------------------------------------------------ A production

    private boolean isOwner() {
        FirebaseUser user = ensemble.user();
        return production != null && user != null && user.getUid().equals(production.ownerUid);
    }

    private Ensemble.Member me() {
        FirebaseUser user = ensemble.user();
        if (members == null || user == null) {
            return null;
        }
        for (Ensemble.Member member : members) {
            if (member.uid.equals(user.getUid())) {
                return member;
            }
        }
        return null;
    }

    private void renderDetail() {
        LinearLayout bar = topBar(null, this::showList);
        if (production == null || members == null) {
            spinner();
            return;
        }
        bar.addView(ui.iconButton(R.drawable.ic_more, getString(R.string.ensemble_group_actions),
            TEXT_SECONDARY, 0, 48, view -> showGroupActions()));
        pageTitle(getString(R.string.ensemble_title), production.title, null);

        // Invite code
        LinearLayout invite = ui.card(ACCENT_CONTAINER, null);
        invite.addView(ui.text(getString(R.string.ensemble_invite_code), Type.OVERLINE, ON_ACCENT_CONTAINER));
        TextView code = ui.text(production.inviteCode, Type.DISPLAY, ON_ACCENT_CONTAINER);
        code.setLetterSpacing(0.25f);
        code.setTextIsSelectable(true);
        invite.addView(code, ui.spaced(6));
        TextView inviteDetail = ui.text(getString(R.string.ensemble_invite_detail), Type.BODY, ON_ACCENT_CONTAINER);
        inviteDetail.setAlpha(0.8f);
        invite.addView(inviteDetail, ui.spaced(4));
        LinearLayout inviteActions = new LinearLayout(this);
        inviteActions.addView(ui.button(getString(R.string.ensemble_send_code), 0, Kind.PRIMARY,
            view -> sendCode()));
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(-2, -2);
        copyParams.leftMargin = ui.dp(8);
        inviteActions.addView(ui.button(getString(R.string.ensemble_copy_code), 0, Kind.TEXT,
            view -> copyCode()), copyParams);
        invite.addView(inviteActions, ui.spaced(14));
        content.addView(invite, ui.spaced(20));

        // My roles
        Scene parsed = production.category.parse(production.text, getString(R.string.solo_role));
        Ensemble.Member me = me();
        List<String> mine = me == null ? new ArrayList<>() : me.roles;
        sectionLabel(getString(R.string.ensemble_my_roles));
        LinearLayout mineCard = ui.card(SURFACE, null);
        mineCard.setPadding(0, ui.dp(4), 0, ui.dp(4));
        mineCard.addView(ui.listRow(R.drawable.ic_person, getString(R.string.ensemble_choose_roles),
            mine.isEmpty() ? getString(R.string.ensemble_no_roles) : TextUtils.join(", ", mine),
            view -> chooseRoles(parsed.roles, mine)), Ui.matchWidth());
        content.addView(mineCard, ui.spaced(10));

        LinearLayout rehearse = ui.button(getString(R.string.ensemble_rehearse), R.drawable.ic_play, Kind.PRIMARY,
            view -> rehearse(mine.isEmpty() ? null : mine.get(0)));
        content.addView(rehearse, ui.spaced(14));
        TextView soon = ui.text(getString(R.string.ensemble_recordings_soon), Type.CAPTION, TEXT_TERTIARY);
        content.addView(soon, ui.spaced(10));

        // Roles and who plays them
        sectionLabel(getString(R.string.ensemble_roles));
        Map<String, List<String>> cast = new LinkedHashMap<>();
        for (String role : parsed.roles) {
            cast.put(role, new ArrayList<>());
        }
        for (Ensemble.Member member : members) {
            for (String role : member.roles) {
                List<String> actors = cast.get(role);
                if (actors != null) {
                    actors.add(memberName(member));
                }
            }
        }
        LinearLayout rolesCard = ui.card(SURFACE, null);
        rolesCard.setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6));
        boolean firstRole = true;
        for (Map.Entry<String, List<String>> entry : cast.entrySet()) {
            if (!firstRole) {
                rolesCard.addView(ui.divider(0));
            }
            firstRole = false;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, ui.dp(12), 0, ui.dp(12));
            TextView roleName = ui.text(entry.getKey(), Type.SUBTITLE, TEXT);
            row.addView(roleName, new LinearLayout.LayoutParams(0, -2, 1));
            boolean open = entry.getValue().isEmpty();
            TextView actors = ui.text(open ? getString(R.string.ensemble_role_open)
                : TextUtils.join(", ", entry.getValue()), Type.BODY, open ? TEXT_TERTIARY : TEXT_SECONDARY);
            actors.setGravity(Gravity.END);
            row.addView(actors, new LinearLayout.LayoutParams(0, -2, 1));
            rolesCard.addView(row, Ui.matchWidth());
        }
        content.addView(rolesCard, ui.spaced(10));

        // Members
        sectionLabel(getResources().getQuantityString(R.plurals.ensemble_member_count, members.size(), members.size()));
        LinearLayout membersCard = ui.card(SURFACE, null);
        membersCard.setPadding(0, ui.dp(4), 0, ui.dp(4));
        for (Ensemble.Member member : members) {
            String detail = member.roles.isEmpty() ? getString(R.string.ensemble_no_roles)
                : TextUtils.join(", ", member.roles);
            if (member.owner) {
                detail = getString(R.string.ensemble_owner) + " · " + detail;
            }
            membersCard.addView(ui.listRow(R.drawable.ic_person, memberName(member), detail, null),
                Ui.matchWidth());
        }
        content.addView(membersCard, ui.spaced(10));
    }

    private String memberName(Ensemble.Member member) {
        String name = member.name.isEmpty() ? getString(R.string.ensemble_unnamed) : member.name;
        Ensemble.Member me = me();
        return me != null && me.uid.equals(member.uid) ? getString(R.string.ensemble_you, name) : name;
    }

    private void chooseRoles(List<String> roles, List<String> mine) {
        String[] items = roles.toArray(new String[0]);
        boolean[] checked = new boolean[items.length];
        for (int i = 0; i < items.length; i++) {
            checked[i] = mine.contains(items[i]);
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.ensemble_choose_roles)
            .setMultiChoiceItems(items, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                List<String> chosen = new ArrayList<>();
                for (int i = 0; i < items.length; i++) {
                    if (checked[i]) {
                        chosen.add(items[i]);
                    }
                }
                saveRoles(chosen);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void saveRoles(List<String> roles) {
        busy = true;
        ensemble.setRoles(production.id, roles, new Ensemble.Callback<>() {
            @Override
            public void done(Void value) {
                loadMembers();
            }

            @Override
            public void failed(Ensemble.Failure failure) {
                busy = false;
                report(failure);
            }
        });
    }

    private void sendCode() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, getString(R.string.ensemble_share_message,
            production.title, production.inviteCode));
        startActivity(Intent.createChooser(send, getString(R.string.ensemble_send_code)));
    }

    private void copyCode() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.ensemble_invite_code),
            production.inviteCode));
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, R.string.ensemble_code_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void showGroupActions() {
        boolean owner = isOwner();
        new AlertDialog.Builder(this)
            .setTitle(production.title)
            .setMessage(getString(owner ? R.string.ensemble_delete_confirm : R.string.ensemble_leave_confirm,
                production.title))
            .setPositiveButton(owner ? R.string.ensemble_delete : R.string.ensemble_leave,
                (dialog, which) -> leaveOrDelete(owner))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void leaveOrDelete(boolean owner) {
        Ensemble.Production target = production;
        busy = true;
        Ensemble.Callback<Void> callback = new Ensemble.Callback<>() {
            @Override
            public void done(Void value) {
                busy = false;
                library.delete(Ensemble.LOCAL_PREFIX + target.id);
                showList();
            }

            @Override
            public void failed(Ensemble.Failure failure) {
                busy = false;
                report(failure);
            }
        };
        if (owner) {
            ensemble.delete(target, callback);
        } else {
            ensemble.leave(target.id, callback);
        }
    }

    private void saveLocalCopy(Ensemble.Production value) {
        library.save(Ensemble.LOCAL_PREFIX + value.id, value.title, value.text, value.category, value.language);
    }

    private void rehearse(String role) {
        saveLocalCopy(production);
        Intent result = new Intent();
        result.putExtra(RESULT_SCRIPT_ID, Ensemble.LOCAL_PREFIX + production.id);
        if (role != null) {
            result.putExtra(RESULT_ROLE, role);
        }
        setResult(RESULT_OK, result);
        finish();
    }

    // ------------------------------------------------------------ Errors

    private void report(Ensemble.Failure failure) {
        int message = switch (failure) {
            case CANCELLED -> 0;
            case NOT_CONFIGURED -> R.string.ensemble_error_config;
            case NO_ACCOUNT -> R.string.ensemble_error_no_account;
            case NETWORK -> R.string.ensemble_error_network;
            case NOT_FOUND -> R.string.ensemble_error_not_found;
            case DENIED -> R.string.ensemble_error_denied;
            case UNKNOWN -> R.string.ensemble_error_unknown;
        };
        if (message != 0 && !isDestroyed()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }
}
