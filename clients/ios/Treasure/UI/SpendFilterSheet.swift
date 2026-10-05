import SwiftUI

/// Edits a copy of the list's filter; "Apply" hands it back, "Reset" clears every condition.
struct SpendFilterSheet: View {
    let initial: SpendFilter
    let apply: (SpendFilter) -> Void
    @Environment(Directory.self) private var directory
    @Environment(SpendsModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var draft: SpendFilter
    @State private var preset: DatePreset
    @State private var minText: String
    @State private var maxText: String
    @State private var picking: Picking?

    private enum Picking: Identifiable { case category, merchant, tags; var id: Self { self } }

    init(initial: SpendFilter, apply: @escaping (SpendFilter) -> Void) {
        self.initial = initial; self.apply = apply
        _draft = State(initialValue: initial)
        _preset = State(initialValue: DatePreset.matching(from: initial.from, to: initial.to))
        func text(_ v: Int64?) -> String { v.map { String(format: "%.\(AmountParse.fractionDigits(initial.currency ?? "USD"))f", Double($0) / pow(10, Double(AmountParse.fractionDigits(initial.currency ?? "USD")))) } ?? "" }
        _minText = State(initialValue: text(initial.minAmountMinor)); _maxText = State(initialValue: text(initial.maxAmountMinor))
    }

    private var currencies: [String] { Array(Set(["USD", "EUR", "GBP", "INR", "JPY", "CAD", "AUD"] + model.spends.map(\.currency) + (draft.currency.map { [$0] } ?? []))).sorted() }

    var body: some View {
        NavigationStack {
            Form {
                Section("When") {
                    Picker("Dates", selection: $preset) { ForEach(DatePreset.allCases) { Text($0.title).tag($0) } }
                    if preset == .custom {
                        dateRow("From", date: $draft.from)
                        dateRow("To", date: $draft.to)
                    }
                }
                Section("What") {
                    Picker("Type", selection: Binding(get: { draft.kind ?? "" }, set: { draft.kind = $0.isEmpty ? nil : $0 })) {
                        Text("Any").tag(""); Text("Expense").tag("expense"); Text("Refund").tag("refund"); Text("Transfer").tag("transfer")
                    }.pickerStyle(.segmented)
                    pickRow("Category", value: draft.uncategorized ? "Uncategorized" : directory.category(draft.categoryId)) { picking = .category }
                    pickRow("Merchant", value: directory.merchant(draft.merchantId)) { picking = .merchant }
                    pickRow("Tags", value: draft.tagIds.isEmpty ? nil : draft.tagIds.compactMap { directory.tag($0) }.joined(separator: ", ")) { picking = .tags }
                    TextField("Account", text: $draft.accountRef)
                }
                Section {
                    Picker("Currency", selection: Binding(get: { draft.currency ?? "" }, set: { draft.currency = $0.isEmpty ? nil : $0 })) {
                        Text("Any").tag(""); ForEach(currencies, id: \.self) { Text($0).tag($0) }
                    }
                    HStack {
                        TextField("Min", text: $minText).keyboardType(.decimalPad)
                        Text("to").foregroundStyle(Tok.muted)
                        TextField("Max", text: $maxText).keyboardType(.decimalPad)
                    }
                } header: { Text("Amount") } footer: { Text("Amounts are in one currency, so setting them picks one.") }
                Section("Order") {
                    Picker("Sort", selection: $draft.sort) {
                        Text("Newest first").tag("date_desc"); Text("Oldest first").tag("date_asc")
                        Text("Largest first").tag("amount_desc"); Text("Smallest first").tag("amount_asc")
                    }
                    if draft.sortNeedsCurrency { Text("Sorting by amount needs a currency; one will be chosen.").font(.footnote).foregroundStyle(Tok.muted) }
                }
                Section("Show") {
                    Picker("Records", selection: $draft.state) { Text("Active").tag("active"); Text("Deleted").tag("deleted"); Text("Both").tag("all") }.pickerStyle(.segmented)
                }
            }
            .scrollContentBackground(.hidden).background(Tok.background)
            .navigationTitle("Filter").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Reset") { reset() } }
                ToolbarItem(placement: .confirmationAction) { Button("Apply") { commit() }.fontWeight(.semibold) }
            }
            .onChange(of: preset) { _, new in
                if let r = new.range() { draft.from = r.from; draft.to = r.to }
                else if new == .custom, draft.from == nil, draft.to == nil { draft.from = CalendarDate.string(from: .now) }
            }
            .sheet(item: $picking) { kind in
                switch kind {
                case .category:
                    SinglePicker(title: "Category", items: directory.categoriesByUse(model.spends), noneLabel: "Uncategorized", selected: draft.uncategorized ? "" : draft.categoryId) { id in
                        draft.uncategorized = id.isEmpty; draft.categoryId = id.isEmpty ? nil : id
                    }
                case .merchant:
                    SinglePicker(title: "Merchant", items: directory.merchants.values.sorted { $0.name.lowercased() < $1.name.lowercased() }, noneLabel: "Any merchant", selected: draft.merchantId ?? "") { id in
                        draft.merchantId = id.isEmpty ? nil : id
                    }
                case .tags:
                    MultiPicker(title: "Tags (all must match)", items: directory.tagsByUse(model.spends), selection: Binding(get: { Set(draft.tagIds) }, set: { draft.tagIds = $0.sorted() }), emptyHint: "No tags yet.")
                }
            }
        }
    }

