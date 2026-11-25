import Foundation

/// The saved texts, kept in the same JSON shape as the Android app so backup files move freely
/// between an Android phone and an iPhone.
public final class ScriptLibrary {
    private static let scriptsKey = "scripts"
    private let defaults: UserDefaults

    public init(_ defaults: UserDefaults) {
        self.defaults = defaults
    }

    public func list() -> [Script] {
        guard let stored = defaults.string(forKey: Self.scriptsKey), let data = stored.data(using: .utf8),
              let entries = try? JSONSerialization.jsonObject(with: data) as? [Any] else {
            return []
        }
        return entries.compactMap { entry in
            guard let object = entry as? [String: Any], let id = object["id"] as? String, !id.isEmpty else {
                return nil
            }
            return Self.script(from: object, id: id)
        }
    }

    @discardableResult
    public func save(id: String?, title: String, text: String, category: TextCategory,
                     language: ScriptLanguage) -> String {
        let scriptId = id ?? UUID().uuidString.lowercased()
        var scripts = list()
        scripts.removeAll { $0.id == scriptId }
        scripts.insert(Script(id: scriptId, title: title, text: text, category: category, language: language), at: 0)
        write(scripts)
        return scriptId
    }

    /// Saves a text without moving it: an existing one keeps its place, a new one goes on top.
    public func put(id: String, title: String, text: String, category: TextCategory, language: ScriptLanguage) {
        var scripts = list()
        let updated = Script(id: id, title: title, text: text, category: category, language: language)
        if let index = scripts.firstIndex(where: { $0.id == id }) {
            scripts[index] = updated
        } else {
            scripts.insert(updated, at: 0)
        }
        write(scripts)
    }

    /// Gives a text a new id in place, with its character voices; returns false if it is not
    /// in the library. A shared text becomes the group's copy this way, and back again.
    @discardableResult
    public func rekey(_ oldId: String, to newId: String, title: String) -> Bool {
        var scripts = list()
        guard let i = scripts.firstIndex(where: { $0.id == oldId }) else {
            return false
        }
        let old = scripts[i]
        scripts.removeAll { $0.id == newId }
        let index = min(i, scripts.count)
        scripts.removeAll { $0.id == oldId }
        scripts.insert(Script(id: newId, title: title, text: old.text, category: old.category, language: old.language),
                       at: min(index, scripts.count))
        write(scripts)
        if let cast = VoiceCast.raw(defaults, key: oldId) {
            VoiceCast.putRaw(defaults, key: newId, json: cast)
            VoiceCast.reset(defaults, key: oldId)
        }
        return true
    }

    /// Removes a saved text and returns its former position, or -1 if it was not found.
    @discardableResult
    public func delete(_ id: String) -> Int {
        var scripts = list()
        guard let index = scripts.firstIndex(where: { $0.id == id }) else {
            return -1
        }
        scripts.remove(at: index)
        write(scripts)
        return index
    }

    /// Puts a deleted text back at its former position.
    public func restore(_ script: Script, at index: Int) {
        var scripts = list()
        if scripts.contains(where: { $0.id == script.id }) {
            return
        }
        scripts.insert(script, at: max(0, min(index, scripts.count)))
        write(scripts)
    }

    /// A backup file: every text plus its character voices.
    public func exportJson() throws -> String {
        var casts: [String: String] = [:]
        let texts = list().map { script -> [String: Any] in
            if let cast = VoiceCast.raw(defaults, key: script.id) {
                casts[script.id] = cast
            }
            return Self.json(script)
        }
        let backup: [String: Any] = ["app": "memo-everything", "version": 1, "texts": texts, "voiceCasts": casts]
        let data = try JSONSerialization.data(withJSONObject: backup, options: [.prettyPrinted, .sortedKeys])
        return String(decoding: data, as: UTF8.self)
    }

    /// Merges a backup into the library: texts with the same id are replaced, new ones are added
    /// at the top, nothing is removed. Returns how many texts were imported.
    public func importJson(_ json: String) throws -> Int {
        guard let data = json.data(using: .utf8),
              let backup = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let texts = backup["texts"] as? [Any] else {
            throw ImportError.unreadable("Not a backup")
        }
        let casts = backup["voiceCasts"] as? [String: Any]
        var imported: [Script] = []
        for entry in texts {
            guard let object = entry as? [String: Any], let text = object["text"] as? String, !text.isEmpty else {
                continue
            }
            let id = object["id"] as? String ?? ""
            imported.append(Self.script(from: object, id: id.isEmpty ? UUID().uuidString.lowercased() : id))
        }
        let incoming = Set(imported.map(\.id))
        write(imported + list().filter { !incoming.contains($0.id) })
        if let casts {
            for script in imported {
                if let cast = casts[script.id] as? String {
                    VoiceCast.putRaw(defaults, key: script.id, json: cast)
                }
            }
        }
        return imported.count
    }

    private static func script(from object: [String: Any], id: String) -> Script {
        Script(id: id, title: object["title"] as? String ?? "", text: object["text"] as? String ?? "",
               category: TextCategory.fromSavedValue(object["category"] as? String),
               language: ScriptLanguage.fromCode(object["language"] as? String))
    }

    private static func json(_ script: Script) -> [String: Any] {
        ["id": script.id, "title": script.title, "text": script.text, "category": script.category.rawValue,
         "language": script.language.code]
    }

    private func write(_ scripts: [Script]) {
        let entries = scripts.map(Self.json)
        if let data = try? JSONSerialization.data(withJSONObject: entries) {
            defaults.set(String(decoding: data, as: UTF8.self), forKey: Self.scriptsKey)
        }
    }
}
