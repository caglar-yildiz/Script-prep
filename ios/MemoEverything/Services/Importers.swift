import Foundation
import ImageIO
import MemoCore
import PDFKit
import UIKit
import Vision

/// Imported text and what the preview should say about it.
struct ImportResult {
    let title: String
    let text: String
    let scannedPages: Int
    let firstPage: Int
    let lastPage: Int
    let requestedLastPage: Int
    /// Read from photos rather than a file; `scannedPages` counts the photos.
    var photos = false
    /// Converted from Word, Fountain or Final Draft, so worth a look at the detected roles.
    var converted = false

    /// The text limit was reached before the last page asked for.
    var cutShort: Bool {
        lastPage < requestedLastPage
    }
}

/// Lets the import thread see the cancel button.
final class CancelFlag {
    private let lock = NSLock()
    private var value = false

    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func cancel() {
        lock.lock()
        value = true
        lock.unlock()
    }

    func check() throws {
        if isCancelled {
            throw ImportError.cancelled
        }
    }
}

enum ScriptImport {
    static let maxScriptLength = 150_000

    /// Reads a TXT, Word (DOCX), Fountain or Final Draft (FDX) file.
    static func readText(_ url: URL, lengthLimit: Int) throws -> ImportResult {
        let name = url.lastPathComponent
        let accessing = url.startAccessingSecurityScopedResource()
        defer {
            if accessing {
                url.stopAccessingSecurityScopedResource()
            }
        }
        let data = try Data(contentsOf: url, options: [.mappedIfSafe])
        var kind = ScriptFormats.kindOf(name)
        if kind == .text {
            kind = ScriptFormats.sniff(data)
        }
        let text: String
        switch kind {
        case .docx:
            text = try ScriptFormats.docx(data)
        case .fdx:
            text = try ScriptFormats.fdx(data)
        case .fountain:
            text = ScriptFormats.fountain(try decode(data, limit: lengthLimit * 2))
        case .text:
            text = try decode(data, limit: lengthLimit)
        }
        if JText.length(text) > lengthLimit {
            throw ImportError.lengthLimit
        }
        if kind != .text && JText.trim(text).isEmpty {
            throw ImportError.unreadable("No script text")
        }
        let title = JRegex("(?i)\\.(?:txt|text|docx|fountain|spmd|fdx)$").replaceFirst(name, "")
        return ImportResult(title: title, text: text, scannedPages: 0, firstPage: 0, lastPage: 0,
                            requestedLastPage: 0, converted: kind != .text)
    }

    /// UTF-8 text without a byte-order mark; anything else is refused like on Android.
    private static func decode(_ data: Data, limit: Int) throws -> String {
        if data.count > (limit + 1) * 4 {
            throw ImportError.lengthLimit
        }
        guard var text = String(data: data, encoding: .utf8) else {
            throw ImportError.unreadable("Not UTF-8")
        }
        if text.hasPrefix("\u{FEFF}") {
            text.removeFirst()
        }
        if JText.length(text) > limit {
            throw ImportError.lengthLimit
        }
        return text
    }
}

// MARK: - PDF

/// A picked PDF, copied and counted, waiting for the pages to read.
final class PdfSource {
    static let maxBytes = 100 * 1024 * 1024
    static let maxPages = 1000
    /// Most pages read in one import; a longer play is added in parts.
    static let maxRange = 200

    let url: URL
    let title: String
    let pageCount: Int

    private init(url: URL, title: String, pageCount: Int) {
        self.url = url
        self.title = title
        self.pageCount = pageCount
    }

