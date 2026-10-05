import SwiftUI

struct SpendRow: View {
    let spend: Spend
    @Environment(Directory.self) private var directory

    private var title: String {
        directory.merchant(spend.merchantId) ?? (spend.description?.isEmpty == false ? spend.description! : spend.kind.capitalized)
    }
    private var subtitle: String {
        let cats = (spend.allocations ?? []).compactMap { directory.category($0.categoryId) }
        let base = cats.isEmpty ? (spend.kind == "expense" ? "Uncategorized" : spend.kind.capitalized) : cats.joined(separator: ", ")
        let tags = directory.tagNames(spend.tagIds)
        return tags.isEmpty ? base : base + " · " + tags.map { "#" + $0 }.joined(separator: " ")
    }

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).lineLimit(1)
                Text(subtitle).font(.caption).foregroundStyle(Tok.muted).lineLimit(1)
            }
            Spacer(minLength: 8)
            Text(Money.format(spend.amountMinor, currency: spend.currency))
                .amountStyle()
                .foregroundStyle(spend.kind == "refund" ? Tok.live : Tok.text)
        }
        .frame(minHeight: 44)
        .accessibilityElement(children: .combine)
    }
}

struct SpendsView: View {
    @Environment(SpendsModel.self) private var model
    @Environment(Directory.self) private var directory
    @Environment(Composer.self) private var composer
    @Environment(Engine.self) private var engine

    @State private var editMode: EditMode = .inactive
    @State private var selection = Set<String>()
    @State private var showFilter = false
    @State private var bulk: BulkAction?
    @State private var tagAction: BulkAction?   // whether the open tag sheet adds or removes
    @State private var pickedTags = Set<String>()
    @State private var confirmDelete = false
    @State private var message: String?
    @State private var lastLoadedSearch = ""
    @State private var done = 0
    @State private var sharing: URL?
    @State private var exporting = false

    private enum BulkAction: Identifiable { case category, addTags, removeTags, merchant; var id: Self { self } }

    private var inTrash: Bool { model.filter.state == "deleted" }
    private var editing: Bool { editMode == .active }
    private var selected: [Spend] { model.spends.filter { selection.contains($0.id) } }
    private var chips: [FilterChip] {
        model.filter.chips(category: { directory.category($0) }, merchant: { directory.merchant($0) }, tag: { directory.tag($0) })
    }

