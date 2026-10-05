import SwiftUI

/// This month at a glance, then the ten newest spends.
struct HomeView: View {
    @Environment(Engine.self) private var engine
    @Environment(SpendsModel.self) private var model
    @Environment(Composer.self) private var composer
    @State private var snapshot: Snapshot?

    var body: some View {
        NavigationStack {
            List {
                if let snap = snapshot, let cur = snap.currencies.first { hero(snap, cur).listRowBackground(Color.clear).listRowSeparator(.hidden) }
                if let p = model.problem, !model.spends.isEmpty { Text(p).foregroundStyle(Tok.muted).listRowBackground(Tok.background) }
                PendingSection()
                Section("Recent") {
                    ForEach(model.spends.prefix(10)) { SpendRow(spend: $0).listRowBackground(Tok.surface) }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Tok.background)
            .overlay {
                if model.spends.isEmpty && !model.loading {
                    if let p = model.problem { ProblemView(message: p) { await model.reload() } }
                    else if model.pending.isEmpty { ContentUnavailableView("No spends yet", systemImage: "diamond") }
                }
            }
            .animation(.easeInOut(duration: 0.25), value: model.spends)
            .navigationTitle("Treasure")
            .toolbar {
                ToolbarItemGroup(placement: .primaryAction) { ImportMenu(); Button { composer.target = .new } label: { Image(systemName: "plus") }.accessibilityLabel("Add spend") }
            }
            .refreshable { await model.flushOutbox(); await model.reload(); await load() }
            .task(id: model.revision) { await load() }
        }
    }

    private func hero(_ snap: Snapshot, _ cur: String) -> some View {
        let t = snap.total(cur)
        return VStack(alignment: .leading, spacing: 4) {
            Text("This month").font(.subheadline).foregroundStyle(Tok.muted)
            Text(Money.format(t.current, currency: cur)).heroAmount()
                .contentTransition(.numericText()).animation(.snappy(duration: 0.3), value: t.current)
            if let d = Delta.text(current: t.current, previous: t.previous, against: snap.period.comparedWith) {
                Text(d).font(.subheadline).foregroundStyle(Tok.muted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private func load() async {
        if case .ok(let s) = await Insights(engine: engine).snapshot(.month) { snapshot = s }
    }
}