    /// Copies the picked file into the cache so its pages can be counted and read later.
    static func open(_ picked: URL, cancel: CancelFlag) throws -> PdfSource {
        let cache = FileManager.default.temporaryDirectory
        // An import left by a closed app or a cancelled page choice is no longer needed.
        for leftover in (try? FileManager.default.contentsOfDirectory(at: cache, includingPropertiesForKeys: nil)) ?? []
            where leftover.lastPathComponent.hasPrefix("script-import-") {
            try? FileManager.default.removeItem(at: leftover)
        }
        let accessing = picked.startAccessingSecurityScopedResource()
        defer {
            if accessing {
                picked.stopAccessingSecurityScopedResource()
            }
        }
        let size = (try? picked.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
        if size > maxBytes {
            throw ImportError.lengthLimit
        }
        try cancel.check()
        let copy = cache.appendingPathComponent("script-import-\(UUID().uuidString).pdf")
        try FileManager.default.copyItem(at: picked, to: copy)
        guard let document = PDFDocument(url: copy), !document.isLocked else {
            try? FileManager.default.removeItem(at: copy)
            throw ImportError.unreadable("Cannot open the PDF")
        }
        if document.pageCount > maxPages {
            try? FileManager.default.removeItem(at: copy)
            throw ImportError.lengthLimit
        }
        let title = JRegex("(?i)\\.(?:txt|pdf)$").replaceFirst(picked.lastPathComponent, "")
        return PdfSource(url: copy, title: title, pageCount: document.pageCount)
    }

    func close() {
        try? FileManager.default.removeItem(at: url)
    }
}

/// Reads the text layer of PDF pages into script rows, the way the Android app reads them with
/// PDFBox: lines in position order, a bold name at the start of a line as a role cue, a line in
/// italics as a direction, running heads left out and two-column pages read column by column.
/// Pages without a text layer are read with on-device text recognition.
enum PdfScriptReader {
    private static let marginRows = 2
    /// A page with fewer letters than this in its text layer is treated as a scan.
    private static let scannedPageLetters = 20
    private static let boldName = JRegex(".*(?:bold|demi|black|heavy).*")
    private static let italicName = JRegex(".*(?:italic|oblique).*")
    private static let trailingSeparator = JRegex("\\s*[:‒–—−-]$")
    private static let trailingAside = JRegex("\\s*\\([^()]*\\)$")

    private struct Row {
        let text: String
        let formatted: String
    }

    private struct Part {
        var text: String
        var bold: Bool
        var italic: Bool
        var left: CGFloat
        var right: CGFloat
    }

    private struct Glyph {
        let unit: unichar
        let rect: CGRect
        let bold: Bool
        let italic: Bool
    }

    static func read(_ source: PdfSource, first: Int, last: Int, lengthLimit: Int,
                     progress: @escaping (Int, Int) -> Void, cancel: CancelFlag, review: OcrReview?) throws -> ImportResult {
        guard let document = PDFDocument(url: source.url) else {
            throw ImportError.unreadable("Cannot open the PDF")
        }
        var pages: [[Row]] = []
        var characters = 0
        for number in first...last {
            try cancel.check()
            guard let page = document.page(at: number - 1) else {
                break
            }
            let rows = autoreleasepool { textRows(page) }
            characters += rows.reduce(0) { $0 + JText.length($1.formatted) + 1 }
            pages.append(rows)
            if characters > lengthLimit * 2 && pages.count > 1 {
                // The page that was being read may not fit; the next import starts with it.
                pages.removeLast()
                break
            }
        }

        var scannedPages = 0
        let scanned = pages.indices.filter { index in
            pages[index].reduce(0) { $0 + JText.letterCount($1.text) } < scannedPageLetters
        }
        if !scanned.isEmpty {
            var done = 0
            for index in scanned {
                try cancel.check()
                progress(done, scanned.count)
                guard let page = document.page(at: first - 1 + index) else {
                    continue
                }
                let rows = try autoreleasepool { try Ocr.read(page: page, number: first + index, review: review) }
                done += 1
                let letters = rows.reduce(0) { $0 + JText.letterCount($1) }
                if letters > 0 {
                    pages[index] = rows.map { text in
                        let note = JText.length(text) > 2 && text.hasPrefix("*") && text.hasSuffix("*")
                        return Row(text: note ? JText.sub(text, 1, JText.length(text) - 1) : text, formatted: text)
                    }
                    scannedPages += 1
                }
                let length = pages[0...index].reduce(0) { $0 + $1.reduce(0) { $0 + JText.length($1.formatted) + 1 } }
                if length > lengthLimit && index > 0 {
                    pages.removeSubrange((index + 1)...)
                    break
                }
            }
        }
        let (text, included) = try scriptText(pages, lengthLimit: lengthLimit, scanned: scannedPages > 0)
        var title = source.title
        if first > 1 || last < source.pageCount {
            title = (title.isEmpty ? "" : title + " ") + "(\(first)–\(first + max(1, included) - 1))"
        }
        return ImportResult(title: title, text: text, scannedPages: scannedPages, firstPage: first,
                            lastPage: first + max(1, included) - 1, requestedLastPage: last)
    }

