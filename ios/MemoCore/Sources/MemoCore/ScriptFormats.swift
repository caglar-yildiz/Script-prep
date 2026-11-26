import Foundation
#if canImport(Compression)
import Compression
#endif

public enum ImportError: Error, Equatable {
    /// The text or file is larger than the app reads.
    case lengthLimit
    /// The user stopped the import.
    case cancelled
    /// No text was found, not even on scanned pages.
    case noText
    /// The file is not what it claims to be or cannot be read.
    case unreadable(String)
}

/// Turns Word (DOCX), Fountain and Final Draft (FDX) scripts into the plain script text the
/// parser reads: "ROLE: line", headings on their own rows and directions in parentheses.
public enum ScriptFormats {
    static let word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    /// The largest unpacked Word document read; far more than any play needs.
    static let maxXmlBytes = 30 * 1024 * 1024
    private static let continued = JRegex("\\s*\\((?:CONT['’]?D|CONTINUED|MORE)\\)\\s*", caseInsensitive: true)
    private static let titleKey = JRegex(
        "^(?i)(?:title|credit|author|authors|source|draft date|date|contact|copyright|notes|revision)\\s*:.*")
    private static let sceneHeading = JRegex("^(?i)(?:INT|EXT|EST|INT\\./EXT|INT/EXT|I/E)[ .].*")
    private static let boneyard = JRegex("/\\*.*?\\*/", dotAll: true)
    private static let fountainNote = JRegex("\\[\\[.*?]]", dotAll: true)
    private static let rule = JRegex("^={3,}$")
    private static let hashes = JRegex("^#+\\s*")
    private static let caret = JRegex("\\s*\\^$")
    private static let parenthetical = JRegex("\\([^()]*\\)")
    private static let nameCharacters = JRegex("[^\\p{L}\\p{N} .'’-]")
    private static let emphasis = JRegex("(?<!\\\\)[*_]+")
    private static let whitespace = JRegex("\\s+")
    private static let leadingSpace = JRegex("^\\s+")
    private static let trailingSeparator = JRegex("\\s*[:‒–—−-]$")
    private static let trailingAside = JRegex("\\s*\\([^()]*\\)$")
    private static let outerParens = JRegex("^\\(|\\)$")

    public enum Kind: Sendable {
        case text, docx, fountain, fdx
    }

    /// The format a file name points to; plain text when the ending is unknown.
    public static func kindOf(_ fileName: String?) -> Kind {
        let name = JText.lowerRoot(fileName ?? "")
        if name.hasSuffix(".docx") {
            return .docx
        }
        if name.hasSuffix(".fountain") || name.hasSuffix(".spmd") {
            return .fountain
        }
        if name.hasSuffix(".fdx") {
            return .fdx
        }
        return .text
    }

    /// Word and Final Draft files picked without a telling name are known by how they start.
    public static func sniff(_ data: Data) -> Kind {
        let head = data.prefix(512)
        if head.count >= 4 && head[head.startIndex] == 0x50 && head[head.startIndex + 1] == 0x4B
            && head[head.startIndex + 2] == 3 && head[head.startIndex + 3] == 4 {
            return .docx
        }
        let start = String(decoding: head, as: UTF8.self)
        return start.contains("<FinalDraft") ? .fdx : .text
    }

    // MARK: - Word

    /// The paragraphs of a Word document; a name in bold at the start becomes a role cue.
    public static func docx(_ data: Data) throws -> String {
        guard let xml = try Zip.entry(named: "word/document.xml", in: data, limit: maxXmlBytes) else {
            throw ImportError.unreadable("Not a Word document")
        }
        let document = try XmlNode.parse(xml, namespaces: true)
        var text = ""
        for paragraph in document.descendants(local: "p", namespace: word) {
            text += wordParagraph(paragraph) + "\n"
        }
        return JText.trim(text)
    }

