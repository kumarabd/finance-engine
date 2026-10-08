import SwiftUI

/// Budgets: what you have set, and how this period is going.
///
/// The bar is the whole point of the screen, so it shows two things at once —
/// where you are, and where the alert line is — because a budget that only tells
/// you that you have failed is not a budget, it is a report. The line being
/// visible before you cross it is what makes it useful.
struct BudgetsView: View {
    @Environment(Engine.self) private var engine
    @Environment(Directory.self) private var directory
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var model: BudgetsModel?
    @State private var editing: EditorTarget?

    enum EditorTarget: Identifiable {
        case new
        case existing(BudgetStatus)
        var id: String {
            switch self {
            case .new: "new"
            case .existing(let s): s.id
            }
        }
    }

    var body: some View {
        List {
            if let model {
                if let problem = model.problem {
                    Section { ProblemView(message: problem) { await model.reload() }.listRowBackground(Tok.surface) }
                }
                if model.statuses.isEmpty && !model.loading {
                    Section {
                        ContentUnavailableView {
                            Label("No budgets yet", systemImage: "target")
                        } description: {
                            Text("A budget is a limit for a month or a week, on one category or on everything. You will hear from it before you have spent it all, not after.")
                        } actions: {
                            Button("Set a budget") { editing = .new }.buttonStyle(PrimaryButtonStyle())
                                .frame(maxWidth: 260)
                        }
                        .listRowBackground(Tok.surface)
                    }
                } else {
                    Section {
                        ForEach(model.statuses) { status in
                            Button { editing = .existing(status) } label: {
                                BudgetRow(status: status)
                            }
                            .buttonStyle(.plain)
                            .listRowBackground(Tok.surface)
                            .swipeActions {
                                Button("Delete", role: .destructive) {
                                    Task { await model.delete(status) }
                                }
                            }
                        }
                    } footer: {
                        Text("Budgets are checked as spends are recorded, so they are always current — there is nothing to refresh.")
                    }
                }
            }
        }
        .navigationTitle("Budgets")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { editing = .new } label: { Image(systemName: "plus") }
                    .accessibilityLabel("New budget")
            }
        }
        .overlay { if model?.loading == true && model?.hasAny == false { ProgressView() } }
        .task {
            if model == nil { model = BudgetsModel(engine: engine, directory: directory) }
            await model?.reload()
        }
        .sheet(item: $editing) { target in
            if let model {
                BudgetEditor(model: model, directory: directory, target: target)
            }
        }
    }
}

/// One budget on one line: how much is used, how much is left, and where the
/// alert line sits.
private struct BudgetRow: View {
    let status: BudgetStatus

    private var tint: Color {
        if status.over { return Tok.critical }
        if status.alerted { return Tok.warn }
        return Tok.live
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(status.name)
                    .font(.headline)
                    .foregroundStyle(Tok.text)
                Spacer(minLength: 8)
                Text("\(status.percent)%")
                    .monospacedDigit()
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(tint)
            }

            // The bar shows progress and the alert line together. The line is
            // drawn as a tick so it is visible before it is crossed, which is the
            // only moment it can still change anything.
            GeometryReader { geo in
                let width = geo.size.width
                ZStack(alignment: .leading) {
                    Capsule().fill(Tok.raised)
                    Capsule().fill(tint).frame(width: max(2, width * status.fraction))
                    if status.notifyAtPercent < 100 {
                        Rectangle()
                            .fill(Tok.muted)
                            .frame(width: 1.5)
                            .offset(x: width * status.alertFraction)
                    }
                }
            }
            .frame(height: 8)
            .accessibilityHidden(true)

            HStack(alignment: .firstTextBaseline) {
                Text("\(Money.format(status.spentMinor, currency: status.currency)) of \(Money.format(status.limitMinor, currency: status.currency))")
                    .font(.footnote)
                    .monospacedDigit()
                    .foregroundStyle(Tok.muted)
                Spacer(minLength: 8)
                Text(remainingText)
                    .font(.footnote.weight(.medium))
                    .monospacedDigit()
                    .foregroundStyle(tint)
            }