    // MARK: Text layer

    private static func textRows(_ page: PDFPage) -> [Row] {
        guard let string = page.string, !string.isEmpty else {
            return []
        }
        let ns = string as NSString
        let attributed = page.attributedString
        var glyphs: [Glyph] = []
        var styles: [String: (Bool, Bool)] = [:]
        for index in 0..<ns.length {
            let unit = ns.character(at: index)
            if unit == 10 || unit == 13 {
                continue
            }
            let rect = page.characterBounds(at: index)
            if rect.isNull || rect.isInfinite || (rect.width <= 0 && rect.height <= 0) {
                if unit == 32, let last = glyphs.last {
                    glyphs.append(Glyph(unit: 32, rect: CGRect(x: last.rect.maxX, y: last.rect.minY, width: 0,
                                                               height: last.rect.height), bold: false, italic: false))
                }
                continue
            }
            var bold = false
            var italic = false
            if let attributed, index < attributed.length,
               let font = attributed.attribute(.font, at: index, effectiveRange: nil) as? UIFont {
                if let known = styles[font.fontName] {
                    (bold, italic) = known
                } else {
                    let name = JText.lowerRoot(font.fontName)
                    let traits = font.fontDescriptor.symbolicTraits
                    bold = boldName.isMatch(name) || traits.contains(.traitBold)
                    italic = italicName.isMatch(name) || traits.contains(.traitItalic)
                    styles[font.fontName] = (bold, italic)
                }
            }
            glyphs.append(Glyph(unit: unit, rect: rect, bold: bold, italic: italic))
        }
        let lines = group(glyphs)
        let box = page.bounds(for: .cropBox)
        let rotated = page.rotation % 180 != 0
        let width = rotated ? box.height : box.width
        let spans = lines.map { line in line.filter { !JText.trim($0.text).isEmpty }.map { (Float($0.left - box.minX), Float($0.right - box.minX)) } }
            .filter { !$0.isEmpty }
        let gutter = CGFloat(TextColumns.gutter(spans, width: Float(width)))
        var rows: [Row] = []
        func add(_ line: [Part]?) {
            guard let line else {
                return
            }
            let joined = line.map(\.text).joined()
            let text = JText.trim(joined)
            if text.isEmpty {
                if let last = rows.last, !last.text.isEmpty {
                    rows.append(Row(text: "", formatted: ""))
                }
                return
            }
            rows.append(Row(text: text, formatted: addStyle(line, joined, text)))
        }
        if gutter < 0 {
            for line in lines {
                add(line)
            }
        } else {
            // Two pages side by side: the left column first, then the right one.
            let split = gutter + box.minX
            for line in lines {
                add(side(line, split, left: true))
            }
            add([])
            for line in lines {
                add(side(line, split, left: false))
            }
        }
        return rows
    }