    private func pickRow(_ title: String, value: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack { Text(title).foregroundStyle(Tok.text); Spacer(); Text(value ?? "Any").foregroundStyle(Tok.muted).lineLimit(1); Image(systemName: "chevron.right").font(.caption).foregroundStyle(Tok.muted) }
                .frame(minHeight: 44)
        }
    }

    private func dateRow(_ title: String, date: Binding<String?>) -> some View {
        DatePicker(title, selection: Binding(get: { date.wrappedValue.flatMap { CalendarDate.date(from: $0) } ?? .now }, set: { date.wrappedValue = CalendarDate.string(from: $0) }), displayedComponents: .date)
    }

    private func reset() {
        var cleared = SpendFilter(); cleared.state = draft.state
        draft = cleared; preset = .any; minText = ""; maxText = ""
    }

    private func commit() {
        var f = draft
        let lo = minText.trimmingCharacters(in: .whitespaces), hi = maxText.trimmingCharacters(in: .whitespaces)
        if !lo.isEmpty || !hi.isEmpty || f.sort.hasPrefix("amount") {
            // Amounts only mean something in one currency: take the one picked, else the user's usual one.
            let cur = f.currency ?? UserDefaults.standard.string(forKey: "lastCurrency") ?? Locale.current.currency?.identifier ?? "USD"
            f.currency = cur
            f.minAmountMinor = lo.isEmpty ? nil : Money.parseMinor(lo, currency: cur)
            f.maxAmountMinor = hi.isEmpty ? nil : Money.parseMinor(hi, currency: cur)
        } else { f.minAmountMinor = nil; f.maxAmountMinor = nil }
        apply(f)
        dismiss()
    }
}

/// Active conditions as removable chips.
struct FilterChipsRow: View {
    let chips: [FilterChip]
    let remove: (FilterChip) -> Void
    let clearAll: () -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(chips) { chip in
                    Button { remove(chip) } label: {
                        HStack(spacing: 6) { Text(chip.label).lineLimit(1); Image(systemName: "xmark").font(.caption2.weight(.bold)) }
                            .font(.subheadline.weight(.medium)).padding(.horizontal, 12).frame(minHeight: 36)
                            .foregroundStyle(Tok.text).background(Tok.raised, in: Capsule())
                    }
                    .buttonStyle(.plain).accessibilityLabel("Remove filter \(chip.label)")
                }
                if chips.count > 1 { Button("Clear all", action: clearAll).font(.subheadline).frame(minHeight: 36) }
            }
            .padding(.horizontal, 16)
        }
    }
}
