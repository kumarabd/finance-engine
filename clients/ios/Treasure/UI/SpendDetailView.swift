import SwiftUI

struct SpendDetailView: View {
    let initial: Spend
    @Environment(SpendsModel.self) private var model
    @Environment(Composer.self) private var composer
    @Environment(Engine.self) private var engine
    @Environment(Directory.self) private var directory
    @State private var history: [Change] = []
    @State private var historyProblem: String?
    @State private var originalSpend: Spend?
    @State private var refunds: [Spend] = []
    @State private var documents: [Evidence] = []
    @State private var attaching = false
    @State private var linkProblem: String?

    /// The newest copy the list holds, so an edit shows here the moment it is saved.
    private var spend: Spend { model.spends.first { $0.id == initial.id } ?? initial }

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text(Money.format(spend.amountMinor, currency: spend.currency))
                        .heroAmount()
                        .foregroundStyle(spend.kind == "refund" ? Tok.live : Tok.text)
                    Text(directory.merchant(spend.merchantId) ?? spend.kind.capitalized).foregroundStyle(Tok.muted)
                }
                .listRowBackground(Color.clear)
            }
            Section("Details") {
                row("Date", DayGroups.title(spend.occurredOn))
                row("Type", spend.kind.capitalized)
                if let d = spend.description, !d.isEmpty { row("Note", d) }
                if !directory.tagNames(spend.tagIds).isEmpty { row("Tags", directory.tagNames(spend.tagIds).joined(separator: ", ")) }
                if let a = spend.accountRef, !a.isEmpty { row("Account", a) }
                if let o = originalSpend { row("Refund of", OriginalPicker.label(o, directory: directory)) }
                ForEach(Array((spend.allocations ?? []).enumerated()), id: \.offset) { _, a in
                    row(directory.category(a.categoryId) ?? "Uncategorized", Money.format(a.amountMinor, currency: spend.currency))
                }
                if spend.deletedAt != nil { row("Status", "Deleted") }
            }
            if !refunds.isEmpty {
                Section("Refunds") {
                    ForEach(refunds) { r in row(DayGroups.title(r.occurredOn), Money.format(r.amountMinor, currency: r.currency)) }
                    row("Net after refunds", Money.format(spend.amountMinor - refunds.reduce(0) { $0 + $1.amountMinor }, currency: spend.currency))
                }
            }
            Section {
                ForEach(documents) { e in
                    VStack(alignment: .leading, spacing: 2) { Text(e.title); Text(e.sourceRef).font(.caption).foregroundStyle(Tok.muted).lineLimit(1) }
                        .swipeActions { if spend.deletedAt == nil { Button("Detach", role: .destructive) { Task { await link(e, attach: false) } } } }
                }
                if spend.deletedAt == nil { Button { attaching = true } label: { Label("Attach a receipt or document", systemImage: "paperclip") }.frame(minHeight: 44) }
                if let linkProblem { Text(linkProblem).foregroundStyle(Tok.critical) }
            } header: { Text("Receipts & documents") } footer: { if !documents.isEmpty { Text("Swipe a document to detach it.") } }
            .listRowBackground(Tok.surface)
            Section("History") {
                if let historyProblem { Text(historyProblem).foregroundStyle(Tok.muted) }
                ForEach(history) { c in
                    HStack {
                        Text(c.operation.replacingOccurrences(of: "_", with: " ").capitalized)
                        Spacer()
                        Text(c.occurredAt.prefix(16).replacingOccurrences(of: "T", with: " ")).font(.caption).foregroundStyle(Tok.muted).amountStyle()
                    }
                }
            }
        }
        .listRowBackground(Tok.surface)
        .scrollContentBackground(.hidden)
        .background(Tok.background)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if spend.deletedAt != nil { Button("Restore") { Task { _ = await model.bulkRestore([spend]) } } }
            else { Button("Edit") { composer.target = .edit(spend) } }
        }
        .task(id: spend.version) { await loadHistory(); await loadLinks(); await loadDocuments() }
        .sheet(isPresented: $attaching) { EvidencePicker(attached: Set(spend.evidenceIds ?? [])) { e in Task { await link(e, attach: true) } } }
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack { Text(label).foregroundStyle(Tok.muted); Spacer(); Text(value).multilineTextAlignment(.trailing) }
            .listRowBackground(Tok.surface)
    }

    private func loadHistory() async {
        let r: Api<Page<Change>> = await engine.call("history_list", HistoryInput(entityType: "spend", id: spend.id))
        if case .ok(let p) = r { history = p.items; historyProblem = nil } else { historyProblem = r.problem }
    }

    private func loadDocuments() async {
        var out: [Evidence] = []
        for id in spend.evidenceIds ?? [] { if let e = await EvidenceModel(engine: engine).get(id) { out.append(e) } }
        documents = out
    }

    private func link(_ e: Evidence, attach: Bool) async {
        let r = await model.link(spend, evidenceId: e.id, attach: attach)
        if case .ok = r { linkProblem = nil } else { linkProblem = r.problem }
    }

    /// The expense a refund belongs to, or the refunds an expense has received.
    private func loadLinks() async {
        originalSpend = nil; refunds = []
        if spend.kind == "refund", let oid = spend.originalSpendId { originalSpend = await model.lookup(oid) }
        if spend.kind == "expense" {
            var f = SpendFilter(); f.originalSpendId = spend.id; f.kind = "refund"
            if case .ok(let items) = await model.search(f, limit: 50) { refunds = items }
        }
    }
}
