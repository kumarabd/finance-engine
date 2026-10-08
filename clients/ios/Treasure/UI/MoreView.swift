import SwiftUI

struct ExportResult: Decodable {
    var csv: String
    var nextOffset: Int?
    enum CodingKeys: String, CodingKey { case csv, nextOffset = "next_offset" }
}

enum CSVJoin {
    /// Each export page repeats the header row; keep it once.
    static func join(_ pages: [String]) -> String {
        guard let first = pages.first else { return "" }
        return pages.dropFirst().reduce(first) { acc, page in
            let body = page.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false).dropFirst().first.map(String.init) ?? ""
            return acc + body
        }
    }
}

struct MoreView: View {
    @Environment(AuthService.self) private var auth
    @Environment(Engine.self) private var engine
    @State private var exporting = false
    @State private var exportURL: URL?
    @State private var problem: String?
    @State private var lockProblem: String?
    @Environment(AppLock.self) private var lock
    @Environment(Router.self) private var router

    var body: some View {
        @Bindable var router = router
        NavigationStack {
            List {
                Section {
                    Button { router.spendsOpen = true } label: { HStack { Label("Spends", systemImage: "list.bullet"); Spacer(); Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Tok.muted) } }.foregroundStyle(Tok.text)
                        .frame(minHeight: 44).listRowBackground(Tok.surface)
                    NavigationLink("Budgets") { BudgetsView() }.frame(minHeight: 44).listRowBackground(Tok.surface)
                }
                Section("Organize") {
                    ForEach(DimensionKind.allCases) { k in
                        NavigationLink(k.title) { OrganizeView(kind: k) }.frame(minHeight: 44).listRowBackground(Tok.surface)
                    }
                }
                Section("Documents") {
                    NavigationLink("Receipts & documents") { EvidenceView() }.frame(minHeight: 44).listRowBackground(Tok.surface)
                }
                Section("Data") {
                    Group {
                        if let exportURL {
                            ShareLink("Share spends.csv", item: exportURL)
                        } else {
                            Button { Task { await export() } } label: {
                                HStack { Text("Export all spends (CSV)"); if exporting { Spacer(); ProgressView() } }
                            }.disabled(exporting)
                        }
                    }
                    .frame(minHeight: 44).listRowBackground(Tok.surface)
                    if let problem { Text(problem).font(.callout).foregroundStyle(Tok.critical).listRowBackground(Tok.surface) }
                }
                Section("Security") {
                    Toggle("Lock with Face ID or passcode", isOn: Binding(get: { lock.enabled }, set: { on in Task { lockProblem = await lock.setEnabled(on) } }))
                        .frame(minHeight: 44).listRowBackground(Tok.surface)
                    if let lockProblem { Text(lockProblem).font(.callout).foregroundStyle(Tok.critical).listRowBackground(Tok.surface) }
                }
                Section("Account") {
                    if case .signedIn(_, let email) = auth.state, let email { Text(email).foregroundStyle(Tok.muted).listRowBackground(Tok.surface) }
                    Button("Sign out", role: .destructive) { Task { await auth.signOut() } }.frame(minHeight: 44).listRowBackground(Tok.surface)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Tok.background)
            .navigationTitle("More")
            .navigationDestination(for: Spend.self) { SpendDetailView(initial: $0) }
            .navigationDestination(isPresented: $router.spendsOpen) { SpendsView(embedded: true) }
        }
    }

    private func export() async {
        exporting = true; problem = nil
        defer { exporting = false }
        switch await Exporter.csv(engine: engine) {
        case .ok(let csv):
            do { exportURL = try Exporter.file(named: "treasure-spends.csv", contents: csv) } catch { problem = "Couldn't write the export file." }
        case let r: problem = r.problem
        }
    }
}
