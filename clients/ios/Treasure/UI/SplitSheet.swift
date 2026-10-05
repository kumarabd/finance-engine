import SwiftUI

/// Divide a purchase across categories. "Done" stays disabled until the shares add up to the amount.
struct SplitSheet: View {
    @Binding var rows: [SplitRow]
    let total: Int64
    let currency: String
    @Environment(Directory.self) private var directory
    @Environment(SpendsModel.self) private var spends
    @Environment(\.dismiss) private var dismiss

    private var problem: String? { Splits.problem(total: total, rows: rows, currency: currency) }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach($rows) { $row in
                        SplitLine(row: $row, currency: currency, categories: directory.categoriesByUse(spends.spends), name: { directory.category($0) })
                            .listRowBackground(Tok.surface)
                            .swipeActions { Button(role: .destructive) { rows.removeAll { $0.id == row.id } } label: { Label("Remove", systemImage: "trash") } }
                    }
                    Button { rows = Splits.adding(to: rows, total: total) } label: { Label("Add a split", systemImage: "plus.circle") }
                        .frame(minHeight: 44).listRowBackground(Tok.surface)
                } footer: { Text("Swipe a line to remove it. Removing down to one line makes it a normal spend.") }
                Section {
                    LabeledContent("Total", value: Money.format(total, currency: currency)).amountStyle()
                    if let problem { Text(problem).foregroundStyle(Tok.warn) } else { Label("Adds up", systemImage: "checkmark.circle").foregroundStyle(Tok.live) }
                }.listRowBackground(Tok.surface)
            }
            .scrollContentBackground(.hidden).background(Tok.background)
            .contentMargins(.top, 0, for: .scrollContent)
            .navigationTitle("Split").navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Done") { dismiss() }.fontWeight(.semibold).disabled(rows.count > 1 && problem != nil) }
        }
        .interactiveDismissDisabled(rows.count > 1 && problem != nil)
        .presentationDetents([.large])
    }
}

private struct SplitLine: View {
    @Binding var row: SplitRow
    let currency: String
    let categories: [Dimension]
    let name: (String?) -> String?
    @State private var text: String

    init(row: Binding<SplitRow>, currency: String, categories: [Dimension], name: @escaping (String?) -> String?) {
        _row = row; self.currency = currency; self.categories = categories; self.name = name
        _text = State(initialValue: row.wrappedValue.amountMinor > 0 ? Money.plain(row.wrappedValue.amountMinor, currency: currency) : "")
    }

    var body: some View {
        HStack(spacing: 12) {
            Menu {
                Button("No category") { row.categoryId = nil }
                ForEach(categories) { c in Button(c.name) { row.categoryId = c.id } }
            } label: {
                HStack(spacing: 4) { Text(name(row.categoryId) ?? "No category").foregroundStyle(row.categoryId == nil ? Tok.muted : Tok.text); Image(systemName: "chevron.up.chevron.down").font(.caption2).foregroundStyle(Tok.muted) }
                    .frame(minHeight: 44)
            }
            Spacer()
            TextField("0", text: $text).keyboardType(.decimalPad).multilineTextAlignment(.trailing).amountStyle().frame(width: 110)
                .onChange(of: text) { _, new in row.amountMinor = Money.parseMinor(new, currency: currency) ?? 0 }
        }
    }
}

/// Choose the expense a refund belongs to. Only expenses in the refund's currency qualify, as the engine requires.
struct OriginalPicker: View {
    let currency: String
    let selected: String?
    let onPick: (_ id: String?, _ label: String) -> Void
    @Environment(SpendsModel.self) private var spends
    @Environment(Directory.self) private var directory
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [Spend] = []
    @State private var problem: String?

    static func label(_ s: Spend, directory: Directory) -> String {
        (directory.merchant(s.merchantId) ?? (s.description?.isEmpty == false ? s.description! : "Expense")) + " · " + DayGroups.title(s.occurredOn)
    }

    var body: some View {
        NavigationStack {
            List {
                Button { onPick(nil, ""); dismiss() } label: { row("No linked expense", selected: selected == nil) }.listRowBackground(Tok.surface)
                if let problem { Text(problem).foregroundStyle(Tok.muted) }
                ForEach(results) { s in
                    Button { onPick(s.id, Self.label(s, directory: directory)); dismiss() } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(directory.merchant(s.merchantId) ?? (s.description?.isEmpty == false ? s.description! : "Expense")).foregroundStyle(Tok.text)
                                Text(DayGroups.title(s.occurredOn)).font(.caption).foregroundStyle(Tok.muted)
                            }
                            Spacer()
                            Text(Money.format(s.amountMinor, currency: s.currency)).amountStyle().foregroundStyle(Tok.text)
                            if selected == s.id { Image(systemName: "checkmark").foregroundStyle(Tok.accent) }
                        }.frame(minHeight: 44)
                    }.listRowBackground(Tok.surface)
                }
            }
            .scrollContentBackground(.hidden).background(Tok.background)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search expenses")
            .navigationTitle("Refund of").navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Cancel") { dismiss() } }
            .task(id: query) {
                if !query.isEmpty { try? await Task.sleep(for: .milliseconds(250)) }
                guard !Task.isCancelled else { return }
                var f = SpendFilter(); f.kind = "expense"; f.currency = currency; f.search = query
                switch await spends.search(f) {
                case .ok(let items): results = items; problem = nil
                case let r: problem = r.problem
                }
            }
        }
        .presentationDetents([.large])
    }

    private func row(_ title: String, selected: Bool) -> some View {
        HStack { Text(title).foregroundStyle(Tok.text); Spacer(); if selected { Image(systemName: "checkmark").foregroundStyle(Tok.accent) } }.frame(minHeight: 44)
    }
}