            HStack(spacing: 6) {
                Text(status.subject)
                Text("·")
                Text("this \(status.periodLabel)")
                if status.alerted && !status.over {
                    Text("·")
                    Text("past the \(status.notifyAtPercent)% mark")
                }
            }
            .font(.caption)
            .foregroundStyle(Tok.muted)
        }
        .padding(.vertical, 6)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(status.name), \(status.subject)")
        .accessibilityValue("\(status.percent) percent used, \(remainingText)")
    }

    /// An exceeded budget reports zero left rather than a negative allowance —
    /// "you have minus 2,000 left" is arithmetic, not information.
    private var remainingText: String {
        status.over
            ? "over by \(Money.format(status.spentMinor - status.limitMinor, currency: status.currency))"
            : "\(Money.format(status.remainingMinor, currency: status.currency)) left"
    }
}

/// Create or change a budget.
///
/// Category and kind are fixed once created: moving a budget to another category
/// would invalidate every crossing already recorded against the old one, so the
/// editor does not offer it and says why instead of hiding the control silently.
private struct BudgetEditor: View {
    @Environment(\.dismiss) private var dismiss
    let model: BudgetsModel
    let directory: Directory
    let target: BudgetsView.EditorTarget

    @State private var name = ""
    @State private var categoryID: String?
    @State private var period = "month"
    @State private var amountText = ""
    @State private var notifyAtPercent = 80
    @State private var pickingCategory = false
    @State private var saving = false

    private var existing: BudgetStatus? {
        if case .existing(let status) = target { return status }
        return nil
    }

    private var currency: String { existing?.currency ?? model.defaultCurrency }

    private var amountMinor: Int64? { Money.parseMinor(amountText, currency: currency) }

    private var valid: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && (amountMinor ?? 0) > 0
    }

    private var categories: [Dimension] {
        directory.categories.values.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("What") {
                    TextField("Name", text: $name)
                    if let existing {
                        LabeledContent("Applies to", value: existing.subject)
                        Text("A budget's category is fixed — delete it and make a new one to move it.")
                            .font(.footnote).foregroundStyle(Tok.muted)
                    } else {
                        Button {
                            pickingCategory = true
                        } label: {
                            LabeledContent("Applies to", value: categoryID.flatMap { directory.category($0) } ?? "All spending")
                        }
                        .foregroundStyle(Tok.text)
                    }
                }

                Section("Limit") {
                    LabeledContent("Period") {
                        Picker("Period", selection: $period) {
                            Text("Month").tag("month")
                            Text("Week").tag("week")
                        }
                        .pickerStyle(.segmented)
                        .labelsHidden()
                    }
                    LabeledContent("Amount (\(currency))") {
                        TextField("0", text: $amountText)
                            .multilineTextAlignment(.trailing)
                            .monospacedDigit()
                            .keyboardType(.decimalPad)
                    }
                }

                Section {
                    Picker("Alert at", selection: $notifyAtPercent) {
                        Text("80% of the limit").tag(80)
                        Text("90% of the limit").tag(90)
                        Text("Only when over").tag(100)
                    }
                } footer: {
                    Text(notifyAtPercent < 100
                         ? "You will be told once, when the total passes \(notifyAtPercent)% — while there is still room to act."
                         : "You will be told once, when the total goes over the limit.")
                }
            }
            .navigationTitle(existing == nil ? "New budget" : "Edit budget")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") { Task { await save() } }
                        .fontWeight(.semibold)
                        .disabled(!valid || saving)
                }
            }
            .sheet(isPresented: $pickingCategory) {
                SinglePicker(title: "Category", items: categories, noneLabel: "All spending", selected: categoryID) { picked in
                    categoryID = picked
                }
            }
        }
        .onAppear {
            guard let existing else {
                notifyAtPercent = 80
                return
            }
            name = existing.name
            categoryID = existing.categoryID
            period = existing.period
            amountText = Money.plain(existing.limitMinor, currency: existing.currency)
            notifyAtPercent = existing.notifyAtPercent
        }
    }

    private func save() async {
        guard let amountMinor, valid else { return }
        saving = true
        defer { saving = false }
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        let ok: Bool
        if let existing {
            ok = await model.update(existing, name: trimmed, period: period,
                                    limitMinor: amountMinor, notifyAtPercent: notifyAtPercent)
        } else {
            ok = await model.create(name: trimmed, categoryID: categoryID, period: period,
                                    limitMinor: amountMinor, currency: currency,
                                    notifyAtPercent: notifyAtPercent)
        }
        if ok { dismiss() }
    }
}
