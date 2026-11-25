import Foundation

/// Turns text recognised on a scanned page into script rows: reading order across a two-page book
/// spread, no running heads or page numbers, words split by a line-end hyphen joined again and
/// typical recognition slips in role cues, cast lists and scene numbers repaired.
public enum OcrText {
    /// Specks read as punctuation in the margin before a line.
    private static let leadingNoise = JRegex("^(?:[,.;:'`´’‘©®•·|~^_°*¨]+\\s*)+")
    private static let leadingDashNoise = JRegex("^[-‒–—−]\\s+(?=\\p{Ll})")
    /// "ORGON. — Text", "MADAM PERNELLE, — Text", "ORGON.— Text", "M. LOYAL. —. Text".
    private static let dashCue = JRegex(
        "^(\\p{Lu}[\\p{Lu}\\p{M} .'’]{0,38}\\p{Lu})\\s*[.,]?\\s*[-‒–—−]{1,2}\\s*[.,;]?\\s*(.*)$")
    private static let nameList = JRegex(
        "^\\p{Lu}[\\p{Lu}\\p{M} .'’-]*(?:\\s*,+\\s*\\p{Lu}[\\p{Lu}\\p{M} .'’-]*)*[,.]*$")
    private static let numberedHeading = JRegex("^(\\p{L}+)\\s+([IVXlH1|!]{1,5})\\.?$")
    /// A speck read as a letter, digit or comma after the end of a sentence: "derecesi ! N".
    private static let trailingNoise = JRegex("(?<=[.!?…»”\"])(?:\\s+[^\\s\\p{L}\\p{N}]+|\\s+[\\p{L}\\p{N}])$")
    private static let numberToken = JRegex("\\d{1,4}")
    private static let colonCue = JRegex("^([^:]{2,40}):\\s")
    private static let dashes = JRegex("[-‒–—−]")
    private static let whitespace = JRegex("\\s+")
    private static let splitWord = JRegex(".*\\p{L}[-‐‑¬]$", dotAll: false)
    private static let listSeparators = JRegex("\\s*[,;]+(?:\\s*[,;]+)*\\s*")
    private static let listEnd = JRegex("[,\\s]+$")
    private static let hasSeparator = JRegex(".*[,;].*")
    private static let misreadNumber = JRegex(".*[lH|!].*")
    private static let hasOne = JRegex(".*1.*")
    private static let hasRoman = JRegex(".*[IVX].*")
    private static let oneLike = JRegex("[l1|!]")
    private static let trailingPeriod = JRegex("[.]$")
    private static let marks = JRegex("\\p{M}+")

    /// One line the recogniser found on a page image, in image pixels.
    public struct Line: Sendable {
        public let text: String
        public let left: Int
        public let top: Int
        public let right: Int
        public let bottom: Int

        public init(text: String, left: Int, top: Int, right: Int, bottom: Int) {
            self.text = text
            self.left = left
            self.top = top
            self.right = right
            self.bottom = bottom
        }

        var height: Int {
            max(1, bottom - top)
        }

        var center: Int {
            (left + right) / 2
        }
    }

    /// Script rows for one page image; an empty row marks a paragraph gap.
    public static func pageRows(_ found: [Line], width: Int) -> [String] {
        var lines: [Line] = []
        for line in found {
            let text = JText.trim(line.text)
            if JText.hasLetterOrDigit(text) {
                lines.append(Line(text: text, left: line.left, top: line.top, right: line.right, bottom: line.bottom))
            }
        }
        var rows: [String] = []
        for column in columns(lines, width: width) {
            let columnRows = columnRows(column)
            if !rows.isEmpty && !columnRows.isEmpty {
                rows.append("")
            }
            rows.append(contentsOf: columnRows)
        }
        return clean(rows)
    }

    /// Splits a page image into its two halves when it holds a book spread.
    private static func columns(_ lines: [Line], width: Int) -> [[Line]] {
        if lines.count < 6 || width <= 0 {
            return [lines]
        }
        var covered = [Bool](repeating: false, count: width)
        for line in lines {
            let from = max(0, line.left)
            let to = min(width, line.right)
            if from < to {
                for x in from..<to {
                    covered[x] = true
                }
            }
        }
        var bestStart = -1
        var bestLength = 0
        let from = width * 3 / 10
        let to = width * 7 / 10
        var x = from
        while x < to {
            if !covered[x] {
                let start = x
                while x < to && !covered[x] {
                    x += 1
                }
                if x - start > bestLength {
                    bestStart = start
                    bestLength = x - start
                }
            }
            x += 1
        }
        if bestLength < max(4, width / 100) {
            return [lines]
        }
        let gutter = bestStart + bestLength / 2
        let left = lines.filter { $0.center < gutter }
        let right = lines.filter { $0.center >= gutter }
        if left.count < 3 || right.count < 3 {
            return [lines]
        }
        return [left, right]
    }