    @ViewBuilder private var emptyState: some View {
        if model.spends.isEmpty {
            if model.loading {
                SkeletonList()
            } else if let p = model.problem {
                ProblemView(message: p) { await model.reload() }
            } else {
                let title: LocalizedStringKey = inTrash ? "Trash is empty" : model.filter.activeCount > 0 ? "No matches" : "No spends yet"
                ContentUnavailableView(title, systemImage: inTrash ? "trash" : "diamond")
            }
        }
    }

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            List(selection: $selection) {
                if let p = model.problem, !model.spends.isEmpty { Text(p).font(.callout).foregroundStyle(Tok.muted).listRowBackground(Tok.background) }
                if !inTrash { PendingSection() }
                ForEach(model.groups) { group in
                    Section {
                        ForEach(group.spends) { s in
                            NavigationLink(value: s) { SpendRow(spend: s) }
                                .listRowBackground(Tok.surface)
                                .task { await model.loadMore(after: s) }
                                .swipeActions {
                                    if inTrash {
                                        Button { Task { _ = await model.bulkRestore([s]) } } label: { Label("Restore", systemImage: "arrow.uturn.backward") }.tint(Tok.accent)
                                    } else {
                                        Button(role: .destructive) { Task { await model.delete(s) } } label: { Label("Delete", systemImage: "trash") }
                                    }
                                }
                        }
                    } header: {
                        HStack {
                            Text(DayGroups.title(group.day))
                            Spacer()
                            if let n = group.net, n.minor != 0 { Text(Money.format(n.minor, currency: n.currency)).amountStyle() }
                        }
                    }
                }
                if model.hasMore { ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear) }
            }
            .environment(\.editMode, $editMode)
            .scrollContentBackground(.hidden)
            .background(Tok.background)
            .overlay { emptyState }
            .animation(.easeInOut(duration: 0.25), value: model.spends)
            .animation(.spring(duration: 0.35), value: model.undo)
            .navigationTitle(editing ? (selection.isEmpty ? "Select spends" : "\(selection.count) selected") : inTrash ? "Trash" : "Spends")
            .toolbar { toolbar }
            // Chips sit outside the list: a button inside a list row can lose its tap to the row.
            .safeAreaInset(edge: .top, spacing: 0) {
                if !chips.isEmpty && !editing {
                    FilterChipsRow(chips: chips, remove: { chip in var f = model.filter; chip.clear(&f); model.filter = f }, clearAll: {
                        var f = SpendFilter(); f.state = model.filter.state; model.filter = f
                    })
                    .padding(.vertical, 6).frame(maxWidth: .infinity).background(Tok.background)
                }
            }
            .safeAreaInset(edge: .bottom) { if editing { bulkBar } }
            .overlay(alignment: .bottom) { UndoToast().padding(.bottom, editing ? 64 : 0) }
            .navigationDestination(for: Spend.self) { SpendDetailView(initial: $0) }
            .searchable(text: $model.search, prompt: "Merchant, note or account")
            .task(id: model.filter) {
                // Debounce typing; any other change (a chip, the filter sheet, Trash) goes straight through.
                let typing = model.filter.search != lastLoadedSearch && !model.filter.search.isEmpty
                if typing { try? await Task.sleep(for: .milliseconds(300)) }
                if !Task.isCancelled { lastLoadedSearch = model.filter.search; await model.reload() }
            }
            .onChange(of: model.filter.state) { selection = []; editMode = .inactive }
            .refreshable { await model.flushOutbox(); await model.reload(); await directory.refresh() }
            .sheet(isPresented: $showFilter) { SpendFilterSheet(initial: model.filter) { model.filter = $0 }.environment(directory).environment(model) }
            .sheet(item: $bulk, onDismiss: applyTagSelection) { action in pickerSheet(action) }
            .sheet(item: $sharing) { ShareSheet(url: $0) }
            .confirmationDialog("Delete \(selection.count) spend\(selection.count == 1 ? "" : "s")?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete", role: .destructive) { run { await model.bulkDelete(selected) } }
            } message: { Text("You can undo right after, or restore them later from Trash.") }
            .alert("Couldn't finish", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK", role: .cancel) {} } message: { Text(message ?? "") }
            .sensoryFeedback(.success, trigger: done)
        }
    }

    // MARK: Toolbar

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        if editing {
            ToolbarItem(placement: .topBarLeading) { Button(selection.count == model.spends.count ? "Select none" : "Select all") { selection = selection.count == model.spends.count ? [] : Set(model.spends.map(\.id)) } }
            ToolbarItem(placement: .primaryAction) { Button("Done") { editMode = .inactive; selection = [] }.fontWeight(.semibold) }
        } else {
            ToolbarItemGroup(placement: .primaryAction) {
                Button { showFilter = true } label: { Image(systemName: model.filter.activeCount > 0 || model.filter.sort != "date_desc" ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle") }
                    .accessibilityLabel(model.filter.activeCount > 0 ? "Filter, \(model.filter.activeCount) active" : "Filter")
                if !inTrash { ImportMenu(); Button { composer.target = .new } label: { Image(systemName: "plus") }.accessibilityLabel("Add spend") }
                Menu {
                    Button { editMode = .active } label: { Label("Select", systemImage: "checkmark.circle") }
                    Button { var f = model.filter; f.state = inTrash ? "active" : "deleted"; model.filter = f } label: { Label(inTrash ? "Back to spends" : "Trash", systemImage: inTrash ? "tray.full" : "trash") }
                    Button { Task { await exportResults() } } label: { Label(exporting ? "Exporting…" : "Export these results", systemImage: "square.and.arrow.up") }.disabled(exporting)
                } label: { Image(systemName: "ellipsis.circle") }.accessibilityLabel("More")
            }
        }
    }

    // MARK: Bulk

    private var bulkBar: some View {
        HStack(spacing: 4) {
            if inTrash {
                barButton("Restore", "arrow.uturn.backward") { run { await model.bulkRestore(selected) } }
            } else {
                barButton("Category", "square.grid.2x2") { bulk = .category }
                Menu {
                    Button { pickedTags = []; tagAction = .addTags; bulk = .addTags } label: { Label("Add tags", systemImage: "tag") }
                    Button { pickedTags = []; tagAction = .removeTags; bulk = .removeTags } label: { Label("Remove tags", systemImage: "tag.slash") }
                } label: { barLabel("Tags", "tag") }.disabled(selection.isEmpty)
                barButton("Merchant", "storefront") { bulk = .merchant }
                barButton("Delete", "trash", destructive: true) { confirmDelete = true }
            }
        }
        .padding(.horizontal, 8).padding(.vertical, 6)
        .background(.bar)
        .overlay(alignment: .top) { Divider() }
        .disabled(selection.isEmpty)
    }

    private func barLabel(_ title: String, _ icon: String, destructive: Bool = false) -> some View {
        VStack(spacing: 2) { Image(systemName: icon); Text(title).font(.caption2) }
            .frame(maxWidth: .infinity, minHeight: 44).foregroundStyle(destructive ? Tok.critical : Tok.accent)
    }
    private func barButton(_ title: String, _ icon: String, destructive: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) { barLabel(title, icon, destructive: destructive) }.buttonStyle(.plain).accessibilityLabel(title)
    }

    @ViewBuilder private func pickerSheet(_ action: BulkAction) -> some View {
        switch action {
        case .category:
            SinglePicker(title: "Categorize \(selection.count)", items: directory.categoriesByUse(model.spends), noneLabel: "No category") { id in
                var patch = SpendPatch(); patch.categoryId = id
                run { await model.bulkUpdate(selected, patch: patch) }
            }
        case .merchant:
            SinglePicker(title: "Merchant for \(selection.count)", items: directory.merchants.values.sorted { $0.name.lowercased() < $1.name.lowercased() }, noneLabel: "No merchant",
                         create: { name in if case .ok(let id?) = await directory.resolveMerchant(name) { return directory.merchants[id] } else { return nil } }) { id in
                var patch = SpendPatch(); patch.merchantId = id
                run { await model.bulkUpdate(selected, patch: patch) }
            }
        case .addTags:
            MultiPicker(title: "Add tags", items: directory.tagsByUse(model.spends), selection: $pickedTags,
                        create: { name in if case .ok(let t) = await directory.resolveTag(name) { return t } else { return nil } }, emptyHint: "No tags yet. Type a name to create one.")
        case .removeTags:
            let present = Set(selected.flatMap { $0.tagIds ?? [] })
            MultiPicker(title: "Remove tags", items: directory.tagsByUse(model.spends).filter { present.contains($0.id) }, selection: $pickedTags, emptyHint: "None of these spends have tags.")
        }
    }

    /// Tags are picked in a sheet that stays open while ticking, so the change is applied when it closes.
    private func applyTagSelection() {
        guard !pickedTags.isEmpty, !selection.isEmpty else { return }
        let removing = tagAction == .removeTags
        var patch = SpendPatch()
        if removing { patch.removeTagIds = pickedTags.sorted() } else { patch.addTagIds = pickedTags.sorted() }
        pickedTags = []
        run { await model.bulkUpdate(selected, patch: patch) }
    }

    private func run(_ work: @escaping () async -> SpendsModel.BulkOutcome) {
        Task {
            let outcome = await work()
            if outcome.ok { selection = []; editMode = .inactive; done += 1 } else { message = outcome.failure }
        }
    }

    private func exportResults() async {
        exporting = true; defer { exporting = false }
        switch await Exporter.csv(engine: engine, filter: model.filter) {
        case .ok(let csv): if let url = try? Exporter.file(named: "treasure-spends.csv", contents: csv) { sharing = url } else { message = "Couldn't write the export file." }
        case let r: message = r.problem
        }
    }
}

