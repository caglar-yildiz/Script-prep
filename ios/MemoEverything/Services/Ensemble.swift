import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import Foundation
import GoogleSignIn
import MemoCore
import UIKit

/// Ensemble rehearsal: a text shared by a cast, each member taking roles. Uses the same
/// Firestore collections and rules as the Android app, so iPhone and Android actors share groups.
///
///     users/{uid}/productions/{id}      the user's own index of productions they belong to
///     invites/{code}                    invite code → production id
///     productions/{id}                  title, text, category, language, ownerUid, inviteCode
///     productions/{id}/members/{uid}    name, roles, owner, code
///     productions/{id}/recordings/{id}  recorded lines
final class Ensemble {
    enum Failure: Error {
        case cancelled, notConfigured, noAccount, collision, network, notFound, denied, unknown

        var messageKey: String? {
            switch self {
            case .cancelled: return nil
            case .notConfigured: return "ensemble_error_config"
            case .noAccount: return "ensemble_error_no_account"
            case .network: return "ensemble_error_network"
            case .notFound: return "ensemble_error_not_found"
            case .denied: return "ensemble_error_denied"
            case .collision, .unknown: return "ensemble_error_unknown"
            }
        }
    }

    /// What connecting a Google account did.
    enum Connection {
        /// This device's identity is now linked to the Google account.
        case linked
        /// Signed in to the identity that Google account already had.
        case signedIn
    }

    struct Production {
        let id: String
        let title: String
        let text: String
        let category: TextCategory
        let language: ScriptLanguage
        let ownerUid: String
        let inviteCode: String

        init(_ document: DocumentSnapshot) {
            id = document.documentID
            title = document.get("title") as? String ?? ""
            text = document.get("text") as? String ?? ""
            category = TextCategory.fromSavedValue(document.get("category") as? String)
            language = ScriptLanguage.fromCode(document.get("language") as? String)
            ownerUid = document.get("ownerUid") as? String ?? ""
            inviteCode = document.get("inviteCode") as? String ?? ""
        }
    }

    struct Member {
        let uid: String
        let name: String
        let roles: [String]
        let owner: Bool

        init(_ document: DocumentSnapshot) {
            uid = document.documentID
            name = document.get("name") as? String ?? ""
            roles = (document.get("roles") as? [Any] ?? []).compactMap { $0 as? String }
            owner = document.get("owner") as? Bool ?? false
        }
    }

    /// A production in the user's own list, before its full text is loaded.
    struct Entry {
        let id: String
        let title: String
    }

    typealias Callback<T> = (Result<T, Failure>) -> Void

    static let shared = Ensemble()
    private static let nameKey = "ensemble_name"
    /// A Google credential already linked to another identity, kept until the user decides.
    private var pendingCredential: AuthCredential?

    /// Whether this build carries a Firebase project (GoogleService-Info.plist).
    static var available: Bool {
        FirebaseApp.app() != nil
    }

    /// Starts Firebase when the project file is in the app.
    static func configure() {
        guard FirebaseApp.app() == nil,
              Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil else {
            return
        }
        FirebaseApp.configure()
    }

    private var auth: Auth {
        Auth.auth()
    }

    private var store: Firestore {
        Firestore.firestore()
    }

    var user: User? {
        Ensemble.available ? auth.currentUser : nil
    }

    var uid: String? {
        user?.uid
    }

    var email: String? {
        user?.email
    }

    // MARK: - Identity
    //
    // Everyone starts with an anonymous identity kept on the device, under the name they type.
    // Linking a Google account keeps that identity, so the same groups, roles and recordings
    // open on a tablet or a new phone after signing in with Google there.

    /// Whether the identity survives on other devices through a Google account.
    var linked: Bool {
        guard let user else {
            return false
        }
        return !user.isAnonymous
    }

    var name: String {
        let saved = UserDefaults.standard.string(forKey: Self.nameKey) ?? ""
        if !saved.isEmpty {
            return saved
        }
        return JText.trim(user?.displayName ?? "")
    }

    private func saveName(_ name: String) {
        UserDefaults.standard.set(name, forKey: Self.nameKey)
    }