    /// Groups glyphs into lines from the top of the page down, words left to right; an empty
    /// line marks a paragraph gap.
    private static func group(_ glyphs: [Glyph]) -> [[Part]] {
        // Top of the page first (PDF space grows upwards), then left to right.
        let sorted = glyphs.enumerated().sorted { a, b in
            if a.element.rect.midY != b.element.rect.midY {
                return a.element.rect.midY > b.element.rect.midY
            }
            if a.element.rect.minX != b.element.rect.minX {
                return a.element.rect.minX < b.element.rect.minX
            }
            return a.offset < b.offset
        }.map(\.element)
        var rows: [[Glyph]] = []
        for glyph in sorted {
            if let last = rows.last, let anchor = last.first(where: { $0.unit != 32 }) ?? last.first,
               abs(anchor.rect.midY - glyph.rect.midY) <= max(1, min(anchor.rect.height, max(glyph.rect.height, 1)) * 0.5) {
                rows[rows.count - 1].append(glyph)
            } else {
                rows.append([glyph])
            }
        }
        let rows2 = rows.map { $0.sorted { $0.rect.minX < $1.rect.minX } }
        let heights = rows2.compactMap { row in row.map(\.rect.height).max() }.sorted()
        let typical = heights.isEmpty ? 10 : heights[heights.count / 2]
        var lines: [[Part]] = []
        var previousTop: CGFloat?
        for row in rows2 {
            var parts: [Part] = []
            var word: Part?
            var lastRight: CGFloat?
            for glyph in row {
                let character = String(utf16CodeUnits: [glyph.unit], count: 1)
                if glyph.unit == 32 || glyph.unit == 9 {
                    if let current = word {
                        parts.append(current)
                        word = nil
                    }
                    continue
                }
                let gap = lastRight.map { glyph.rect.minX - $0 } ?? 0
                if var current = word, gap <= max(1.5, glyph.rect.height * 0.25) {
                    current.text += character
                    current.right = max(current.right, glyph.rect.maxX)
                    current.bold = current.bold && glyph.bold
                    current.italic = current.italic && glyph.italic
                    word = current
                } else {
                    if let current = word {
                        parts.append(current)
                    }
                    word = Part(text: character, bold: glyph.bold, italic: glyph.italic,
                                left: glyph.rect.minX, right: glyph.rect.maxX)
                }
                lastRight = glyph.rect.maxX
            }
            if let current = word {
                parts.append(current)
            }
            guard !parts.isEmpty else {
                continue
            }
            var spaced: [Part] = []
            for (index, part) in parts.enumerated() {
                if index > 0 {
                    spaced.append(Part(text: " ", bold: false, italic: false, left: .nan, right: .nan))
                }
                spaced.append(part)
            }
            let top = row.map(\.rect.maxY).max() ?? 0
            if let previousTop, previousTop - top > typical * 1.9, !lines.isEmpty {
                lines.append([])
            }
            previousTop = top
            lines.append(spaced)
        }
        return lines
    }

    /// The words of a line on one side of the gutter; spaces go with the word before them.
    private static func side(_ line: [Part], _ gutter: CGFloat, left: Bool) -> [Part]? {
        if line.isEmpty {
            return line
        }
        var kept: [Part] = []
        var onLeft = true
        var seenWord = false
        for part in line {
            if !part.left.isNaN {
                onLeft = (part.left + part.right) / 2 < gutter
                seenWord = true
            }
            if onLeft == left && (seenWord || !part.left.isNaN) {
                kept.append(part)
            }
        }
        return kept.contains { !JText.trim($0.text).isEmpty } ? kept : nil
    }

    private static func addStyle(_ parts: [Part], _ current: String, _ text: String) -> String {
        if ScriptScene.isHeading(text) {
            return text
        }
        var offset = 0
        var boldEnd = 0
        var leadingBold = true
        var allItalic = true
        for part in parts {
            offset += JText.length(part.text)
            if JText.trim(part.text).isEmpty {
                continue
            }
            allItalic = allItalic && part.italic
            leadingBold = leadingBold && part.bold
            if leadingBold {
                boldEnd = offset
            }
        }
        if boldEnd > 0 {
            let prefix = JText.trim(JText.sub(current, 0, boldEnd))
            let name = trailingAside.replaceFirst(trailingSeparator.replaceFirst(prefix, ""), "")
            if ScriptScene.isRoleName(JText.trim(name)) {
                let rest = JText.trim(JText.sub(current, boldEnd))
                return "**" + prefix + "**" + (rest.isEmpty ? "" : " " + rest)
            }
        }
        return allItalic ? "*" + text + "*" : text
    }