    private static func wordParagraph(_ paragraph: XmlNode) -> String {
        var plain = ""
        var boldEnd = 0
        var leadingBold = true
        var allItalic = true
        var anyText = false
        for run in paragraph.descendants(local: "r", namespace: word) {
            let runText = self.runText(run)
            if runText.isEmpty {
                continue
            }
            plain += runText
            if JText.trim(runText).isEmpty {
                continue
            }
            anyText = true
            let bold = hasStyle(run, "b")
            allItalic = allItalic && hasStyle(run, "i")
            leadingBold = leadingBold && bold
            if leadingBold {
                boldEnd = JText.length(plain)
            }
        }
        let text = JText.trim(plain.replacingOccurrences(of: "\u{00a0}", with: " "))
        if !anyText {
            return ""
        }
        if ScriptScene.isHeading(text) {
            return text
        }
        let lead = JText.length(plain) - JText.length(leadingSpace.replaceFirst(plain, ""))
        if boldEnd > lead {
            let prefix = JText.trim(JText.sub(plain, lead, boldEnd))
            let name = JText.trim(trailingAside.replaceFirst(trailingSeparator.replaceFirst(prefix, ""), ""))
            if ScriptScene.isRoleName(name) {
                let rest = JText.trim(JText.sub(plain, boldEnd))
                return "**" + prefix + "**" + (rest.isEmpty ? "" : " " + rest)
            }
        }
        return allItalic ? "*" + text + "*" : text
    }

    private static func runText(_ run: XmlNode) -> String {
        var text = ""
        for child in run.children where child.namespace == word {
            switch child.local {
            case "t":
                text += child.textContent
            case "tab", "br", "cr":
                text += " "
            default:
                break
            }
        }
        return text
    }

    /// Whether a run is bold ("b") or italic ("i"), allowing for w:val="0" turning it off.
    private static func hasStyle(_ run: XmlNode, _ style: String) -> Bool {
        guard let properties = run.descendants(local: "rPr", namespace: word).first,
              let mark = properties.descendants(local: style, namespace: word).first else {
            return false
        }
        let value = mark.attribute(local: "val") ?? ""
        return value != "0" && JText.lowerRoot(value) != "false" && JText.lowerRoot(value) != "none"
    }

    // MARK: - Fountain

    /// A Fountain screenplay: characters become cues, action becomes directions.
    public static func fountain(_ source: String) -> String {
        var text = source.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        text = boneyard.replaceAll(text, "")
        text = fountainNote.replaceAll(text, "")
        let rows = text.components(separatedBy: "\n")
        var start = 0
        if let first = rows.first, titleKey.isMatch(first) {
            while start < rows.count && !JText.trim(rows[start]).isEmpty {
                start += 1
            }
        }

        var output = ""
        var i = start
        while i < rows.count {
            defer { i += 1 }
            let row = JText.trim(rows[i])
            if row.isEmpty || rule.isMatch(row) || row.hasPrefix("=") {
                continue
            }
            let afterBlank = i == start || JText.trim(rows[i - 1]).isEmpty
            let beforeText = i + 1 < rows.count && !JText.trim(rows[i + 1]).isEmpty
            if row.hasPrefix("#") {
                blank(&output)
                output += plain(hashes.replaceFirst(row, "")) + "\n"
            } else if (row.hasPrefix(".") && !row.hasPrefix("..")) || sceneHeading.isMatch(row) {
                blank(&output)
                output += plain(row.hasPrefix(".") ? JText.sub(row, 1) : row) + "\n"
            } else if row.hasPrefix(">") && row.hasSuffix("<") {
                output += note(JText.sub(row, 1, JText.length(row) - 1)) + "\n"
            } else if row.hasPrefix(">") || (afterBlank && isTransition(row)) {
                continue
            } else if afterBlank && beforeText && isCharacter(row) {
                let name = JText.trim(caret.replaceFirst(
                    continued.replaceAll(row.hasPrefix("@") ? JText.sub(row, 1) : row, " "), ""))
                blank(&output)
                output += plain(name) + ":\n"
                i = dialogue(rows, i + 1, &output) - 1
            } else {
                // Action: kept as a direction, one paragraph per note.
                var action = row.hasPrefix("!") ? JText.sub(row, 1) : row
                while i + 1 < rows.count && !JText.trim(rows[i + 1]).isEmpty {
                    i += 1
                    action += " " + JText.trim(rows[i])
                }
                output += note(action) + "\n"
            }
        }
        return JText.trim(output)
    }

    /// Copies the dialogue rows after a character, up to the next blank row; returns that row.
    private static func dialogue(_ rows: [String], _ from: Int, _ output: inout String) -> Int {
        var i = from
        while i < rows.count && !JText.trim(rows[i]).isEmpty {
            let row = JText.trim(rows[i])
            if row.hasPrefix("(") && row.hasSuffix(")") {
                output += note(JText.sub(row, 1, JText.length(row) - 1)) + "\n"
            } else {
                output += plain(row.hasPrefix("~") ? JText.sub(row, 1) : row) + "\n"
            }
            i += 1
        }
        return i
    }