    /// Starts with an anonymous identity on this device.
    func start(name: String, _ callback: @escaping Callback<Void>) {
        let clean = EnsembleRules.cleanName(name)
        saveName(clean)
        auth.signInAnonymously { result, error in
            if let user = result?.user {
                // The profile name follows the identity to other devices once it is linked.
                let change = user.createProfileChangeRequest()
                change.displayName = clean
                change.commitChanges(completion: nil)
                callback(.success(()))
            } else {
                callback(.failure(Self.failure(error)))
            }
        }
    }

    /// Changes the user's name here and in every group they belong to.
    func rename(_ name: String, _ callback: @escaping Callback<Void>) {
        guard let user else {
            callback(.failure(.denied))
            return
        }
        let clean = EnsembleRules.cleanName(name)
        saveName(clean)
        let change = user.createProfileChangeRequest()
        change.displayName = clean
        change.commitChanges(completion: nil)
        list { result in
            switch result {
            case .success(let entries):
                let batch = self.store.batch()
                for entry in entries {
                    batch.updateData(["name": clean], forDocument: self.store.collection("productions")
                        .document(entry.id).collection("members").document(user.uid))
                }
                batch.commit { error in
                    callback(error == nil ? .success(()) : .failure(Self.failure(error)))
                }
            case .failure(let failure):
                callback(.failure(failure))
            }
        }
    }

