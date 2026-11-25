import Foundation

/// Finds the gutter of a page printed in two columns, such as a book spread saved as one PDF page,
/// so the left column can be read before the right one instead of line by line across both.
public enum TextColumns {
    /// The x position of the gutter, or -1 for a single column. `rows` holds, for each line,
    /// the left and right edges of its words, in the same units as `width`.
    public static func gutter(_ rows: [[(Float, Float)]], width: Float) -> Float {
        let cells = Int(width.rounded(.up))
        if cells <= 0 || rows.count < 6 {
            return -1
        }
        var covered = [Bool](repeating: false, count: cells)
        for row in rows {
            for word in row {
                let from = max(0, Int(word.0.rounded(.down)))
                let to = min(cells, Int(word.1.rounded(.up)))
                if from < to {
                    for x in from..<to {
                        covered[x] = true
                    }
                }
            }
        }
        let from = cells * 3 / 10
        let to = cells * 7 / 10
        var bestStart = -1
        var bestLength = 0
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
        // Wider than any space between words, even in justified text.
        if bestLength < max(12, cells / 50) {
            return -1
        }
        let gutter = Float(bestStart) + Float(bestLength) / 2
        // Both sides need real text: short lines in one column also leave the middle empty.
        var left = 0
        var right = 0
        for row in rows {
            var hasLeft = false
            var hasRight = false
            for word in row {
                hasLeft = hasLeft || word.1 <= gutter
                hasRight = hasRight || word.0 >= gutter
            }
            left += hasLeft ? 1 : 0
            right += hasRight ? 1 : 0
        }
        let enough = max(3, rows.count / 4)
        return left >= enough && right >= enough ? gutter : -1
    }
}
