import Foundation

// The Android app's text rules are written against Java strings: UTF-16 indexes, Java's
// trim(), whole-string regex matches and ICU-compatible patterns. These helpers keep the
// Swift port reading text exactly the same way, so a script parses identically on both phones.

/// A compiled pattern with Java's `matches()`, `find()`, `replaceFirst()` and `replaceAll()`.
public final class JRegex: @unchecked Sendable {
    let pattern: NSRegularExpression
    /// The same pattern anchored at both ends, for Java's whole-string `matches()`.
    let whole: NSRegularExpression

    public init(_ source: String, caseInsensitive: Bool = false, dotAll: Bool = false) {
        var options: NSRegularExpression.Options = []
        if caseInsensitive {
            options.insert(.caseInsensitive)
        }
        if dotAll {
            options.insert(.dotMatchesLineSeparators)
        }
        do {
            pattern = try NSRegularExpression(pattern: source, options: options)
            whole = try NSRegularExpression(pattern: "\\A(?:" + source + ")\\z", options: options)
        } catch {
            fatalError("Invalid pattern \(source): \(error)")
        }
    }

    /// Java's `matcher(text).matches()`: the whole text must match.
    public func matches(_ text: String) -> JMatch? {
        let ns = text as NSString
        guard let result = whole.firstMatch(in: text, options: [], range: NSRange(location: 0, length: ns.length)) else {
            return nil
        }
        return JMatch(result: result, text: ns)
    }

    public func isMatch(_ text: String) -> Bool {
        matches(text) != nil
    }

    /// Java's `matcher(text).find()`, starting at a UTF-16 offset.
    public func find(_ text: String, from: Int = 0) -> JMatch? {
        let ns = text as NSString
        let start = max(0, min(from, ns.length))
        guard let result = pattern.firstMatch(in: text, options: [],
                                              range: NSRange(location: start, length: ns.length - start)) else {
            return nil
        }
        return JMatch(result: result, text: ns)
    }

    public func contains(_ text: String) -> Bool {
        find(text) != nil
    }

    public func all(_ text: String) -> [JMatch] {
        let ns = text as NSString
        return pattern.matches(in: text, options: [], range: NSRange(location: 0, length: ns.length))
            .map { JMatch(result: $0, text: ns) }
    }

    public func replaceFirst(_ text: String, _ template: String) -> String {
        let ns = text as NSString
        guard let result = pattern.firstMatch(in: text, options: [], range: NSRange(location: 0, length: ns.length)) else {
            return text
        }
        let replacement = pattern.replacementString(for: result, in: text, offset: 0, template: template)
        return ns.replacingCharacters(in: result.range, with: replacement)
    }

    public func replaceAll(_ text: String, _ template: String) -> String {
        let ns = text as NSString
        return pattern.stringByReplacingMatches(in: text, options: [], range: NSRange(location: 0, length: ns.length),
                                                withTemplate: template)
    }

    /// Java's `split(regex)`: trailing empty strings are dropped unless `keepTrailing` is set.
    public func split(_ text: String, keepTrailing: Bool = false) -> [String] {
        let ns = text as NSString
        var parts: [String] = []
        var start = 0
        for match in pattern.matches(in: text, options: [], range: NSRange(location: 0, length: ns.length)) {
            if match.range.length == 0 && match.range.location == 0 {
                continue
            }
            if match.range.length == 0 && match.range.location >= ns.length {
                continue
            }
            parts.append(ns.substring(with: NSRange(location: start, length: match.range.location - start)))
            start = match.range.location + match.range.length
        }
        parts.append(ns.substring(from: start))
        if !keepTrailing {
            while let last = parts.last, last.isEmpty, parts.count > 1 {
                parts.removeLast()
            }
            if parts == [""] && ns.length > 0 {
                return []
            }
        }
        return parts
    }
}

/// One regex match with Java-style group access by UTF-16 offsets.
public struct JMatch {
    let result: NSTextCheckingResult
    let text: NSString

    public func group(_ index: Int = 0) -> String? {
        guard index < result.numberOfRanges else {
            return nil
        }
        let range = result.range(at: index)
        return range.location == NSNotFound ? nil : text.substring(with: range)
    }

    /// The group's text, or an empty string when it did not take part (Java code checks for null).
    public func string(_ index: Int) -> String {
        group(index) ?? ""
    }

    public func start(_ index: Int = 0) -> Int {
        let range = result.range(at: index)
        return range.location == NSNotFound ? -1 : range.location
    }