    private static func scriptText(_ pages: [[Row]], lengthLimit: Int, scanned: Bool) throws -> (String, Int) {
        var repeated: [String: Int] = [:]
        for rows in pages {
            var seen = Set<String>()
            for (i, row) in rows.enumerated() where isMargin(i, rows.count) && !row.text.isEmpty
                && seen.insert(row.text).inserted && !ScriptScene.isHeading(row.text) {
                repeated[row.text, default: 0] += 1
            }
        }
        var output = ""
        var foundText = false
        var included = 0
        for (p, rows) in pages.enumerated() {
            let pageStart = output
            var pageText = false
            for (i, row) in rows.enumerated() {
                let count = repeated[row.text] ?? 0
                if isMargin(i, rows.count) && (ScriptScene.isPageNumber(row.text)
                    || (count > 1 && !row.formatted.hasPrefix("**") && !looksLikeRole(row.text))) {
                    continue
                }
                pageText = pageText || !row.text.isEmpty
                output += row.formatted + "\n"
            }
            output += "\n"
            if JText.length(output) > lengthLimit {
                if p == 0 {
                    throw ImportError.lengthLimit
                }
                // Stop after the last whole page that fits; the rest is imported separately.
                output = pageStart
                break
            }
            foundText = foundText || pageText
            included = p + 1
        }
        if !foundText {
            throw ImportError.noText
        }
        let text = JText.trim(output)
        return (scanned ? OcrText.unifyRoles(text) : text, included)
    }

    private static func isMargin(_ index: Int, _ size: Int) -> Bool {
        index < marginRows || size - index <= marginRows
    }

    private static func looksLikeRole(_ text: String) -> Bool {
        ScriptScene.isRoleName(text) && text == JText.upperRoot(text)
    }
}

// MARK: - Text recognition

/// Reads scanned pages and photos with Apple's on-device text recognition (Vision). Page images
/// never leave the phone.
enum Ocr {
    /// Longest side of a rendered page; about 210 dpi for A4, enough for book print.
    private static let maxSide: CGFloat = 2500
    private static let maxScale: CGFloat = 300 / 72

    static func read(page: PDFPage, number: Int, review: OcrReview?) throws -> [String] {
        let box = page.bounds(for: .mediaBox)
        let rotated = page.rotation % 180 != 0
        let size = rotated ? CGSize(width: box.height, height: box.width) : box.size
        let scale = min(maxScale, maxSide / max(1, max(size.width, size.height)))
        let target = CGSize(width: max(1, (size.width * scale).rounded()), height: max(1, (size.height * scale).rounded()))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let image = UIGraphicsImageRenderer(size: target, format: format).image { context in
            // Pages render onto a transparent bitmap; recognition needs dark text on white.
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: target))
            context.cgContext.saveGState()
            context.cgContext.translateBy(x: 0, y: target.height)
            context.cgContext.scaleBy(x: scale, y: -scale)
            page.draw(with: .mediaBox, to: context.cgContext)
            context.cgContext.restoreGState()
        }
        guard let cgImage = image.cgImage else {
            throw ImportError.unreadable("Cannot render the page")
        }
        return try recognize(cgImage, number: number, review: review)
    }

    /// Script rows read from one image; unsure lines are kept for review.
    static func recognize(_ image: CGImage, number: Int, review: OcrReview?) throws -> [String] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        let wanted = ["tr-TR", "en-US", "es-ES", "de-DE", "fr-FR", "pt-BR", "it-IT"]
        let supported = (try? request.supportedRecognitionLanguages()) ?? []
        let languages = wanted.filter { supported.contains($0) }
        if !languages.isEmpty {
            request.recognitionLanguages = languages
        }
        if #available(iOS 16.0, *) {
            request.automaticallyDetectsLanguage = true
        }
        let handler = VNImageRequestHandler(cgImage: image, options: [:])
        try handler.perform([request])
        let width = image.width
        let height = image.height
        var lines: [OcrText.Line] = []
        for observation in request.results ?? [] {
            guard let candidate = observation.topCandidates(1).first else {
                continue
            }
            let box = observation.boundingBox
            let left = Int(box.minX * CGFloat(width))
            let right = Int(box.maxX * CGFloat(width))
            let top = Int((1 - box.maxY) * CGFloat(height))
            let bottom = Int((1 - box.minY) * CGFloat(height))
            lines.append(OcrText.Line(text: candidate.string, left: left, top: top, right: right, bottom: bottom))
            review?.add(image, box: CGRect(x: left, y: top, width: right - left, height: bottom - top),
                        text: candidate.string, confidence: candidate.confidence, page: number)
        }
        return OcrText.pageRows(lines, width: width)
    }
}