    private static func isCharacter(_ row: String) -> Bool {
        if row.hasPrefix("@") {
            return JText.length(row) > 1
        }
        let name = JText.trim(parenthetical.replaceAll(caret.replaceFirst(row, ""), ""))
        return !name.isEmpty && name == JText.upperRoot(name) && name != JText.lowerRoot(name)
            && !sceneHeading.isMatch(name) && ScriptScene.isRoleName(JText.trim(nameCharacters.replaceAll(name, "")))
    }

    private static func isTransition(_ row: String) -> Bool {
        row == JText.upperRoot(row) && row.hasSuffix("TO:")
    }

    /// Fountain emphasis marks (*, **, ***, _) are left out; escaped characters are kept.
    private static func plain(_ row: String) -> String {
        JText.trim(emphasis.replaceAll(row, "").replacingOccurrences(of: "\\*", with: "*")
            .replacingOccurrences(of: "\\_", with: "_"))
    }

    // MARK: - Final Draft

    /// A Final Draft script: its paragraph types say which rows are cues, dialogue or action.
    public static func fdx(_ data: Data) throws -> String {
        if data.count > maxXmlBytes {
            throw ImportError.lengthLimit
        }
        let document = try XmlNode.parse(data, namespaces: false)
        guard let content = document.descendants(name: "Content").first else {
            throw ImportError.unreadable("Not a Final Draft script")
        }
        var output = ""
        for paragraph in content.descendants(name: "Paragraph") {
            let text = fdxText(paragraph)
            if text.isEmpty {
                continue
            }
            switch paragraph.attributes["Type"] ?? "" {
            case "ScriptScene Heading":
                blank(&output)
                output += text + "\n"
            case "Character":
                blank(&output)
                output += JText.trim(continued.replaceAll(text, " ")) + ":\n"
            case "Dialogue", "Lyrics":
                output += text + "\n"
            case "Parenthetical":
                output += note(outerParens.replaceAll(text, "")) + "\n"
            case "Transition", "Shot":
                // Camera and editing directions are not read in a rehearsal.
                break
            default:
                output += note(text) + "\n"
            }
        }
        return JText.trim(output)
    }

    private static func fdxText(_ paragraph: XmlNode) -> String {
        var text = ""
        for child in paragraph.children where child.name == "Text" {
            text += child.textContent
        }
        return JText.trim(whitespace.replaceAll(text.replacingOccurrences(of: "\u{00a0}", with: " "), " "))
    }

    // MARK: - Shared

    private static func note(_ text: String) -> String {
        let clean = JText.trim(whitespace.replaceAll(ScriptScene.balanced(plain(text)), " "))
        return clean.isEmpty ? "" : "(" + clean + ")"
    }

    private static func blank(_ output: inout String) {
        if !output.isEmpty && !output.hasSuffix("\n\n") {
            output += "\n"
        }
    }
}

/// A small element tree read with Foundation's XMLParser, enough for Word and Final Draft files.
/// External entities are never resolved.
final class XmlNode {
    let name: String
    let local: String
    let namespace: String?
    let attributes: [String: String]
    var children: [XmlNode] = []
    var text = ""
    weak var parent: XmlNode?

    init(name: String, local: String, namespace: String?, attributes: [String: String]) {
        self.name = name
        self.local = local
        self.namespace = namespace
        self.attributes = attributes
    }

    var textContent: String {
        var value = ""
        collectText(into: &value)
        return value
    }

    private func collectText(into value: inout String) {
        value += text
        for child in children {
            child.collectText(into: &value)
        }
    }

    /// Text and child elements are kept in order: each node's `text` holds only the text before
    /// its first child; the text after a child is attached to an anonymous text node.
    fileprivate func appendText(_ value: String) {
        if children.isEmpty {
            text += value
        } else if let last = children.last, last.name == "#text" {
            last.text += value
        } else {
            let node = XmlNode(name: "#text", local: "#text", namespace: nil, attributes: [:])
            node.text = value
            node.parent = self
            children.append(node)
        }
    }

    func attribute(local: String) -> String? {
        if let value = attributes[local] {
            return value
        }
        for (key, value) in attributes where key.hasSuffix(":" + local) {
            return value
        }
        return nil
    }

    /// Every element below this one with the given local name and namespace, in document order.
    func descendants(local: String, namespace: String) -> [XmlNode] {
        var found: [XmlNode] = []
        walk { node in
            if node.local == local && node.namespace == namespace {
                found.append(node)
            }
        }
        return found
    }