    private static func columnRows(_ column: [Line]) -> [String] {
        let sorted = column.enumerated().sorted { a, b in
            if a.element.top != b.element.top {
                return a.element.top < b.element.top
            }
            if a.element.left != b.element.left {
                return a.element.left < b.element.left
            }
            return a.offset < b.offset
        }.map(\.element)

        // Pieces of one printed line can come back as separate lines; put them back together.
        var visual: [[Line]] = []
        for line in sorted {
            if let last = visual.last, overlaps(last, line) {
                visual[visual.count - 1].append(line)
            } else {
                visual.append([line])
            }
        }

        let heights = visual.map { rowBottom($0) - rowTop($0) }.sorted()
        let typical = heights.isEmpty ? 1 : max(1, heights[heights.count / 2])

        var rows: [String] = []
        var previousBottom = Int.min
        for i in 0..<visual.count {
            let row = visual[i].enumerated().sorted { a, b in
                a.element.left != b.element.left ? a.element.left < b.element.left : a.offset < b.offset
            }.map(\.element)
            let value = row.map(\.text).joined(separator: " ")
            if (i == 0 || i == visual.count - 1) && isRunningHead(value) {
                continue
            }
            if previousBottom != Int.min && rowTop(row) - previousBottom > typical * 6 / 5 && !rows.isEmpty {
                rows.append("")
            }
            rows.append(value)
            previousBottom = rowBottom(row)
        }
        return rows
    }

    /// A piece beside the row, at the same height, rather than the next line below it.
    private static func overlaps(_ row: [Line], _ line: Line) -> Bool {
        for piece in row {
            let sharedWidth = min(piece.right, line.right) - max(piece.left, line.left)
            if sharedWidth > min(piece.right - piece.left, line.right - line.left) / 4 {
                return false
            }
        }
        let top = rowTop(row)
        let bottom = rowBottom(row)
        let shared = min(bottom, line.bottom) - max(top, line.top)
        return shared * 5 > min(bottom - top, line.height) * 3
    }

    private static func rowTop(_ row: [Line]) -> Int {
        row.map(\.top).min() ?? Int.max
    }

    private static func rowBottom(_ row: [Line]) -> Int {
        row.map(\.bottom).max() ?? Int.min
    }

    /// "84 TARTUFFE", "Perde V Sahne III 85", "- 12 -": a book's running head or foot.
    public static func isRunningHead(_ text: String) -> Bool {
        let plain = JText.trim(dashes.replaceAll(leadingNoise.replaceFirst(JText.trim(text), ""), " "))
        if ScriptScene.isPageNumber(plain) {
            return true
        }
        let words = whitespace.split(plain)
        guard let first = words.first, let last = words.last else {
            return false
        }
        return words.count <= 8 && (numberToken.isMatch(first) || numberToken.isMatch(last))
    }

    /// Repairs rows read from one page; empty rows are kept as paragraph gaps.
    public static func clean(_ rows: [String]) -> [String] {
        var cleaned: [String] = []
        var names: String?
        for raw in rows {
            let row = cleanRow(raw)
            if row.isEmpty {
                names = flushNames(&cleaned, names)
                if let last = cleaned.last, !last.isEmpty {
                    cleaned.append("")
                }
                continue
            }
            if isNameList(row) {
                names = names.map { $0 + " " + row } ?? row
                continue
            }
            names = flushNames(&cleaned, names)
            let previous = cleaned.last ?? ""
            if splitWord.isMatch(previous), let first = row.unicodeScalars.first, JText.isLowerCase(first) {
                // "zor-" + "balıkla iş yürür mü?" was one word split at the line end.
                cleaned[cleaned.count - 1] = JText.sub(previous, 0, JText.length(previous) - 1) + row
            } else {
                cleaned.append(row)
            }
        }
        _ = flushNames(&cleaned, names)
        while let last = cleaned.last, last.isEmpty {
            cleaned.removeLast()
        }
        return cleaned
    }

    private static func flushNames(_ rows: inout [String], _ names: String?) -> String? {
        if let names {
            // The characters on stage at the start of a scene: a note, not a role.
            let list = listEnd.replaceFirst(whitespace.replaceAll(listSeparators.replaceAll(names, ", "), " "), "")
            rows.append("*" + list + "*")
        }
        return nil
    }

    private static func isNameList(_ row: String) -> Bool {
        hasSeparator.isMatch(row) && nameList.isMatch(JText.trim(row.replacingOccurrences(of: ";", with: ",")))
            && row == JText.upperTurkish(row) && !ScriptScene.isHeading(row)
    }