/// Reads script pages from photos; photos never leave the phone.
enum PhotoOcr {
    /// Most photos read in one import.
    static let maxPhotos = 60
    /// Photos are scaled down until their longest side is at most this; enough for book print.
    private static let maxSide: CGFloat = 3000

    static func read(_ photos: [Data], title: String, lengthLimit: Int, progress: @escaping (Int, Int) -> Void,
                     cancel: CancelFlag, review: OcrReview?) throws -> ImportResult {
        var text = ""
        var pages = 0
        var letters = false
        for (i, photo) in photos.enumerated() {
            try cancel.check()
            progress(i, photos.count)
            guard let image = autoreleasepool(invoking: { upright(photo) }) else {
                throw ImportError.unreadable("Not an image")
            }
            let rows = try Ocr.recognize(image, number: i + 1, review: review)
            let before = text
            for row in rows {
                text += row + "\n"
                letters = letters || JText.hasLetter(row)
            }
            text += "\n"
            if JText.length(text) > lengthLimit {
                if pages == 0 {
                    throw ImportError.lengthLimit
                }
                text = before
                break
            }
            pages += 1
        }
        if !letters {
            throw ImportError.noText
        }
        return ImportResult(title: title, text: OcrText.unifyRoles(JText.trim(text)), scannedPages: pages,
                            firstPage: 0, lastPage: 0, requestedLastPage: 0, photos: true)
    }

    /// The photo scaled down to a size recognition handles well, turned the way it was taken.
    static func upright(_ data: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            return nil
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxSide,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}

/// Keeps a cut-out of each line text recognition was unsure about, so it can be checked against
/// the page after the import. The cut-outs stay in the cache until the next import.
final class OcrReview {
    /// Lines recognised with less confidence than this are offered for review.
    static let lowConfidence: Float = 0.7
    private static let maxItems = 150
    private static let maxWidth = 1600

    struct Item {
        let text: String
        let image: URL
        let page: Int
    }

    private let folder: URL
    private let lock = NSLock()
    private var collected: [Item] = []

    /// Starts a new review and removes the cut-outs of the previous one.
    init() {
        folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ocr-review", isDirectory: true)
        try? FileManager.default.removeItem(at: folder)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    func add(_ page: CGImage, box: CGRect, text: String, confidence: Float, page number: Int) {
        lock.lock()
        defer { lock.unlock() }
        guard confidence >= 0, confidence < Self.lowConfidence, collected.count < Self.maxItems,
              !OcrText.cleanRow(text).isEmpty else {
            return
        }
        // Some room around the line shows the letters cut by a tight box and the line's context.
        let pad = max(8, box.height / 2)
        let crop = box.insetBy(dx: -pad, dy: -pad)
            .intersection(CGRect(x: 0, y: 0, width: page.width, height: page.height)).integral
        guard crop.width >= 4, crop.height >= 4, var image = page.cropping(to: crop) else {
            return
        }
        if image.width > Self.maxWidth {
            let height = max(1, image.height * Self.maxWidth / image.width)
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            let scaled = UIGraphicsImageRenderer(size: CGSize(width: Self.maxWidth, height: height), format: format)
                .image { _ in UIImage(cgImage: image).draw(in: CGRect(x: 0, y: 0, width: Self.maxWidth, height: height)) }
            if let cgImage = scaled.cgImage {
                image = cgImage
            }
        }
        let file = folder.appendingPathComponent("line-\(collected.count).jpg")
        guard let data = UIImage(cgImage: image).jpegData(compressionQuality: 0.85), (try? data.write(to: file)) != nil else {
            return
        }
        collected.append(Item(text: text, image: file, page: number))
    }

    var items: [Item] {
        lock.lock()
        defer { lock.unlock() }
        return collected
    }
}
