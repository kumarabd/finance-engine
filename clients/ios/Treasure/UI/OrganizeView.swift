import SwiftUI

struct OrganizeView: View {
    let kind: DimensionKind
    @Environment(Engine.self) private var engine
    @Environment(Directory.self) private var directory
    @Environment(SpendsModel.self) private var spends
    @State private var model: OrganizeModel?

    var body: some View {
        Group {
            if let model { OrganizeList(model: model) } else { Tok.background }
        }
        .navigationTitle(kind.title)
        .task {
            guard model == nil else { return }
            let m = OrganizeModel(kind: kind, engine: engine) { [directory, spends] in await directory.refresh(); await spends.reload() }
            model = m
            await m.load()
        }
    }
}

private struct OrganizeList: View {
    @Bindable var model: OrganizeModel
    @State private var naming: Naming?
    @State private var text = ""
    @State private var picking: Picking?
    @State private var aliasing: Dimension?
    @State private var aliasText = ""
    @State private var viewingHistory: Dimension?

    struct Naming: Identifiable { var id = UUID(); var existing: Dimension? }
    struct Picking: Identifiable {
        var id: String { source.id + (merge ? "m" : "r") }
        var source: Dimension; var merge: Bool
    }

    var body: some View {
        List {
            if let p = model.problem { Text(p).font(.callout).foregroundStyle(Tok.critical).listRowBackground(Tok.background) }
            Section {
                Picker("Show", selection: $model.showDeleted) { Text("Active").tag(false); Text("Deleted").tag(true) }.pickerStyle(.segmented)
            }.listRowBackground(Color.clear)
            ForEach(model.items) { d in
                VStack(alignment: .leading, spacing: 2) {
                    Text(d.name)
                    if let a = d.aliases, !a.isEmpty { Text(a.joined(separator: ", ")).font(.caption).foregroundStyle(Tok.muted).lineLimit(1) }
                }
                .frame(minHeight: 44, alignment: .leading)
                .listRowBackground(Tok.surface)
                .swipeActions {
                    if model.showDeleted { Button { Task { await model.restore(d) } } label: { Label("Restore", systemImage: "arrow.uturn.backward") }.tint(Tok.accent) }
                    else {
                        Button(role: .destructive) { Task { await model.delete(d) } } label: { Label("Delete", systemImage: "trash") }
                        Button { text = d.name; naming = Naming(existing: d) } label: { Label("Rename", systemImage: "pencil") }.tint(Tok.accent)
                    }
                }
                .contextMenu {
                    if !model.showDeleted {
                        Button { text = d.name; naming = Naming(existing: d) } label: { Label("Rename", systemImage: "pencil") }
                        if model.kind == .merchant { Button { aliasText = (d.aliases ?? []).joined(separator: ", "); aliasing = d } label: { Label("Edit aliases", systemImage: "text.badge.plus") } }
                        Button { picking = Picking(source: d, merge: true) } label: { Label("Merge into…", systemImage: "arrow.triangle.merge") }
                    }
                    Button { viewingHistory = d } label: { Label("History", systemImage: "clock.arrow.circlepath") }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Tok.background)
        .overlay {
            if model.loading { ProgressView() }
            else if model.items.isEmpty && model.problem == nil { ContentUnavailableView(model.showDeleted ? "Nothing deleted" : "No \(model.kind.plural) yet", systemImage: "tag") }
        }
        .animation(.easeInOut(duration: 0.25), value: model.items)
        .toolbar { Button { text = ""; naming = Naming(existing: nil) } label: { Image(systemName: "plus") }.accessibilityLabel("Add \(model.kind.rawValue)") }
        .refreshable { await model.load() }
        .alert(naming?.existing == nil ? "New \(model.kind.rawValue)" : "Rename", isPresented: Binding(get: { naming != nil }, set: { if !$0 { naming = nil } })) {
            TextField("Name", text: $text)
            Button("Save") {
                let name = text.trimmingCharacters(in: .whitespaces), n = naming
                guard !name.isEmpty else { return }
                Task { if let e = n?.existing { await model.rename(e, to: name) } else { await model.create(name) } }
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Aliases", isPresented: Binding(get: { aliasing != nil }, set: { if !$0 { aliasing = nil } })) {
            TextField("Comma-separated", text: $aliasText)
            Button("Save") {
                guard let d = aliasing else { return }
                let list = aliasText.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
                Task { await model.setAliases(d, to: list) }
            }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Other names this merchant appears under, such as “AMZN MKTP”.") }
        .sheet(item: $viewingHistory) { d in DimensionHistory(model: model, record: d) }
        .sheet(item: $picking) { p in
            TargetPicker(title: "Merge “\(p.source.name)” into", candidates: model.items.filter { $0.id != p.source.id }) { target in
                Task { await model.merge(p.source, into: target) }
            }
        }
        .sheet(item: $model.needsReplacement) { d in
            TargetPicker(title: "Spends use “\(d.name)”. Move them to",
                         candidates: model.items.filter { $0.id != d.id }) { target in
                Task { await model.delete(d, replacement: target) }
            }
        }
    }
}

/// Choose another record: used to merge, or to take over spends when deleting something still in use.
private struct TargetPicker: View {
    let title: String
    let candidates: [Dimension]
    let onPick: (Dimension) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(candidates) { c in
                Button { onPick(c); dismiss() } label: { Text(c.name).frame(minHeight: 44, alignment: .leading) }
                    .listRowBackground(Tok.surface)
            }
            .scrollContentBackground(.hidden)
            .background(Tok.background)
            .overlay { if candidates.isEmpty { ContentUnavailableView("Nothing to move them to", systemImage: "tray") } }
            .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Cancel") { dismiss() } }
        }
        .presentationDetents([.medium, .large])
    }
}

/// What changed on a category, tag or merchant, newest first.
private struct DimensionHistory: View {
    let model: OrganizeModel
    let record: Dimension
    @Environment(\.dismiss) private var dismiss
    @State private var changes: [Change] = []
    @State private var problem: String?
    @State private var loading = true

    var body: some View {
        NavigationStack {
            List {
                if let problem { Text(problem).foregroundStyle(Tok.muted).listRowBackground(Tok.surface) }
                ForEach(changes) { c in
                    HStack {
                        Text(c.operation.replacingOccurrences(of: "_", with: " ").capitalized)
                        Spacer()
                        Text(c.occurredAt.prefix(16).replacingOccurrences(of: "T", with: " ")).font(.caption).foregroundStyle(Tok.muted).amountStyle()
                    }.frame(minHeight: 44).listRowBackground(Tok.surface)
                }
            }
            .scrollContentBackground(.hidden).background(Tok.background)
            .overlay { if loading { ProgressView() } else if changes.isEmpty && problem == nil { ContentUnavailableView("No history", systemImage: "clock") } }
            .navigationTitle(record.name).navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Done") { dismiss() } }
            .task {
                switch await model.history(record) {
                case .ok(let p): changes = p.items
                case let r: problem = r.problem
                }
                loading = false
            }
        }
        .presentationDetents([.medium, .large])
    }
}