    public static func cleanRow(_ raw: String) -> String {
        var row = JText.trim(whitespace.replaceAll(raw.replacingOccurrences(of: "\u{00a0}", with: " "), " "))
        row = leadingNoise.replaceFirst(row, "")
        row = leadingDashNoise.replaceFirst(row, "")
        row = trailingNoise.replaceFirst(row, "")
        if row.isEmpty {
            return row
        }

        if let heading = numberedHeading.matches(row) {
            let number = heading.string(2)
            if misreadNumber.isMatch(number) || (hasOne.isMatch(number) && hasRoman.isMatch(number)) {
                let fixed = heading.string(1) + " "
                    + oneLike.replaceAll(number.replacingOccurrences(of: "H", with: "II"), "I")
                if ScriptScene.isHeading(fixed) {
                    return fixed
                }
            }
            if ScriptScene.isHeading(row) {
                return row
            }
        }

        if let cue = dashCue.matches(row) {
            let name = JText.trim(whitespace.replaceAll(cue.string(1), " "))
            if ScriptScene.isRoleName(name) && name == JText.upperTurkish(name) && !ScriptScene.isHeading(name) {
                return name + ": " + JText.trim(cue.string(2))
            }
        }
        return row
    }

    /// Joins role names that differ by one misread letter ("CLBANTE" and "CLEANTE") into the
    /// spelling read most often. The names in cast lists count as readings too.
    public static func unifyRoles(_ text: String) -> String {
        let rows = text.components(separatedBy: "\n")
        var counts: [String: Int] = [:]
        var order: [String] = []
        func count(_ name: String) {
            if counts[name] == nil {
                order.append(name)
            }
            counts[name, default: 0] += 1
        }
        for row in rows {
            if let cue = colonCue.find(row), ScriptScene.isRoleName(JText.trim(cue.string(1))) {
                count(JText.trim(cue.string(1)))
            } else if JText.length(row) > 2 && row.hasPrefix("*") && row.hasSuffix("*") {
                let list = JText.sub(row, 1, JText.length(row) - 1)
                if isNameList(list) {
                    for name in list.components(separatedBy: ",") {
                        let trimmed = JText.trim(trailingPeriod.replaceAll(name, ""))
                        if !trimmed.isEmpty {
                            count(trimmed)
                        }
                    }
                }
            }
        }
        let spellings = order
        var replacements: [String: String] = [:]
        for i in 0..<spellings.count {
            let rare = spellings[i]
            let rareCount = counts[rare] ?? 0
            var best: String?
            var bestCount = 0
            for j in 0..<spellings.count {
                let common = spellings[j]
                let commonCount = counts[common] ?? 0
                // A tie only joins spellings that differ in accents ("ELMİRE", "ELMIRE"): the first wins.
                let wins = commonCount > rareCount
                    || (commonCount == rareCount && j < i && fold(rare) == fold(common))
                if j != i && wins && commonCount > bestCount && JText.length(rare) >= 4
                    && close(fold(rare), fold(common)) {
                    best = common
                    bestCount = commonCount
                }
            }
            if let best {
                replacements[rare] = best
            }
        }
        for (key, value) in replacements {
            var target = value
            var hops = 0
            while let next = replacements[target], hops < spellings.count {
                target = next
                hops += 1
            }
            replacements[key] = target
        }
        if replacements.isEmpty {
            return text
        }
        var output: [String] = []
        for var row in rows {
            if let cue = colonCue.find(row), let replacement = replacements[JText.trim(cue.string(1))] {
                row = replacement + JText.sub(row, JText.length(cue.string(1)))
            }
            output.append(row)
        }
        return output.joined(separator: "\n")
    }

    /// Where a recognised line ended up in the script text: the UTF-16 start and end of the match
    /// at or after `from` (or else anywhere), or nil when it cannot be found.
    public static func locate(_ text: String, _ recognised: String, from: Int) -> (Int, Int)? {
        var key = cleanRow(recognised)
        if splitWord.isMatch(key) {
            let space = JText.lastIndexOf(key, " ")
            key = space > 0 ? JText.sub(key, 0, space) : JText.sub(key, 0, JText.length(key) - 1)
        }
        var keys = [key]
        if let cue = colonCue.find(key), JText.length(key) > cue.end() {
            keys.append(JText.sub(key, cue.end()))
        }
        let words = key.components(separatedBy: " ")
        if words.count >= 6 {
            keys.append(words[0..<(words.count / 2)].joined(separator: " "))
        }
        for candidate in keys {
            if JText.length(JText.trim(candidate)) < 3 {
                continue
            }
            var start = JText.indexOf(text, candidate, from: max(0, min(from, JText.length(text))))
            if start < 0 {
                start = JText.indexOf(text, candidate)
            }
            if start >= 0 {
                return (start, start + JText.length(candidate))
            }
        }
        return nil
    }

    private static func fold(_ value: String) -> String {
        JText.fold(value)
    }

    /// Equal, or one letter replaced, added or left out.
    static func close(_ a: String, _ b: String) -> Bool {
        if a == b {
            return true
        }
        let x = Array(a.utf16)
        let y = Array(b.utf16)
        if abs(x.count - y.count) > 1 {
            return false
        }
        var i = 0
        while i < x.count && i < y.count && x[i] == y[i] {
            i += 1
        }
        var j = 0
        while j < x.count - i && j < y.count - i && x[x.count - 1 - j] == y[y.count - 1 - j] {
            j += 1
        }
        return x.count - i - j <= 1 && y.count - i - j <= 1
    }
}