    /// Links a Google account to this device's identity, or signs in with it when nobody is
    /// signed in yet. Fails with `.collision` when that Google account already has its own
    /// identity; `switchToPending` then moves to it.
    func connectGoogle(_ callback: @escaping Callback<Connection>) {
        guard let clientId = FirebaseApp.app()?.options.clientID, !clientId.isEmpty,
              let presenter = Self.topViewController() else {
            callback(.failure(.notConfigured))
            return
        }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientId)
        GIDSignIn.sharedInstance.signIn(withPresenting: presenter) { result, error in
            if let error {
                let code = (error as NSError).code
                // -5 is GIDSignInError.canceled.
                callback(.failure(code == -5 ? .cancelled : .noAccount))
                return
            }
            guard let user = result?.user, let token = user.idToken?.tokenString else {
                callback(.failure(.unknown))
                return
            }
            let credential = GoogleAuthProvider.credential(withIDToken: token,
                                                           accessToken: user.accessToken.tokenString)
            self.useGoogle(credential, callback)
        }
    }

    private func useGoogle(_ credential: AuthCredential, _ callback: @escaping Callback<Connection>) {
        guard let user else {
            signIn(with: credential, callback)
            return
        }
        user.link(with: credential) { _, error in
            guard let error else {
                callback(.success(.linked))
                return
            }
            let nsError = error as NSError
            if nsError.code == 17025 || nsError.code == 17007 {
                // credentialAlreadyInUse / emailAlreadyInUse: this Google account has its own identity.
                self.pendingCredential = nsError.userInfo["FIRAuthErrorUserInfoUpdatedCredentialKey"] as? AuthCredential
                    ?? credential
                callback(.failure(.collision))
            } else {
                callback(.failure(Self.failure(error)))
            }
        }
    }

    /// Leaves this device's identity for the one the Google account already has.
    func switchToPending(_ callback: @escaping Callback<Connection>) {
        guard let credential = pendingCredential else {
            callback(.failure(.unknown))
            return
        }
        pendingCredential = nil
        signIn(with: credential, callback)
    }

    private func signIn(with credential: AuthCredential, _ callback: @escaping Callback<Connection>) {
        auth.signIn(with: credential) { result, error in
            if let user = result?.user {
                // Use the name this identity had, not the one typed on this device.
                self.saveName(EnsembleRules.cleanName(user.displayName))
                callback(.success(.signedIn))
            } else {
                callback(.failure(Self.failure(error)))
            }
        }
    }

    /// Signs out of a Google-linked identity; it comes back by signing in with Google again.
    func signOut() {
        try? auth.signOut()
        GIDSignIn.sharedInstance.signOut()
        saveName("")
    }

    // MARK: - Productions

    /// The productions the user created or joined, newest first.
    func list(_ callback: @escaping Callback<[Entry]>) {
        guard let user else {
            callback(.success([]))
            return
        }
        store.collection("users").document(user.uid).collection("productions")
            .order(by: "joinedAt", descending: true)
            .getDocuments { snapshot, error in
                guard let snapshot else {
                    callback(.failure(Self.failure(error)))
                    return
                }
                callback(.success(snapshot.documents.map {
                    Entry(id: $0.documentID, title: $0.get("title") as? String ?? "")
                }))
            }
    }

    /// Shares a text: the owner becomes the first member and gets an invite code.
    func create(title: String, text: String, category: TextCategory, language: ScriptLanguage,
                attempts: Int = 3, _ callback: @escaping Callback<String>) {
        guard let user else {
            callback(.failure(.denied))
            return
        }
        let production = store.collection("productions").document()
        let code = EnsembleRules.newCode()
        let now = Timestamp(date: Date())
        let batch = store.batch()
        batch.setData(["title": title, "text": text, "category": category.rawValue, "language": language.code,
                       "ownerUid": user.uid, "inviteCode": code, "createdAt": now, "updatedAt": now],
                      forDocument: production)
        batch.setData(["productionId": production.documentID, "ownerUid": user.uid],
                      forDocument: store.collection("invites").document(code))
        batch.setData(member(code: code, owner: true), forDocument: production.collection("members").document(user.uid))
        batch.setData(indexEntry(title), forDocument: index(user.uid, production.documentID))
        batch.commit { error in
            guard let error else {
                callback(.success(production.documentID))
                return
            }
            // An invite code already in use is refused by the rules; try another one.
            if attempts > 1 && Self.failure(error) == .denied {
                self.create(title: title, text: text, category: category, language: language,
                            attempts: attempts - 1, callback)
            } else {
                callback(.failure(Self.failure(error)))
            }
        }
    }

    /// Joins the production an invite code points to and returns its id.
    func join(_ rawCode: String, _ callback: @escaping Callback<String>) {
        let code = EnsembleRules.normalizeCode(rawCode)
        guard let user else {
            callback(.failure(.denied))
            return
        }
        guard code.count == EnsembleRules.codeLength else {
            callback(.failure(.notFound))
            return
        }
        store.collection("invites").document(code).getDocument { invite, error in
            guard let invite else {
                callback(.failure(Self.failure(error)))
                return
            }
            guard invite.exists, let productionId = invite.get("productionId") as? String else {
                callback(.failure(.notFound))
                return
            }
            let production = self.store.collection("productions").document(productionId)
            let me = production.collection("members").document(user.uid)
            let addToList = {
                production.getDocument { document, error in
                    guard let document else {
                        callback(.failure(Self.failure(error)))
                        return
                    }
                    self.index(user.uid, productionId).setData(
                        self.indexEntry(document.get("title") as? String ?? "")) { error in
                            callback(error == nil ? .success(productionId) : .failure(Self.failure(error)))
                        }
                }
            }
            me.getDocument { existing, _ in
                if existing?.exists == true {
                    // Already a member: only make sure the production is in the user's list.
                    addToList()
                    return
                }
                me.setData(self.member(code: code, owner: false)) { error in
                    if let error {
                        callback(.failure(Self.failure(error)))
                    } else {
                        addToList()
                    }
                }
            }
        }
    }

    func load(_ productionId: String, _ callback: @escaping Callback<Production>) {
        store.collection("productions").document(productionId).getDocument { document, error in
            guard let document else {
                let failure = Self.failure(error)
                if failure == .denied {
                    // Removed from the cast, or the production was deleted.
                    self.forget(productionId)
                }
                callback(.failure(failure))
                return
            }
            guard document.exists else {
                self.forget(productionId)
                callback(.failure(.notFound))
                return
            }
            let production = Production(document)
            // Keep the name in the user's own list current after the owner renames it.
            if let uid = self.uid {
                self.index(uid, productionId).updateData(["title": production.title])
            }
            callback(.success(production))
        }
    }

    /// Renames a group; only its owner may.
    func rename(production productionId: String, title: String, _ callback: @escaping Callback<Void>) {
        store.collection("productions").document(productionId)
            .updateData(["title": title, "updatedAt": Timestamp(date: Date())]) { error in
                callback(error == nil ? .success(()) : .failure(Self.failure(error)))
            }
    }

    func members(_ productionId: String, _ callback: @escaping Callback<[Member]>) {
        store.collection("productions").document(productionId).collection("members")
            .order(by: "joinedAt")
            .getDocuments { snapshot, error in
                guard let snapshot else {
                    callback(.failure(Self.failure(error)))
                    return
                }
                callback(.success(snapshot.documents.map(Member.init)))
            }
    }

    func setRoles(_ productionId: String, roles: [String], _ callback: @escaping Callback<Void>) {
        guard let user else {
            callback(.failure(.denied))
            return
        }
        store.collection("productions").document(productionId).collection("members").document(user.uid)
            .updateData(["roles": roles, "name": name]) { error in
                callback(error == nil ? .success(()) : .failure(Self.failure(error)))
            }
    }

    /// Leaves a production the user joined; their recordings go with them.
    func leave(_ productionId: String, _ callback: @escaping Callback<Void>) {
        guard let user else {
            callback(.failure(.denied))
            return
        }
        let production = store.collection("productions").document(productionId)
        production.collection("recordings").whereField("uid", isEqualTo: user.uid).getDocuments { recordings, error in
            guard let recordings else {
                callback(.failure(Self.failure(error)))
                return
            }
            let batch = self.store.batch()
            recordings.documents.forEach { batch.deleteDocument($0.reference) }
            batch.deleteDocument(production.collection("members").document(user.uid))
            batch.deleteDocument(self.index(user.uid, productionId))
            batch.commit { error in
                callback(error == nil ? .success(()) : .failure(Self.failure(error)))
            }
        }
    }

    /// Deletes a production the user owns, with its members, recordings and invite code.
    func delete(_ production: Production, _ callback: @escaping Callback<Void>) {
        guard let user else {
            callback(.failure(.denied))
            return
        }
        let reference = store.collection("productions").document(production.id)
        reference.collection("recordings").getDocuments { recordings, error in
            guard let recordings else {
                callback(.failure(Self.failure(error)))
                return
            }
            reference.collection("members").getDocuments { members, error in
                guard let members else {
                    callback(.failure(Self.failure(error)))
                    return
                }
                // Recordings are deleted in their own batches first: a batch holds at most 500 writes.
                self.deleteAll(recordings.documents.map(\.reference), callback) {
                    let batch = self.store.batch()
                    for member in members.documents where member.documentID != user.uid {
                        batch.deleteDocument(member.reference)
                    }
                    batch.deleteDocument(self.store.collection("invites").document(production.inviteCode))
                    batch.deleteDocument(reference.collection("members").document(user.uid))
                    batch.deleteDocument(reference)
                    batch.deleteDocument(self.index(user.uid, production.id))
                    batch.commit { error in
                        callback(error == nil ? .success(()) : .failure(Self.failure(error)))
                    }
                }
            }
        }
    }

    private func deleteAll(_ documents: [DocumentReference], _ callback: @escaping Callback<Void>,
                           then: @escaping () -> Void) {
        if documents.isEmpty {
            then()
            return
        }
        let end = min(400, documents.count)
        let batch = store.batch()
        documents[0..<end].forEach { batch.deleteDocument($0) }
        let rest = Array(documents[end...])
        batch.commit { error in
            if let error {
                callback(.failure(Self.failure(error)))
            } else {
                self.deleteAll(rest, callback, then: then)
            }
        }
    }

    /// Drops a production the user can no longer open from their own list.
    private func forget(_ productionId: String) {
        if let uid {
            index(uid, productionId).delete()
        }
    }

    // MARK: - Helpers

    private func index(_ uid: String, _ productionId: String) -> DocumentReference {
        store.collection("users").document(uid).collection("productions").document(productionId)
    }

    private func indexEntry(_ title: String) -> [String: Any] {
        ["title": title, "joinedAt": Timestamp(date: Date())]
    }

    private func member(code: String, owner: Bool) -> [String: Any] {
        ["name": name, "roles": [String](), "owner": owner, "code": code, "joinedAt": Timestamp(date: Date())]
    }

    static func failure(_ error: Error?) -> Failure {
        guard let error = error as NSError? else {
            return .unknown
        }
        if error.domain == "FIRFirestoreErrorDomain" {
            switch error.code {
            case 7, 16: return .denied
            case 5: return .notFound
            case 14, 4: return .network
            default: return .unknown
            }
        }
        if error.domain == "FIRAuthErrorDomain" && error.code == 17020 {
            return .network
        }
        if error.domain == NSURLErrorDomain {
            return .network
        }
        return .unknown
    }

    static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive } ?? UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }.first
        var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController ?? scene?.windows.first?.rootViewController
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }
}