    public func end(_ index: Int = 0) -> Int {
        let range = result.range(at: index)
        return range.location == NSNotFound ? -1 : range.location + range.length
    }
}

public enum JText {
    public static let turkish = Locale(identifier: "tr_TR")
    private static let marks = JRegex("\\p{M}+")
    private static let spaces = JRegex("\\s+")

    /// Java's `String.trim()`: removes characters up to U+0020 from both ends.
    public static func trim(_ value: String) -> String {
        let ns = value as NSString
        var start = 0
        var end = ns.length
        while start < end && ns.character(at: start) <= 0x20 {
            start += 1
        }
        while end > start && ns.character(at: end - 1) <= 0x20 {
            end -= 1
        }
        if start == 0 && end == ns.length {
            return value
        }
        return ns.substring(with: NSRange(location: start, length: end - start))
    }

    /// Upper case without accents: "Perde Sonu" → "PERDE SONU", "MUHÂVERE" → "MUHAVERE".
    public static func fold(_ value: String) -> String {
        marks.replaceAll(value.uppercased(with: Locale(identifier: "en_US_POSIX")).decomposedStringWithCanonicalMapping, "")
    }

    public static func upperTurkish(_ value: String) -> String {
        value.uppercased(with: turkish)
    }

    public static func lowerTurkish(_ value: String) -> String {
        value.lowercased(with: turkish)
    }

    public static func upperRoot(_ value: String) -> String {
        value.uppercased(with: Locale(identifier: "en_US_POSIX"))
    }

    public static func lowerRoot(_ value: String) -> String {
        value.lowercased(with: Locale(identifier: "en_US_POSIX"))
    }

    /// Collapses runs of white space into single spaces.
    public static func collapse(_ value: String) -> String {
        spaces.replaceAll(value, " ")
    }

    public static func length(_ value: String) -> Int {
        (value as NSString).length
    }

    /// Java's `substring(start, end)` by UTF-16 offsets.
    public static func sub(_ value: String, _ start: Int, _ end: Int? = nil) -> String {
        let ns = value as NSString
        let stop = min(end ?? ns.length, ns.length)
        let from = max(0, min(start, stop))
        return ns.substring(with: NSRange(location: from, length: stop - from))
    }

    /// Java's `indexOf(text, from)`, or -1.
    public static func indexOf(_ value: String, _ needle: String, from: Int = 0) -> Int {
        let ns = value as NSString
        let start = max(0, min(from, ns.length))
        let range = ns.range(of: needle, options: [.literal], range: NSRange(location: start, length: ns.length - start))
        return range.location == NSNotFound ? -1 : range.location
    }

    public static func lastIndexOf(_ value: String, _ needle: String) -> Int {
        let range = (value as NSString).range(of: needle, options: [.literal, .backwards])
        return range.location == NSNotFound ? -1 : range.location
    }

    /// The UTF-16 unit at an offset.
    public static func unit(_ value: String, _ index: Int) -> unichar {
        (value as NSString).character(at: index)
    }

    public static func isDigit(_ unit: unichar) -> Bool {
        guard let scalar = Unicode.Scalar(unit) else {
            return false
        }
        return scalar.properties.numericType == .decimal
    }

    public static func isLetter(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter:
            return true
        default:
            return false
        }
    }

    public static func isLetterOrDigit(_ scalar: Unicode.Scalar) -> Bool {
        isLetter(scalar) || scalar.properties.numericType == .decimal
    }

    public static func isLowerCase(_ scalar: Unicode.Scalar) -> Bool {
        scalar.properties.isLowercase
    }

    /// Number of letters, as Java's `codePoints().filter(Character::isLetter).count()`.
    public static func letterCount(_ value: String) -> Int {
        value.unicodeScalars.reduce(0) { $0 + (isLetter($1) ? 1 : 0) }
    }

    public static func hasLetter(_ value: String) -> Bool {
        value.unicodeScalars.contains(where: isLetter)
    }

    public static func hasLetterOrDigit(_ value: String) -> Bool {
        value.unicodeScalars.contains(where: isLetterOrDigit)
    }

    /// Rows split at any line break, keeping empty rows (Java `split("\\r\\n|[\\r\\n]", -1)`).
    public static func rows(_ value: String, keepTrailing: Bool) -> [String] {
        let normalized = value.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        var rows = normalized.components(separatedBy: "\n")
        if !keepTrailing {
            while let last = rows.last, last.isEmpty, rows.count > 0 {
                rows.removeLast()
            }
        }
        return rows
    }
}