    func descendants(name: String) -> [XmlNode] {
        var found: [XmlNode] = []
        walk { node in
            if node.name == name {
                found.append(node)
            }
        }
        return found
    }

    private func walk(_ visit: (XmlNode) -> Void) {
        for child in children where child.name != "#text" {
            visit(child)
            child.walk(visit)
        }
    }

    static func parse(_ data: Data, namespaces: Bool) throws -> XmlNode {
        let builder = Builder()
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = namespaces
        parser.shouldResolveExternalEntities = false
        parser.delegate = builder
        if !parser.parse() || builder.failed {
            throw ImportError.unreadable(parser.parserError?.localizedDescription ?? "Invalid XML")
        }
        return builder.root
    }

    private final class Builder: NSObject, XMLParserDelegate {
        let root = XmlNode(name: "#document", local: "#document", namespace: nil, attributes: [:])
        lazy var current: XmlNode = root
        var failed = false

        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                    qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
            let node = XmlNode(name: qName ?? elementName, local: elementName,
                               namespace: namespaceURI?.isEmpty == false ? namespaceURI : nil,
                               attributes: attributeDict)
            node.parent = current
            current.children.append(node)
            current = node
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?,
                    qualifiedName qName: String?) {
            current = current.parent ?? root
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            current.appendText(string)
        }

        func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
            current.appendText(String(decoding: CDATABlock, as: UTF8.self))
        }

        func parser(_ parser: XMLParser, parseErrorOccurred parseError: Error) {
            failed = true
        }
    }
}

/// Reads one file from a ZIP archive (a Word document) without unpacking the rest.
enum Zip {
    static func entry(named target: String, in data: Data, limit: Int) throws -> Data? {
        let bytes = [UInt8](data)
        func u16(_ at: Int) -> Int {
            at + 1 < bytes.count ? Int(bytes[at]) | Int(bytes[at + 1]) << 8 : 0
        }
        func u32(_ at: Int) -> Int {
            at + 3 < bytes.count ? u16(at) | u16(at + 2) << 16 : 0
        }
        // The end of central directory record sits in the last 64 KiB.
        var end = -1
        var at = bytes.count - 22
        let lowest = max(0, bytes.count - 22 - 65_535)
        while at >= lowest {
            if u32(at) == 0x0605_4b50 {
                end = at
                break
            }
            at -= 1
        }
        guard end >= 0 else {
            throw ImportError.unreadable("Not a ZIP archive")
        }
        let count = u16(end + 10)
        var entry = u32(end + 16)
        for _ in 0..<count {
            guard u32(entry) == 0x0201_4b50 else {
                throw ImportError.unreadable("Damaged ZIP archive")
            }
            let method = u16(entry + 10)
            let compressed = u32(entry + 20)
            let size = u32(entry + 24)
            let nameLength = u16(entry + 28)
            let extraLength = u16(entry + 30)
            let commentLength = u16(entry + 32)
            let local = u32(entry + 42)
            let nameStart = entry + 46
            guard nameStart + nameLength <= bytes.count else {
                throw ImportError.unreadable("Damaged ZIP archive")
            }
            let name = String(decoding: bytes[nameStart..<(nameStart + nameLength)], as: UTF8.self)
            if name == target {
                if size > limit {
                    throw ImportError.lengthLimit
                }
                let dataStart = local + 30 + u16(local + 26) + u16(local + 28)
                guard u32(local) == 0x0403_4b50, dataStart + compressed <= bytes.count else {
                    throw ImportError.unreadable("Damaged ZIP archive")
                }
                let payload = Array(bytes[dataStart..<(dataStart + compressed)])
                switch method {
                case 0:
                    return Data(payload)
                case 8:
                    return try inflate(payload, size: size)
                default:
                    throw ImportError.unreadable("Unsupported ZIP compression")
                }
            }
            entry = nameStart + nameLength + extraLength + commentLength
        }
        return nil
    }

    private static func inflate(_ payload: [UInt8], size: Int) throws -> Data {
        #if canImport(Compression)
        let capacity = max(size, 1)
        var output = [UInt8](repeating: 0, count: capacity)
        let written = payload.withUnsafeBufferPointer { source in
            output.withUnsafeMutableBufferPointer { target in
                compression_decode_buffer(target.baseAddress!, capacity, source.baseAddress!, payload.count,
                                          nil, COMPRESSION_ZLIB)
            }
        }
        guard written == size else {
            throw ImportError.unreadable("Could not unpack the document")
        }
        return Data(output)
        #else
        throw ImportError.unreadable("Compressed documents are not supported here")
        #endif
    }
}
