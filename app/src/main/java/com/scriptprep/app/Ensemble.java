package com.scriptprep.app;

import android.app.Activity;
import android.content.Context;
import android.os.CancellationSignal;

import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.firebase.FirebaseApp;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Ensemble rehearsal: a text shared by a cast, each member taking roles.
 *
 * <pre>
 * users/{uid}/productions/{id}      the user's own index of productions they belong to
 * invites/{code}                    invite code → production id
 * productions/{id}                  title, text, category, language, ownerUid, inviteCode
 * productions/{id}/members/{uid}    name, roles, owner, code
 * productions/{id}/recordings/{id}  recorded lines (next step)
 * </pre>
 *
 * Access is enforced by firestore.rules: only members read a production, and a non-member
 * becomes one only with its invite code.
 */
final class Ensemble {
    /** Letters and digits that cannot be confused when read aloud or typed. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 6;
    /** Prefix of the local library copy of a production, so rehearsal works offline. */
    static final String LOCAL_PREFIX = "ensemble:";
    private static final SecureRandom RANDOM = new SecureRandom();

    interface Callback<T> {
        void done(T value);

        void failed(Failure failure);
    }

    enum Failure { CANCELLED, NOT_CONFIGURED, NO_ACCOUNT, COLLISION, NETWORK, NOT_FOUND, DENIED, UNKNOWN }

    static final class Production {
        final String id;
        final String title;
        final String text;
        final TextCategory category;
        final ScriptLanguage language;
        final String ownerUid;
        final String inviteCode;

        Production(DocumentSnapshot document) {
            id = document.getId();
            title = string(document, "title");
            text = string(document, "text");
            category = TextCategory.fromSavedValue(document.getString("category"));
            language = ScriptLanguage.fromCode(document.getString("language"));
            ownerUid = string(document, "ownerUid");
            inviteCode = string(document, "inviteCode");
        }
    }

    static final class Member {
        final String uid;
        final String name;
        final List<String> roles;
        final boolean owner;

