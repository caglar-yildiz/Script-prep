package com.scriptprep.app;

import static com.scriptprep.app.Ui.TEXT;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseUser;

/**
 * The ensemble account actions shared by Settings and the ensemble screen: linking or signing in
 * with Google, changing the name and signing out.
 */
final class EnsembleAccount {
    interface Listener {
        /** A request started; show progress. */
        void busy();

        /** The account changed, or the request ended; redraw. */
        void changed(Ensemble.Connection connection);
    }

    private EnsembleAccount() {}

    /** One line saying whether the identity lives on this device only or in a Google account. */
    static String status(Context context, Ensemble ensemble) {
        FirebaseUser user = ensemble.user();
        if (user == null) {
            return context.getString(R.string.ensemble_account_none);
        }
        if (ensemble.linked()) {
            return context.getString(R.string.ensemble_account_linked,
                user.getEmail() == null ? "" : user.getEmail());
        }
        return context.getString(R.string.ensemble_account_device);
    }

    /**
     * Signs in with Google, or links Google to this device's identity. When the Google account
     * already has its own identity, asks before switching to it.
     */
    static void connectGoogle(Activity activity, Ensemble ensemble, Listener listener) {
        listener.busy();
        ensemble.connectGoogle(activity, new Ensemble.Callback<>() {
            @Override
            public void done(Ensemble.Connection value) {
                if (value == Ensemble.Connection.LINKED) {
                    Toast.makeText(activity, R.string.ensemble_linked, Toast.LENGTH_LONG).show();
                }
                listener.changed(value);
            }

            @Override
            public void failed(Ensemble.Failure failure) {
                listener.changed(null);
                if (failure == Ensemble.Failure.COLLISION) {
                    confirmSwitch(activity, ensemble, listener);
                } else {
                    report(activity, failure);
                }
            }
        });
    }

    private static void confirmSwitch(Activity activity, Ensemble ensemble, Listener listener) {
        if (activity.isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(activity)
            .setTitle(R.string.ensemble_switch_title)
            .setMessage(R.string.ensemble_switch_body)
            .setPositiveButton(R.string.ensemble_switch_action, (dialog, which) -> {
                listener.busy();
                ensemble.switchToPending(new Ensemble.Callback<>() {
                    @Override
                    public void done(Ensemble.Connection value) {
                        listener.changed(value);
                    }

                    @Override
                    public void failed(Ensemble.Failure failure) {
                        listener.changed(null);
                        report(activity, failure);
                    }
                });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    static void askName(Activity activity, Ensemble ensemble, Listener listener) {
        Ui ui = new Ui(activity);
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setText(ensemble.name());
        field.setSelection(field.getText().length());
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS
            | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(Ensemble.MAX_NAME_LENGTH)});
        field.setTextColor(TEXT);
        FrameLayout frame = new FrameLayout(activity);
        frame.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        frame.addView(field);
        new AlertDialog.Builder(activity)
            .setTitle(R.string.ensemble_rename)
            .setView(frame)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String name = Ensemble.cleanName(field.getText().toString());
                if (name.isEmpty()) {
                    return;
                }
                ensemble.rename(name, new Ensemble.Callback<>() {
                    @Override
                    public void done(Void value) {
                        listener.changed(null);
                    }

                    @Override
                    public void failed(Ensemble.Failure failure) {
                        listener.changed(null);
                        report(activity, failure);
                    }
                });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Only a Google-linked identity can sign out: a device-only one could not come back. */
    static void confirmSignOut(Activity activity, Ensemble ensemble, Listener listener) {
        new AlertDialog.Builder(activity)
            .setTitle(R.string.ensemble_sign_out)
            .setMessage(R.string.ensemble_sign_out_body)
            .setPositiveButton(R.string.ensemble_sign_out, (dialog, which) ->
                ensemble.signOut(activity, () -> listener.changed(null)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    static void report(Context context, Ensemble.Failure failure) {
        int message = switch (failure) {
            case CANCELLED -> 0;
            case NOT_CONFIGURED -> R.string.ensemble_error_config;
            case NO_ACCOUNT -> R.string.ensemble_error_no_account;
            case NETWORK -> R.string.ensemble_error_network;
            case NOT_FOUND -> R.string.ensemble_error_not_found;
            case DENIED -> R.string.ensemble_error_denied;
            case COLLISION, UNKNOWN -> R.string.ensemble_error_unknown;
        };
        if (message != 0) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }
    }
}
