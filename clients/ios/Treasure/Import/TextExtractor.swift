import CryptoKit
import PDFKit
import UIKit
import Vision

/// What a file or set of photos turned into: plain text lines, read entirely on this device.
struct ExtractedDocument {
    var text: String
    var isCSV: Bool
    var hash: String        // SHA-256 of the file bytes; identifies the evidence record
    var name: String
    var mediaType: String
}

enum TextExtractor {
    /// A PDF's own text layer when it has one (exact), otherwise Vision OCR on the rendered page. Images go straight to Vision.
    static func extract(url: URL) async -> ExtractedDocument? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { return nil }
        let hash = digest(data), name = url.lastPathComponent
        switch url.pathExtension.lowercased() {
        case "csv", "txt":
            let text = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
            return ExtractedDocument(text: text, isCSV: url.pathExtension.lowercased() == "csv", hash: hash, name: name, mediaType: url.pathExtension.lowercased() == "csv" ? "text/csv" : "text/plain")
        case "pdf":
            let text = await Task.detached(priority: .userInitiated) { pdfText(data) }.value
            return ExtractedDocument(text: text, isCSV: false, hash: hash, name: name, mediaType: "application/pdf")
        default:
            guard let image = UIImage(data: data) else { return nil }
            let text = await recognize(image)
            return ExtractedDocument(text: text, isCSV: false, hash: hash, name: name, mediaType: "image/jpeg")
        }
    }

    static func extract(images: [UIImage], name: String) async -> ExtractedDocument {
        var pages: [String] = []
        var bytes = Data()
        for image in images {
            pages.append(await recognize(image))
            bytes.append(image.jpegData(compressionQuality: 0.8) ?? Data())
        }
        return ExtractedDocument(text: pages.joined(separator: "\n"), isCSV: false, hash: digest(bytes), name: name, mediaType: "image/jpeg")
    }

    private static func pdfText(_ data: Data) -> String {
        guard let pdf = PDFDocument(data: data) else { return "" }
        var pages: [String] = []
        for i in 0..<min(pdf.pageCount, 40) {
            guard let page = pdf.page(at: i) else { continue }
            let own = page.string ?? ""
            if own.trimmingCharacters(in: .whitespacesAndNewlines).count >= 20 { pages.append(own); continue }
            // A scanned page has no text layer: draw it and read it.
            let box = page.bounds(for: .mediaBox)
            let scale = min(2.5, 2400 / max(box.width, 1))
            let image = page.thumbnail(of: CGSize(width: box.width * scale, height: box.height * scale), for: .mediaBox)
            pages.append(ocr(image))
        }
        return pages.joined(separator: "\n")
    }

    static func recognize(_ image: UIImage) async -> String {
        await Task.detached(priority: .userInitiated) { ocr(image) }.value
    }

    private static func ocr(_ image: UIImage) -> String {
        guard let cg = image.cgImage else { return "" }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false   // amounts and ids matter more than words; correction turns 0 into O
        let handler = VNImageRequestHandler(cgImage: cg, orientation: CGImagePropertyOrientation(image.imageOrientation), options: [:])
        guard (try? handler.perform([request])) != nil else { return "" }
        let found = (request.results ?? []).compactMap { o -> (String, CGRect)? in o.topCandidates(1).first.map { ($0.string, o.boundingBox) } }
        return rows(found).joined(separator: "\n")
    }

    /// Vision returns text fragments with boxes (origin bottom-left, so a larger y is higher on the page). Fragments on the
    /// same visual row are joined left to right, so "date, description, amount" columns stay on one line.
    static func rows(_ found: [(text: String, box: CGRect)]) -> [String] {
        var rows: [[(text: String, box: CGRect)]] = []
        for f in found.sorted(by: { $0.box.midY > $1.box.midY }) {
            if let ref = rows.last?.first, abs(f.box.midY - ref.box.midY) <= max(f.box.height, ref.box.height) * 0.5 { rows[rows.count - 1].append(f) }
            else { rows.append([f]) }
        }
        return rows.map { $0.sorted { $0.box.minX < $1.box.minX }.map(\.text).joined(separator: "  ") }
    }

    private static func digest(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
}

extension CGImagePropertyOrientation {
    init(_ o: UIImage.Orientation) {
        switch o {
        case .up: self = .up; case .upMirrored: self = .upMirrored; case .down: self = .down; case .downMirrored: self = .downMirrored
        case .left: self = .left; case .leftMirrored: self = .leftMirrored; case .right: self = .right; case .rightMirrored: self = .rightMirrored
        @unknown default: self = .up
        }
    }
}
