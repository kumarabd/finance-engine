import SwiftUI

enum EditorTarget: Identifiable {
    case new
    case edit(Spend)
    var id: String { if case .edit(let s) = self { s.id } else { "new" } }
}

/// What the Add/Edit sheet is showing; owned by the signed-in shell so any tab can open it.
@MainActor @Observable
final class Composer { var target: EditorTarget? }

private let lastCurrencyKey = "lastCurrency"

struct SpendEditor: View {
    let target: EditorTarget
    @Environment(SpendsModel.self) private var spends
    @Environment(Directory.self) private var directory
    @Environment(\.dismiss) private var dismiss

    @State private var original: Spend?
    @State private var entry = AmountEntry()
    @State private var kind = "expense"
    @State private var currency = UserDefaults.standard.string(forKey: lastCurrencyKey) ?? Locale.current.currency?.identifier ?? "USD"
    @State private var date = Date()
    @State private var merchant = ""
    @State private var categoryId: String?
    @State private var tagIds: Set<String> = []
    @State private var pickingTags = false
    @State private var note = ""
    @State private var account = ""
    @State private var splitRows: [SplitRow] = []
    @State private var editingSplits = false
    @State private var originalId: String?
    @State private var originalLabel = ""
    @State private var pickingOriginal = false
    @State private var saving = false
    @State private var error: String?
    @State private var saved = 0
    // One key per editing session: a retry after a dropped connection repeats the same write instead of duplicating it.
    @State private var key = UUID().uuidString
    @FocusState private var typing: Bool

    private var isSplit: Bool { splitRows.count > 1 }
    private var splitProblem: String? { Splits.problem(total: entry.minor, rows: splitRows, currency: currency) }
    private var canSave: Bool { entry.minor > 0 && !saving && splitProblem == nil }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        Picker("Type", selection: $kind) {
                            Text("Expense").tag("expense"); Text("Refund").tag("refund"); Text("Transfer").tag("transfer")
                        }.pickerStyle(.segmented)

