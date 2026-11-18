package com.scriptprep.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Design tokens and small view factories shared by every screen. */
final class Ui {
    static final int BACKGROUND = 0xFF111015;
    static final int SURFACE = 0xFF1B1A21;
    static final int SURFACE_HIGH = 0xFF25232D;
    static final int SURFACE_HIGHEST = 0xFF312E3A;
    static final int OUTLINE = 0xFF3A3645;
    static final int OUTLINE_SOFT = 0xFF292631;
    static final int TEXT = 0xFFF5F2F8;
    static final int TEXT_SECONDARY = 0xFFB4ADBF;
    static final int TEXT_TERTIARY = 0xFF827B8D;
    static final int ACCENT = 0xFFF4A98A;
    static final int ON_ACCENT = 0xFF2B1610;
    static final int ACCENT_CONTAINER = 0xFF3B2924;
    static final int ON_ACCENT_CONTAINER = 0xFFFFD8C9;
    static final int VIOLET = 0xFFC9B6F2;
    static final int VIOLET_CONTAINER = 0xFF2C2640;
    static final int TEAL = 0xFF8FD3C1;
    static final int TEAL_CONTAINER = 0xFF1C312D;
    static final int DANGER = 0xFFFF8F86;

    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);

    enum Type {
        DISPLAY(32, true, 0f),
        HEADLINE(26, true, 0f),
        TITLE(18, true, 0f),
        SUBTITLE(16, true, 0f),
        BODY_LARGE(16, false, 0f),
        BODY(14, false, 0f),
        LABEL(14, true, 0.01f),
        CAPTION(12, false, 0.01f),
        OVERLINE(12, true, 0.12f);

        final int size;
        final boolean medium;
        final float tracking;

        Type(int size, boolean medium, float tracking) {
            this.size = size;
            this.medium = medium;
            this.tracking = tracking;
        }
    }

    enum Kind { PRIMARY, TONAL, OUTLINE, TEXT, DANGER }

    private final Context context;

    Ui(Context context) {
        this.context = context;
    }

    int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    // ---- Text ----

    TextView text(CharSequence value, Type type, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(type.size);
        view.setTextColor(color);
        view.setTypeface(type.medium ? MEDIUM : REGULAR);
        view.setLetterSpacing(type.tracking);
        if (type == Type.OVERLINE) {
            view.setAllCaps(true);
        }
        if (type == Type.BODY || type == Type.BODY_LARGE) {
            view.setLineSpacing(dp(3), 1f);
        }
        return view;
    }

    TextView serif(CharSequence value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.SERIF);
        view.setLineSpacing(dp(4), 1f);
        return view;
    }

    // ---- Surfaces ----

    GradientDrawable shape(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    GradientDrawable outlined(int color, float radius, int stroke) {
        GradientDrawable shape = shape(color, radius);
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    Drawable ripple(Drawable content, int rippleColor) {
        Drawable mask = content == null ? shape(0xFFFFFFFF, 16) : null;
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    /** A ripple with no resting background, bounded by a rounded mask. */
    Drawable rippleMask(int rippleColor, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), null, shape(0xFFFFFFFF, radius));
    }

    /** A rounded container; clickable when an action is supplied. */
    LinearLayout card(int color, View.OnClickListener action) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable surface = outlined(color, 20, OUTLINE_SOFT);
        card.setClipToOutline(true);
        if (action == null) {
            card.setBackground(surface);
        } else {
            card.setBackground(ripple(surface, 0x22FFFFFF));
            card.setOnClickListener(action);
            card.setClickable(true);
            card.setFocusable(true);
        }
        return card;
    }

    GradientDrawable heroWash() {
        GradientDrawable wash = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {0xFF4A3036, 0xFF2A2236, SURFACE}
        );
        wash.setCornerRadius(dp(28));
        wash.setStroke(dp(1), 0x33F4A98A);
        return wash;
    }

    // ---- Icons ----

    ImageView icon(int drawable, int tint, int size) {
        ImageView view = new ImageView(context);
        view.setImageResource(drawable);
        view.setImageTintList(ColorStateList.valueOf(tint));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return view;
    }

    /** A rounded square holding a centered icon. */
    FrameLayout iconTile(int drawable, int tint, int background, int size) {
        FrameLayout tile = new FrameLayout(context);
        tile.setBackground(shape(background, size * 0.32f));
        ImageView glyph = icon(drawable, tint, Math.round(size * 0.5f));
        tile.addView(glyph, new FrameLayout.LayoutParams(dp(size * 0.5f), dp(size * 0.5f), Gravity.CENTER));
        tile.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return tile;
    }

    /** A circular touch target with an icon and an accessible label. */
    FrameLayout iconButton(int drawable, String description, int tint, int background,
                           int size, View.OnClickListener action) {
        FrameLayout button = new FrameLayout(context);
        button.setBackground(background == 0 ? rippleMask(0x2EFFFFFF, 999)
            : ripple(shape(background, 999), 0x2EFFFFFF));
        button.addView(icon(drawable, tint, 24),
            new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        button.setContentDescription(description);
        button.setOnClickListener(action);
        button.setClickable(true);
        button.setFocusable(true);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return button;
    }

    // ---- Buttons ----

    LinearLayout button(String label, int drawable, Kind kind, View.OnClickListener action) {
        LinearLayout button = new LinearLayout(context);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(dp(52));
        button.setPadding(dp(kind == Kind.TEXT ? 12 : 20), dp(12), dp(kind == Kind.TEXT ? 12 : 22), dp(12));
        int content;
        GradientDrawable surface;
        switch (kind) {
            case PRIMARY -> {
                surface = shape(ACCENT, 999);
                content = ON_ACCENT;
            }
            case TONAL -> {
                surface = shape(SURFACE_HIGHEST, 999);
                content = TEXT;
            }
            case OUTLINE -> {
                surface = outlined(0x00000000, 999, OUTLINE);
                content = TEXT;
            }
            case DANGER -> {
                surface = outlined(0x00000000, 999, 0x66FF8F86);
                content = DANGER;
            }
            default -> {
                surface = null;
                content = ACCENT;
            }
        }
        button.setBackground(surface == null ? rippleMask(0x2EFFFFFF, 999)
            : ripple(surface, kind == Kind.PRIMARY ? 0x33000000 : 0x2EFFFFFF));
        if (drawable != 0) {
            ImageView glyph = icon(drawable, content, 20);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(20), dp(20));
            params.rightMargin = dp(8);
            button.addView(glyph, params);
        }
        TextView title = text(label, Type.LABEL, content);
        title.setTextSize(15);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        button.addView(title);
        button.setContentDescription(label);
        button.setOnClickListener(action);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    TextView chip(String label, int foreground, int background) {
        TextView chip = text(label, Type.CAPTION, foreground);
        chip.setTypeface(MEDIUM);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        chip.setBackground(shape(background, 999));
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        return chip;
    }

    LinearLayout chip(String label, int drawable, int foreground, int background) {
        LinearLayout chip = new LinearLayout(context);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(10), dp(6), dp(12), dp(6));
        chip.setBackground(shape(background, 999));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(16), dp(16));
        iconParams.rightMargin = dp(6);
        chip.addView(icon(drawable, foreground, 16), iconParams);
        TextView text = text(label, Type.CAPTION, foreground);
        text.setTypeface(MEDIUM);
        text.setTextSize(13);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        chip.addView(text);
        return chip;
    }

    // ---- Rows ----

    /** A settings-style row: icon tile, title and value, trailing chevron when tappable. */
    LinearLayout listRow(int drawable, String title, String value, View.OnClickListener action) {
        View chevron = action == null ? null : icon(R.drawable.ic_chevron_right, TEXT_TERTIARY, 22);
        return listRow(drawable, title, value, chevron, action);
    }

    /** A settings-style row with a custom trailing view such as a switch or a badge. */
    LinearLayout listRow(int drawable, String title, String value, View trailing, View.OnClickListener action) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(16), dp(12), dp(12), dp(12));
        if (action != null) {
            row.setBackground(ripple(null, 0x1FFFFFFF));
            row.setOnClickListener(action);
            row.setClickable(true);
            row.setFocusable(true);
        }
        FrameLayout tile = iconTile(drawable, ACCENT, SURFACE_HIGHEST, 40);
        row.addView(tile);

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.leftMargin = dp(14);
        labelParams.rightMargin = dp(8);
        labels.addView(text(title, Type.SUBTITLE, TEXT));
        if (value != null && !value.isEmpty()) {
            TextView detail = text(value, Type.BODY, TEXT_SECONDARY);
            detail.setPadding(0, dp(2), 0, 0);
            labels.addView(detail);
        }
        row.addView(labels, labelParams);
        if (trailing != null) {
            row.addView(trailing);
        }
        return row;
    }

    /** A compact on/off switch drawn to match the theme. */
    FrameLayout toggle(boolean on) {
        FrameLayout track = new FrameLayout(context);
        GradientDrawable trackShape = shape(on ? ACCENT : SURFACE_HIGHEST, 999);
        if (!on) {
            trackShape.setStroke(dp(1), OUTLINE);
        }
        track.setBackground(trackShape);
        View knob = new View(context);
        knob.setBackground(shape(on ? ON_ACCENT : TEXT_SECONDARY, 999));
        FrameLayout.LayoutParams knobParams = new FrameLayout.LayoutParams(dp(20), dp(20),
            Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START));
        knobParams.leftMargin = dp(4);
        knobParams.rightMargin = dp(4);
        track.addView(knob, knobParams);
        track.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        track.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(28)));
        return track;
    }

    /** A small "Premium" badge with a lock, used on features the free plan cannot open. */
    LinearLayout premiumBadge() {
        LinearLayout badge = chip(context.getString(R.string.premium_tab), R.drawable.ic_lock,
            ON_ACCENT_CONTAINER, ACCENT_CONTAINER);
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return badge;
    }

    View divider(int inset) {
        View line = new View(context);
        line.setBackgroundColor(OUTLINE_SOFT);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2 + 1));
        params.leftMargin = dp(inset);
        line.setLayoutParams(params);
        return line;
    }

    /** A thin horizontal progress indicator. */
    LinearLayout progress(int done, int total) {
        LinearLayout track = new LinearLayout(context);
        track.setBackground(shape(SURFACE_HIGHEST, 999));
        View fill = new View(context);
        fill.setBackground(shape(ACCENT, 999));
        int safeTotal = Math.max(1, total);
        track.addView(fill, new LinearLayout.LayoutParams(0, -1, Math.max(0, done)));
        View rest = new View(context);
        track.addView(rest, new LinearLayout.LayoutParams(0, -1, Math.max(0, safeTotal - done)));
        track.setWeightSum(safeTotal);
        return track;
    }

    /** Moves a bar made by {@link #progress} without rebuilding it. */
    static void setProgress(LinearLayout track, int done, int total) {
        int safeTotal = Math.max(1, total);
        int filled = Math.max(0, Math.min(done, safeTotal));
        ((LinearLayout.LayoutParams) track.getChildAt(0).getLayoutParams()).weight = filled;
        ((LinearLayout.LayoutParams) track.getChildAt(1).getLayoutParams()).weight = safeTotal - filled;
        track.setWeightSum(safeTotal);
        track.requestLayout();
    }

    /** Background for text fields: filled surface, accent outline while focused. */
    Drawable fieldBackground() {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable focused = shape(SURFACE_HIGH, 16);
        focused.setStroke(dp(2), ACCENT);
        GradientDrawable idle = shape(SURFACE_HIGH, 16);
        idle.setStroke(dp(1), OUTLINE_SOFT);
        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[] {}, idle);
        return states;
    }

    static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams params = matchWidth();
        params.topMargin = dp(top);
        return params;
    }

    LinearLayout.LayoutParams wrapSpaced(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.topMargin = dp(top);
        return params;
    }
}