        Member(DocumentSnapshot document) {
            uid = document.getId();
            name = string(document, "name");
            List<String> saved = new ArrayList<>();
            Object value = document.get("roles");
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String role) {
                        saved.add(role);
                    }
                }
            }
            roles = Collections.unmodifiableList(saved);
            owner = Boolean.TRUE.equals(document.getBoolean("owner"));
        }
    }

    /** A production in the user's own list, before its full text is loaded. */
    static final class Entry {
        final String id;
        final String title;

        Entry(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private final Context context;
    private final Executor mainExecutor;

    Ensemble(Context context) {
        this.context = context.getApplicationContext();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        this.mainExecutor = main::post;
    }

    /** Whether this build carries a Firebase project (google-services.json). */
    static boolean available(Context context) {
        return !FirebaseApp.getApps(context.getApplicationContext()).isEmpty();
    }

    private FirebaseAuth auth() {
        return FirebaseAuth.getInstance();
    }

    private FirebaseFirestore store() {
        return FirebaseFirestore.getInstance();
    }

    FirebaseUser user() {
        return auth().getCurrentUser();
    }

    // ------------------------------------------------------------ Identity
    //
    // Everyone starts with an anonymous identity kept on the device, under the name they type.
    // Linking a Google account keeps that identity, so the same groups, roles and recordings
    // open on a tablet or a new phone after signing in with Google there.

    /** What connecting a Google account did. */
    enum Connection {
        /** This device's identity is now linked to the Google account. */
        LINKED,
        /** Signed in to the identity that Google account already had. */
        SIGNED_IN
    }

    private static final String NAME_PREFERENCE = "ensemble_name";
    static final int MAX_NAME_LENGTH = 40;
    /** A Google credential already linked to another identity, kept until the user decides. */
    private AuthCredential pendingCredential;

    /** Whether the identity survives on other devices through a Google account. */
    boolean linked() {
        FirebaseUser user = user();
        return user != null && !user.isAnonymous();
    }

    String name() {
        String saved = context.getSharedPreferences("script_prep", Context.MODE_PRIVATE)
            .getString(NAME_PREFERENCE, "");
        if (!saved.isEmpty()) {
            return saved;
        }
        FirebaseUser user = user();
        String name = user == null ? null : user.getDisplayName();
        return name == null ? "" : name.trim();
    }

    private void saveName(String name) {
        context.getSharedPreferences("script_prep", Context.MODE_PRIVATE).edit()
            .putString(NAME_PREFERENCE, name).apply();
    }

    /** Starts with an anonymous identity on this device. */
    void start(String name, Callback<FirebaseUser> callback) {
        String clean = cleanName(name);
        saveName(clean);
        auth().signInAnonymously()
            .addOnSuccessListener(mainExecutor, result -> {
                // The profile name follows the identity to other devices once it is linked.
                result.getUser().updateProfile(new UserProfileChangeRequest.Builder()
                    .setDisplayName(clean).build());
                callback.done(result.getUser());
            })
            .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error)));
    }

    /** Changes the user's name here and in every group they belong to. */
    void rename(String name, Callback<Void> callback) {
        FirebaseUser user = user();
        if (user == null) {
            callback.failed(Failure.DENIED);
            return;
        }
        String clean = cleanName(name);
        saveName(clean);
        user.updateProfile(new UserProfileChangeRequest.Builder().setDisplayName(clean).build());
        list(new Callback<>() {
            @Override
            public void done(List<Entry> entries) {
                WriteBatch batch = store().batch();
                for (Entry entry : entries) {
                    batch.update(store().collection("productions").document(entry.id)
                        .collection("members").document(user.getUid()), "name", clean);
                }
                batch.commit()
                    .addOnSuccessListener(mainExecutor, ignored -> callback.done(null))
                    .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error)));
            }

            @Override
            public void failed(Failure failure) {
                callback.failed(failure);
            }
        });
    }

    static String cleanName(String name) {
        String clean = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        return clean.length() > MAX_NAME_LENGTH ? clean.substring(0, MAX_NAME_LENGTH).trim() : clean;
    }

    /**
     * The OAuth web client the Firebase project created for Google sign-in, if any. Looked up by
     * name because google-services.json only carries it once Google sign-in is turned on.
     */
    @android.annotation.SuppressLint("DiscouragedApi")
    private String webClientId() {
        int id = context.getResources().getIdentifier("default_web_client_id", "string", context.getPackageName());
        return id == 0 ? null : context.getString(id);
    }

    /**
     * Links a Google account to this device's identity, or signs in with it when nobody is
     * signed in yet. Fails with {@link Failure#COLLISION} when that Google account already has
     * its own identity; {@link #switchToPending} then moves to it.
     */
    void connectGoogle(Activity activity, Callback<Connection> callback) {
        String clientId = webClientId();
        if (clientId == null || clientId.isEmpty()) {
            callback.failed(Failure.NOT_CONFIGURED);
            return;
        }
        GetCredentialRequest request = new GetCredentialRequest.Builder()
            .addCredentialOption(new GetSignInWithGoogleOption.Builder(clientId).build())
            .build();
        CredentialManager.create(activity).getCredentialAsync(activity, request, new CancellationSignal(),
            mainExecutor, new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                @Override
                public void onResult(GetCredentialResponse response) {
                    Credential credential = response.getCredential();
                    if (!(credential instanceof CustomCredential custom)
                        || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(custom.getType())) {
                        callback.failed(Failure.UNKNOWN);
                        return;
                    }
                    String token = GoogleIdTokenCredential.createFrom(custom.getData()).getIdToken();
                    useGoogle(GoogleAuthProvider.getCredential(token, null), callback);
                }

                @Override
                public void onError(GetCredentialException error) {
                    if (error instanceof GetCredentialCancellationException) {
                        callback.failed(Failure.CANCELLED);
                    } else if (error instanceof NoCredentialException) {
                        callback.failed(Failure.NO_ACCOUNT);
                    } else {
                        callback.failed(Failure.UNKNOWN);
                    }
                }
            });
    }

    private void useGoogle(AuthCredential credential, Callback<Connection> callback) {
        FirebaseUser user = user();
        if (user == null) {
            signInWith(credential, callback);
            return;
        }
        user.linkWithCredential(credential)
            .addOnSuccessListener(mainExecutor, result -> callback.done(Connection.LINKED))
            .addOnFailureListener(mainExecutor, error -> {
                if (error instanceof FirebaseAuthUserCollisionException) {
                    pendingCredential = credential;
                    callback.failed(Failure.COLLISION);
                } else {
                    callback.failed(failure(error));
                }
            });
    }

    /** Leaves this device's identity for the one the Google account already has. */
    void switchToPending(Callback<Connection> callback) {
        AuthCredential credential = pendingCredential;
        pendingCredential = null;
        if (credential == null) {
            callback.failed(Failure.UNKNOWN);
            return;
        }
        signInWith(credential, callback);
    }

    private void signInWith(AuthCredential credential, Callback<Connection> callback) {
        auth().signInWithCredential(credential)
            .addOnSuccessListener(mainExecutor, result -> {
                // Use the name this identity had, not the one typed on this device.
                String name = result.getUser().getDisplayName();
                saveName(name == null ? "" : cleanName(name));
                callback.done(Connection.SIGNED_IN);
            })
            .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error)));
    }

    /** Signs out of a Google-linked identity; it comes back by signing in with Google again. */
    void signOut(Activity activity, Runnable done) {
        auth().signOut();
        saveName("");
        CredentialManager.create(activity).clearCredentialStateAsync(new ClearCredentialStateRequest(),
            new CancellationSignal(), mainExecutor, new CredentialManagerCallback<Void, ClearCredentialException>() {
                @Override
                public void onResult(Void result) {
                    done.run();
                }

                @Override
                public void onError(ClearCredentialException error) {
                    done.run();
                }
            });
    }

    private String displayName() {
        return name();
    }

    // ------------------------------------------------------------ Productions

    /** The productions the user created or joined, newest first. */
    void list(Callback<List<Entry>> callback) {
        FirebaseUser user = user();
        if (user == null) {
            callback.done(Collections.emptyList());
            return;
        }
        store().collection("users").document(user.getUid()).collection("productions")
            .orderBy("joinedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener(mainExecutor, snapshot -> {
                List<Entry> entries = new ArrayList<>();
                for (QueryDocumentSnapshot document : snapshot) {
                    entries.add(new Entry(document.getId(), string(document, "title")));
                }
                callback.done(entries);
            })
            .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error)));
    }

    /** Shares a text: the owner becomes the first member and gets an invite code. */
    void create(String title, String text, TextCategory category, ScriptLanguage language,
                Callback<String> callback) {
        create(title, text, category, language, callback, 3);
    }

    private void create(String title, String text, TextCategory category, ScriptLanguage language,
                        Callback<String> callback, int attempts) {
        FirebaseUser user = user();
        if (user == null) {
            callback.failed(Failure.DENIED);
            return;
        }
        String uid = user.getUid();
        DocumentReference production = store().collection("productions").document();
        String code = newCode();
        Timestamp now = Timestamp.now();

        Map<String, Object> data = new HashMap<>();
        data.put("title", title);
        data.put("text", text);
        data.put("category", category.name());
        data.put("language", language.code);
        data.put("ownerUid", uid);
        data.put("inviteCode", code);
        data.put("createdAt", now);
        data.put("updatedAt", now);

        Map<String, Object> invite = new HashMap<>();
        invite.put("productionId", production.getId());
        invite.put("ownerUid", uid);

        WriteBatch batch = store().batch();
        batch.set(production, data);
        batch.set(store().collection("invites").document(code), invite);
        batch.set(production.collection("members").document(uid), member(code, true));
        batch.set(index(uid, production.getId()), indexEntry(title));
        batch.commit()
            .addOnSuccessListener(mainExecutor, ignored -> callback.done(production.getId()))
            .addOnFailureListener(mainExecutor, error -> {
                // An invite code already in use is refused by the rules; try another one.
                if (attempts > 1 && failure(error) == Failure.DENIED) {
                    create(title, text, category, language, callback, attempts - 1);
                } else {
                    callback.failed(failure(error));
                }
            });
    }

    /** Joins the production an invite code points to and returns its id. */
    void join(String rawCode, Callback<String> callback) {
        FirebaseUser user = user();
        String code = normalizeCode(rawCode);
        if (user == null) {
            callback.failed(Failure.DENIED);
            return;
        }
        if (code.length() != CODE_LENGTH) {
            callback.failed(Failure.NOT_FOUND);
            return;
        }
        String uid = user.getUid();
        store().collection("invites").document(code).get()
            .addOnSuccessListener(mainExecutor, invite -> {
                String productionId = invite.getString("productionId");
                if (!invite.exists() || productionId == null) {
                    callback.failed(Failure.NOT_FOUND);
                    return;
                }
                DocumentReference production = store().collection("productions").document(productionId);
                DocumentReference self = production.collection("members").document(uid);
                self.get().addOnCompleteListener(mainExecutor, existing -> {
                    if (existing.isSuccessful() && existing.getResult().exists()) {
                        // Already a member: only make sure the production is in the user's list.
                        production.get().addOnSuccessListener(mainExecutor, document ->
                            index(uid, productionId).set(indexEntry(string(document, "title")))
                                .addOnCompleteListener(mainExecutor, ignored -> callback.done(productionId)))
                            .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error)));
                        return;
                    }
                    self.set(member(code, false))
                        .addOnSuccessListener(mainExecutor, ignored -> production.get()
                            .addOnSuccessListener(mainExecutor, document ->
                                index(uid, productionId).set(indexEntry(string(document, "title")))
                                    .addOnSuccessListener(mainExecutor, done -> callback.done(productionId))
                                    .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error))))
                            .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error))))
                        .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error)));
                });
            })
            .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error)));
    }

    void load(String productionId, Callback<Production> callback) {
        store().collection("productions").document(productionId).get()
            .addOnSuccessListener(mainExecutor, document -> {
                if (document.exists()) {
                    Production production = new Production(document);
                    // Keep the name in the user's own list current after the owner renames it.
                    FirebaseUser user = user();
                    if (user != null) {
                        index(user.getUid(), productionId).update("title", production.title);
                    }
                    callback.done(production);
                } else {
                    forget(productionId);
                    callback.failed(Failure.NOT_FOUND);
                }
            })
            .addOnFailureListener(mainExecutor, error -> {
                Failure failure = failure(error);
                if (failure == Failure.DENIED) {
                    // Removed from the cast, or the production was deleted.
                    forget(productionId);
                }
                callback.failed(failure);
            });
    }

    /** Renames a group; only its owner may. */
    void rename(String productionId, String title, Callback<Void> callback) {
        Map<String, Object> update = new HashMap<>();
        update.put("title", title);
        update.put("updatedAt", Timestamp.now());
        store().collection("productions").document(productionId).update(update)
            .addOnSuccessListener(mainExecutor, ignored -> callback.done(null))
            .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error)));
    }

    void members(String productionId, Callback<List<Member>> callback) {
        store().collection("productions").document(productionId).collection("members")
            .orderBy("joinedAt")
            .get()
            .addOnSuccessListener(mainExecutor, snapshot -> {
                List<Member> members = new ArrayList<>();
                for (QueryDocumentSnapshot document : snapshot) {
                    members.add(new Member(document));
                }
                callback.done(members);
            })
            .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error)));
    }

    void setRoles(String productionId, List<String> roles, Callback<Void> callback) {
        FirebaseUser user = user();
        if (user == null) {
            callback.failed(Failure.DENIED);
            return;
        }
        Map<String, Object> update = new HashMap<>();
        update.put("roles", new ArrayList<>(roles));
        update.put("name", displayName());
        store().collection("productions").document(productionId).collection("members")
            .document(user.getUid()).update(update)
            .addOnSuccessListener(mainExecutor, ignored -> callback.done(null))
            .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error)));
    }

    /** Leaves a production the user joined; their recordings go with them. */
    void leave(String productionId, Callback<Void> callback) {
        FirebaseUser user = user();
        if (user == null) {
            callback.failed(Failure.DENIED);
            return;
        }
        String uid = user.getUid();
        DocumentReference production = store().collection("productions").document(productionId);
        production.collection("recordings").whereEqualTo("uid", uid).get()
            .addOnSuccessListener(mainExecutor, recordings -> {
                WriteBatch batch = store().batch();
                for (QueryDocumentSnapshot recording : recordings) {
                    batch.delete(recording.getReference());
                }
                batch.delete(production.collection("members").document(uid));
                batch.delete(index(uid, productionId));
                batch.commit()
                    .addOnSuccessListener(mainExecutor, ignored -> callback.done(null))
                    .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error)));
            })
            .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error)));
    }

    /** Deletes a production the user owns, with its members, recordings and invite code. */
    void delete(Production production, Callback<Void> callback) {
        FirebaseUser user = user();
        if (user == null) {
            callback.failed(Failure.DENIED);
            return;
        }
        DocumentReference reference = store().collection("productions").document(production.id);
        reference.collection("recordings").get()
            .addOnSuccessListener(mainExecutor, recordings -> reference.collection("members").get()
                .addOnSuccessListener(mainExecutor, members -> {
                    // Recordings are deleted in their own batches first: a batch holds at most 500 writes.
                    List<DocumentReference> pending = new ArrayList<>();
                    for (QueryDocumentSnapshot recording : recordings) {
                        pending.add(recording.getReference());
                    }
                    deleteAll(pending, () -> {
                        WriteBatch batch = store().batch();
                        for (QueryDocumentSnapshot member : members) {
                            if (!member.getId().equals(user.getUid())) {
                                batch.delete(member.getReference());
                            }
                        }
                        batch.delete(store().collection("invites").document(production.inviteCode));
                        batch.delete(reference.collection("members").document(user.getUid()));
                        batch.delete(reference);
                        batch.delete(index(user.getUid(), production.id));
                        batch.commit()
                            .addOnSuccessListener(mainExecutor, ignored -> callback.done(null))
                            .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error)));
                    }, callback);
                })
                .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error))))
            .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error)));
    }

    private void deleteAll(List<DocumentReference> documents, Runnable then, Callback<Void> callback) {
        if (documents.isEmpty()) {
            then.run();
            return;
        }
        int end = Math.min(400, documents.size());
        WriteBatch batch = store().batch();
        for (DocumentReference document : documents.subList(0, end)) {
            batch.delete(document);
        }
        List<DocumentReference> rest = new ArrayList<>(documents.subList(end, documents.size()));
        batch.commit()
            .addOnSuccessListener(mainExecutor, ignored -> deleteAll(rest, then, callback))
            .addOnFailureListener(mainExecutor, error -> callback.failed(failure(error)));
    }

    /** Drops a production the user can no longer open from their own list. */
    private void forget(String productionId) {
        FirebaseUser user = user();
        if (user != null) {
            index(user.getUid(), productionId).delete();
        }
    }

    // ------------------------------------------------------------ Helpers

    private DocumentReference index(String uid, String productionId) {
        return store().collection("users").document(uid).collection("productions").document(productionId);
    }

    private static Map<String, Object> indexEntry(String title) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("title", title);
        entry.put("joinedAt", Timestamp.now());
        return entry;
    }

    private Map<String, Object> member(String code, boolean owner) {
        Map<String, Object> member = new HashMap<>();
        member.put("name", displayName());
        member.put("roles", new ArrayList<String>());
        member.put("owner", owner);
        member.put("code", code);
        member.put("joinedAt", Timestamp.now());
        return member;
    }

    static String newCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    /** Upper-cases a typed code and drops spaces, dashes and other separators. */
    static String normalizeCode(String raw) {
        StringBuilder code = new StringBuilder();
        for (char c : raw.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                code.append(c);
            }
        }
        return code.toString();
    }

    private static String string(DocumentSnapshot document, String field) {
        String value = document.getString(field);
        return value == null ? "" : value;
    }

    private static Failure failure(Exception error) {
        if (error instanceof FirebaseFirestoreException firestore) {
            return switch (firestore.getCode()) {
                case PERMISSION_DENIED, UNAUTHENTICATED -> Failure.DENIED;
                case NOT_FOUND -> Failure.NOT_FOUND;
                case UNAVAILABLE, DEADLINE_EXCEEDED -> Failure.NETWORK;
                default -> Failure.UNKNOWN;
            };
        }
        if (error instanceof com.google.firebase.FirebaseNetworkException) {
            return Failure.NETWORK;
        }
        return Failure.UNKNOWN;
    }
}