                        amountHeader
                        merchantField
                        categoryChips
                        tagsRow
                        if kind == "refund" { refundRow }
                        HStack {
                            DatePicker("Date", selection: $date, displayedComponents: .date)
                        }
                        TextField("Note", text: $note).focused($typing).field()
                        TextField("Account (optional)", text: $account).focused($typing).field()
                        if let error { Text(error).font(.callout).foregroundStyle(Tok.critical) }
                    }
                    .padding(20)
                }
                .scrollDismissesKeyboard(.interactively)
                if !typing { keypad.transition(.move(edge: .bottom).combined(with: .opacity)) }
            }
            .animation(.easeOut(duration: 0.2), value: typing)
            .background(Tok.background)
            .navigationTitle(original == nil ? "Add spend" : "Edit spend")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else { Button("Save") { Task { await save() } }.disabled(!canSave).fontWeight(.semibold) }
                }
            }
            .sensoryFeedback(.success, trigger: saved)
        }
        .onAppear(perform: load)
    }

    // MARK: Pieces

    private var amountHeader: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(Money.format(entry.minor, currency: currency))
                .heroAmount()
                .minimumScaleFactor(0.5).lineLimit(1)
                .foregroundStyle(entry.minor == 0 ? Tok.muted : (kind == "refund" ? Tok.live : Tok.text))
                .contentTransition(.numericText())
                .animation(.snappy(duration: 0.2), value: entry.minor)
            Spacer()
            if original == nil {
                Menu(currency) { ForEach(["USD", "EUR", "GBP", "INR", "JPY", "CAD", "AUD"], id: \.self) { c in Button(c) { currency = c } } }
                    .font(.subheadline.weight(.semibold)).frame(minWidth: 44, minHeight: 44)
            } else {
                Text(currency).font(.subheadline.weight(.semibold)).foregroundStyle(Tok.muted)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Amount \(Money.format(entry.minor, currency: currency))")
    }

    private var merchantField: some View {
        let typed = merchant.trimmingCharacters(in: .whitespaces).lowercased()
        let hints = typed.isEmpty ? [] : directory.merchants.values.filter { $0.name.lowercased().hasPrefix(typed) && $0.name.lowercased() != typed }
            .sorted { $0.name < $1.name }.prefix(5)
        return VStack(alignment: .leading, spacing: 8) {
            TextField("Merchant", text: $merchant).focused($typing).field().textInputAutocapitalization(.words)
            if !hints.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack { ForEach(Array(hints)) { m in chip(m.name, selected: false) { merchant = m.name } } }
                }
            }
        }
    }

    /// Tags on this spend, as removable chips, with a button to pick more or create one.
    private var tagsRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(tagIds.sorted(), id: \.self) { id in
                        Button { tagIds.remove(id) } label: {
                            HStack(spacing: 6) { Text(directory.tag(id) ?? "Tag"); Image(systemName: "xmark").font(.caption2.weight(.bold)) }
                                .font(.subheadline.weight(.medium)).padding(.horizontal, 14).frame(minHeight: 44)
                                .foregroundStyle(Tok.onAccent).background(Tok.accent, in: Capsule())
                        }
                        .buttonStyle(.plain).accessibilityLabel("Remove tag \(directory.tag(id) ?? "")")
                    }
                    Button { pickingTags = true } label: {
                        Label(tagIds.isEmpty ? "Add tags" : "Add", systemImage: "tag").font(.subheadline.weight(.medium))
                            .padding(.horizontal, 14).frame(minHeight: 44).foregroundStyle(Tok.text).background(Tok.raised, in: Capsule())
                    }.buttonStyle(.plain)
                }
            }
        }
        .sheet(isPresented: $pickingTags) {
            MultiPicker(title: "Tags", items: directory.tagsByUse(spends.spends), selection: $tagIds,
                        create: { name in if case .ok(let t) = await directory.resolveTag(name) { return t } else { return nil } },
                        emptyHint: "No tags yet. Type a name to create one.")
        }
    }

    /// Either one category (chips), or a split across several.
    private var categoryChips: some View {
        let cats = directory.categoriesByUse(spends.spends)
        return VStack(alignment: .leading, spacing: 8) {
            if isSplit {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(splitRows) { r in
                        HStack { Text(directory.category(r.categoryId) ?? "No category"); Spacer(); Text(Money.format(r.amountMinor, currency: currency)).amountStyle() }.font(.subheadline)
                    }
                    if let p = splitProblem { Text(p).font(.footnote).foregroundStyle(Tok.warn) }
                    HStack {
                        Button("Edit split") { editingSplits = true }.frame(minHeight: 44)
                        Button("Remove split", role: .destructive) { categoryId = splitRows.first?.categoryId; splitRows = [] }.frame(minHeight: 44)
                    }.font(.subheadline.weight(.medium))
                }
                .padding(14).frame(maxWidth: .infinity, alignment: .leading).background(Tok.raised, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            } else {
                if !cats.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack { ForEach(cats) { c in chip(c.name, selected: categoryId == c.id) { categoryId = categoryId == c.id ? nil : c.id } } }
                    }
                }
                if entry.minor > 1 {
                    Button { splitRows = Splits.start(categoryId: categoryId, total: entry.minor); editingSplits = true } label: { Label("Split across categories", systemImage: "square.split.2x1") }
                        .font(.subheadline.weight(.medium)).frame(minHeight: 44)
                }
            }
        }
        .sheet(isPresented: $editingSplits, onDismiss: {
            // Taken back down to one line, a split is just a category again.
            if splitRows.count <= 1 { categoryId = splitRows.first?.categoryId ?? categoryId; splitRows = [] }
        }) { SplitSheet(rows: $splitRows, total: entry.minor, currency: currency) }
    }

    /// Which expense this refund gives back, so the two net out and the expense shows what came back.
    private var refundRow: some View {
        Button { pickingOriginal = true } label: {
            HStack { Text("Refund of").foregroundStyle(Tok.muted); Spacer(); Text(originalId == nil ? "None" : originalLabel).foregroundStyle(Tok.text).lineLimit(1); Image(systemName: "chevron.right").font(.caption).foregroundStyle(Tok.muted) }
                .padding(.horizontal, 14).frame(minHeight: 52).background(Tok.raised, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .sheet(isPresented: $pickingOriginal) {
            OriginalPicker(currency: currency, selected: originalId) { id, label in originalId = id; originalLabel = label }
        }
    }

    private func chip(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.subheadline.weight(.medium)).padding(.horizontal, 14).frame(minHeight: 44)
                .foregroundStyle(selected ? Tok.onAccent : Tok.text)
                .background(selected ? Tok.accent : Tok.raised, in: Capsule())
        }
        .buttonStyle(.plain)
        .animation(.easeOut(duration: 0.15), value: selected)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var keypad: some View {
        let rows = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["00", "0", "⌫"]]
        return VStack(spacing: 8) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 8) {
                    ForEach(row, id: \.self) { k in
                        Button { tap(k) } label: {
                            Group {
                                if k == "⌫" { Image(systemName: "delete.left") } else { Text(k) }
                            }
                            .font(.title2.weight(.medium)).frame(maxWidth: .infinity, minHeight: 56)
                            .background(Tok.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Tok.hairline))
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(Tok.text)
                        .sensoryFeedback(.selection, trigger: entry)
                        .accessibilityLabel(k == "⌫" ? "Delete" : k)
                    }
                }
            }
        }
        .padding(.horizontal, 16).padding(.bottom, 8).padding(.top, 8)
        .background(Tok.background)
    }

    // MARK: Behavior

    private func tap(_ k: String) { if k == "⌫" { entry.backspace() } else { entry.press(k) } }

    private func load() {
        guard original == nil else { return }
        if case .edit(let s) = target { fill(s) }
    }

    private func fill(_ s: Spend) {
        original = spends.spends.first { $0.id == s.id } ?? s
        let o = original!
        entry = AmountEntry(minor: o.amountMinor)
        kind = o.kind; currency = o.currency
        date = CalendarDate.date(from: o.occurredOn) ?? Date()
        merchant = directory.merchant(o.merchantId) ?? ""
        note = o.description ?? ""
        categoryId = o.allocations?.count == 1 ? o.allocations?.first?.categoryId : nil
        splitRows = Splits.rows(from: o.allocations)
        tagIds = Set(o.tagIds ?? [])
        account = o.accountRef ?? ""
        originalId = o.originalSpendId
        if let oid = o.originalSpendId { Task { if let s = await spends.lookup(oid) { originalLabel = OriginalPicker.label(s, directory: directory) } } }
    }

    private func save() async {
        saving = true; error = nil
        defer { saving = false }
        let typedMerchant = merchant.trimmingCharacters(in: .whitespaces)
        var merchantId: String?
        var unsavedMerchant: String?   // a new merchant can't be created offline; the outbox creates it later
        switch await directory.resolveMerchant(merchant) {
        case .ok(let id): merchantId = id
        case .retry where original == nil && !typedMerchant.isEmpty: unsavedMerchant = typedMerchant
        case let r: error = r.problem; return
        }
        var input = original.map(SpendInput.init) ?? SpendInput(occurredOn: "", kind: kind, amountMinor: 0, currency: currency)
        input.occurredOn = CalendarDate.string(from: date)
        input.kind = kind; input.currency = currency
        input.merchantId = merchantId
        input.description = note.trimmingCharacters(in: .whitespaces).isEmpty ? nil : note.trimmingCharacters(in: .whitespaces)
        input.amountMinor = entry.minor
        input.allocations = isSplit ? Splits.allocations(splitRows) : categoryId.map { [Allocation(categoryId: $0, amountMinor: entry.minor)] }
        input.accountRef = account.trimmingCharacters(in: .whitespaces).isEmpty ? nil : account.trimmingCharacters(in: .whitespaces)
        input.originalSpendId = kind == "refund" ? originalId : nil   // only a refund may point at an original expense
        input.tagIds = tagIds.isEmpty ? nil : tagIds.sorted()
        if original == nil { input.source = "ios"; input.sourceRecordId = key }

        let r: Api<Spend>
        if let o = original { r = await spends.update(o, to: input, key: key) }
        else if unsavedMerchant != nil { r = .retry("offline") }
        else { r = await spends.create(input, key: key) }
        if original == nil, case .retry = r {
            // Offline: keep the spend, send it when the connection returns. Same key, so a late reply can't duplicate it.
            spends.enqueue(input, merchantName: unsavedMerchant, key: key)
            UserDefaults.standard.set(currency, forKey: lastCurrencyKey)
            saved += 1
            try? await Task.sleep(for: .milliseconds(150))
            dismiss()
            return
        }
        switch r {
        case .ok:
            UserDefaults.standard.set(currency, forKey: lastCurrencyKey)
            saved += 1
            try? await Task.sleep(for: .milliseconds(150))   // let the confirmation haptic land before the sheet leaves
            dismiss()
        case .failed("conflict", _):
            // Someone changed it elsewhere: show their version and let the user decide again.
            if let o = original, let latest = await spends.fetch(o.id) { fill(latest); key = UUID().uuidString }
            error = "This spend changed elsewhere. The latest version is shown; review it and save again."
        default:
            error = r.problem
        }
    }
}
