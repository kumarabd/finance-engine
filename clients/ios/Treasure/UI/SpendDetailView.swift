import SwiftUI

struct SpendDetailView: View {
    let initial: Spend
    @Environment(SpendsModel.self) private var model
    @Environment(Composer.self) private var composer
    @Environment(Engine.self) private var engine
    @Environment(Directory.self) private var directory
    @State private var history: [Change] = []
    @State private var historyProblem: String?

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
                ForEach(Array((spend.allocations ?? []).enumerated()), id: \.offset) { _, a in
                    row(directory.category(a.categoryId) ?? "Uncategorized", Money.format(a.amountMinor, currency: spend.currency))
                }
                if spend.deletedAt != nil { row("Status", "Deleted") }
            }
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
        .task(id: spend.version) { await loadHistory() }
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack { Text(label).foregroundStyle(Tok.muted); Spacer(); Text(value).multilineTextAlignment(.trailing) }
            .listRowBackground(Tok.surface)
    }

    private func loadHistory() async {
        let r: Api<Page<Change>> = await engine.call("history_list", HistoryInput(entityType: "spend", id: spend.id))
        if case .ok(let p) = r { history = p.items; historyProblem = nil } else { historyProblem = r.problem }
    }
}