/// Recorded lines of an ensemble production. A recording belongs to a line through
/// `EnsembleRules.lineKey`, so it still matches after lines are added or moved. Recordings live in
/// Firestore (productions/{id}/recordings/{lineKey}_{uid}, long takes split into parts) and are
/// copied to the app's files, so rehearsals play them offline.
final class EnsembleRecordings {
    private static let syncPrefix = "ensemble_sync_"
    private let disk = DispatchQueue(label: "ensemble.disk")

    static func directory(_ productionId: String) -> URL {
        let safe = productionId.unicodeScalars.filter {
            CharacterSet.alphanumerics.contains($0) || $0 == "_" || $0 == "-"
        }
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ensemble", isDirectory: true)
            .appendingPathComponent(String(String.UnicodeScalarView(safe)), isDirectory: true)
    }

    private func file(_ productionId: String, _ key: String, _ uid: String) -> URL {
        Self.directory(productionId).appendingPathComponent(key + "_" + uid + ".m4a")
    }

    func mine(_ productionId: String, _ key: String) -> URL? {
        guard let uid = Ensemble.shared.uid else {
            return nil
        }
        let url = file(productionId, key, uid)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// Removes a production's recordings from this device, after leaving or deleting it.
    func forget(_ productionId: String) {
        try? FileManager.default.removeItem(at: Self.directory(productionId))
        UserDefaults.standard.removeObject(forKey: Self.syncPrefix + productionId)
    }

    private func recordings(_ productionId: String) -> CollectionReference {
        Firestore.firestore().collection("productions").document(productionId).collection("recordings")
    }

    /// Downloads recordings added, changed or deleted since the last sync.
    func sync(_ productionId: String, done: @escaping (Bool) -> Void) {
        let since = UserDefaults.standard.double(forKey: Self.syncPrefix + productionId)
        let collection = recordings(productionId)
        collection.whereField("updatedAt", isGreaterThan: Timestamp(date: Date(timeIntervalSince1970: since / 1000)))
            .order(by: "updatedAt")
            .getDocuments { snapshot, error in
                guard let snapshot else {
                    done(false)
                    return
                }
                var byId: [String: DocumentSnapshot] = [:]
                var takes: [DocumentSnapshot] = []
                for document in snapshot.documents {
                    byId[document.documentID] = document
                    if document.get("part") == nil {
                        takes.append(document)
                    }
                }
                self.disk.async {
                    var latest = since
                    var ok = true
                    for take in takes {
                        guard let key = take.get("lineKey") as? String, let uid = take.get("uid") as? String,
                              let updated = take.get("updatedAt") as? Timestamp else {
                            continue
                        }
                        let target = self.file(productionId, key, uid)
                        let first = take.get("audio") as? Data
                        if take.get("deleted") as? Bool == true || first == nil || first!.isEmpty {
                            try? FileManager.default.removeItem(at: target)
                        } else {
                            guard let audio = self.assemble(collection, take, byId), self.write(target, audio) else {
                                // Takes come oldest first: keep what is done, retry from here.
                                ok = false
                                break
                            }
                        }
                        latest = max(latest, updated.dateValue().timeIntervalSince1970 * 1000)
                    }
                    UserDefaults.standard.set(latest, forKey: Self.syncPrefix + productionId)
                    DispatchQueue.main.async {
                        done(ok)
                    }
                }
            }
    }

    /// Joins a take's parts; a part missing from the sync is fetched. Runs on the disk queue.
    private func assemble(_ collection: CollectionReference, _ take: DocumentSnapshot,
                          _ byId: [String: DocumentSnapshot]) -> Data? {
        let count = (take.get("parts") as? NSNumber)?.intValue ?? 1
        let parts = max(1, min(count, EnsembleRules.maxBytes / EnsembleRules.partBytes))
        let takeId = take.get("take") as? String
        var audio = take.get("audio") as? Data ?? Data()
        if parts > 1 {
            for n in 1..<parts {
                let id = take.documentID + "_\(n)"
                var part = byId[id]
                if part == nil {
                    let wait = DispatchSemaphore(value: 0)
                    collection.document(id).getDocument { document, _ in
                        part = document
                        wait.signal()
                    }
                    if wait.wait(timeout: .now() + 30) == .timedOut {
                        return nil
                    }
                }
                guard let chunk = part?.get("audio") as? Data, let takeId,
                      takeId == part?.get("take") as? String else {
                    // A newer take is on its way; the next sync brings it whole.
                    return nil
                }
                audio.append(chunk)
            }
        }
        return audio
    }

    private func write(_ target: URL, _ bytes: Data) -> Bool {
        do {
            try FileManager.default.createDirectory(at: target.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try bytes.write(to: target, options: [.atomic])
            return true
        } catch {
            return false
        }
    }

    /// Keeps a new recording on this device at once and uploads it, split into parts if long.
    func save(_ productionId: String, key: String, role: String, audio: Data, millis: Int, name: String,
              done: @escaping (Bool) -> Void) {
        guard let uid = Ensemble.shared.uid, !audio.isEmpty, audio.count <= EnsembleRules.maxBytes else {
            done(false)
            return
        }
        let target = file(productionId, key, uid)
        disk.async {
            let saved = self.write(target, audio)
            DispatchQueue.main.async {
                guard saved else {
                    done(false)
                    return
                }
                let takeId = UUID().uuidString.lowercased()
                let partBytes = EnsembleRules.partBytes
                let parts = (audio.count + partBytes - 1) / partBytes
                let batch = Firestore.firestore().batch()
                for n in 0..<parts {
                    let chunk = audio.subdata(in: (n * partBytes)..<min(audio.count, (n + 1) * partBytes))
                    var data: [String: Any] = ["uid": uid, "lineKey": key, "take": takeId, "audio": chunk,
                                               "updatedAt": FieldValue.serverTimestamp()]
                    if n == 0 {
                        data["name"] = name
                        data["role"] = role
                        data["parts"] = parts
                        data["durationMs"] = millis
                        data["deleted"] = false
                    } else {
                        data["part"] = n
                    }
                    batch.setData(data, forDocument: self.recordings(productionId).document(Self.id(key, uid, n)))
                }
                self.removeExtraParts(productionId, key, uid, keep: parts, batch, done)
            }
        }
    }

    /// Deletes the user's recording of a line here and, through an empty marker, everywhere.
    func delete(_ productionId: String, key: String, done: @escaping (Bool) -> Void) {
        guard let uid = Ensemble.shared.uid else {
            done(false)
            return
        }
        try? FileManager.default.removeItem(at: file(productionId, key, uid))
        let batch = Firestore.firestore().batch()
        batch.setData(["uid": uid, "lineKey": key, "audio": Data(), "parts": 0, "deleted": true,
                       "updatedAt": FieldValue.serverTimestamp()],
                      forDocument: recordings(productionId).document(Self.id(key, uid, 0)))
        removeExtraParts(productionId, key, uid, keep: 1, batch, done)
    }

    /// Adds deletes for parts an earlier, longer take left behind, then commits.
    private func removeExtraParts(_ productionId: String, _ key: String, _ uid: String, keep: Int,
                                  _ batch: WriteBatch, _ done: @escaping (Bool) -> Void) {
        recordings(productionId).whereField("uid", isEqualTo: uid).whereField("lineKey", isEqualTo: key)
            .getDocuments { found, _ in
                for part in found?.documents ?? [] {
                    if let n = (part.get("part") as? NSNumber)?.intValue, n >= keep {
                        batch.deleteDocument(part.reference)
                    }
                }
                batch.commit { error in
                    done(error == nil)
                }
            }
    }

    private static func id(_ key: String, _ uid: String, _ part: Int) -> String {
        part == 0 ? key + "_" + uid : key + "_" + uid + "_\(part)"
    }
}
