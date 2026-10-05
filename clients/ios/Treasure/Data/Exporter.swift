import Foundation

/// Every page of an export joined into one CSV (each page repeats the header row; it is kept once).
enum Exporter {
    static func csv(engine: Engine, filter: SpendFilter = SpendFilter()) async -> Api<String> {
        var pages: [String] = [], offset = 0
        while true {
            let r: Api<ExportResult> = await engine.call("spends_export", filter.input(limit: 200, offset: offset))
            guard case .ok(let page) = r else { return r.map { $0.csv } }
            pages.append(page.csv)
            guard let next = page.nextOffset else { return .ok(CSVJoin.join(pages)) }
            offset = next
        }
    }

    static func file(named name: String, contents: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try contents.write(to: url, atomically: true, encoding: .utf8)
        return url
    }
}