extension URL: @retroactive Identifiable { public var id: String { absoluteString } }

/// The system share sheet for a file.
struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: [url], applicationActivities: nil) }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

/// Placeholder rows while the first page loads; fades to content. Static under Reduce Motion.
struct SkeletonList: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var dim = false

    var body: some View {
        VStack(spacing: 12) {
            ForEach(0..<7, id: \.self) { _ in
                RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Tok.raised).frame(height: 44)
            }
        }
        .padding(20)
        .frame(maxHeight: .infinity, alignment: .top)
        .opacity(dim ? 0.5 : 1)
        .onAppear { if !reduceMotion { withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { dim = true } } }
        .accessibilityLabel("Loading")
    }
}

/// "Spend deleted. Undo" for five seconds after a delete; Undo calls spends_restore.
struct UndoToast: View {
    @Environment(SpendsModel.self) private var model

    var body: some View {
        if let u = model.undo {
            HStack {
                Text(u.spends.count == 1 ? "Spend deleted" : "\(u.spends.count) spends deleted")
                Spacer()
                Button("Undo") { Task { await model.undoDelete() } }.fontWeight(.semibold).frame(minHeight: 44)
            }
            .padding(.horizontal, 16)
            .background(Tok.raised, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Tok.hairline))
            .padding(16)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .task(id: u.id) {
                try? await Task.sleep(for: .seconds(5))
                if !Task.isCancelled { withAnimation(.easeIn(duration: 0.15)) { model.dismissUndo(u.id) } }
            }
            .accessibilityElement(children: .contain)
        }
    }
}

/// Spends saved offline, shown until they reach the engine. A refused one explains why and can be discarded.
struct PendingSection: View {
    @Environment(SpendsModel.self) private var model

    var body: some View {
        if !model.pending.isEmpty {
            Section("Waiting to sync") {
                ForEach(model.pending) { item in
                    HStack(spacing: 12) {
                        Image(systemName: item.failure == nil ? "icloud.slash" : "exclamationmark.triangle")
                            .foregroundStyle(item.failure == nil ? Tok.muted : Tok.warn)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.merchantName ?? item.spend.description ?? item.spend.kind.capitalized).lineLimit(1)
                            Text(item.failure ?? "Will send when you're back online").font(.caption).foregroundStyle(Tok.muted).lineLimit(2)
                        }
                        Spacer(minLength: 8)
                        Text(Money.format(item.spend.amountMinor, currency: item.spend.currency)).amountStyle()
                    }
                    .frame(minHeight: 44)
                    .listRowBackground(Tok.surface)
                    .accessibilityElement(children: .combine)
                    .swipeActions {
                        Button(role: .destructive) { model.outbox.discard(item.id) } label: { Label("Discard", systemImage: "trash") }
                        if item.failure != nil {
                            Button { model.outbox.retry(item.id); Task { await model.flushOutbox() } } label: { Label("Retry", systemImage: "arrow.clockwise") }.tint(Tok.accent)
                        }
                    }
                }
            }
        }
    }
}
