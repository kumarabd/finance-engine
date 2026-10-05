import SwiftUI

/// More → Receipts & documents: the references the engine holds. Files stay wherever they already live (the engine never fetches them).
struct EvidenceView: View {
    @Environment(Engine.self) private var engine
    @Environment(SpendsModel.self) private var spends
    @State private var model: EvidenceModel?

    var body: some View {
        Group { if let model { EvidenceList(model: model) } else { Tok.background } }
            .navigationTitle("Receipts & documents")
            .task {
                guard model == nil else { return }
                let m = EvidenceModel(engine: engine) { [spends] in await spends.reload() }
                model = m
                await m.load()
            }
    }
}

private struct EvidenceList: View {
    @Bindable var model: EvidenceModel
    @State private var editing: Evidence?
    @State private var adding = false

    var body: some View {
        List {
            if let p = model.problem { Text(p).font(.callout).foregroundStyle(Tok.critical).listRowBackground(Tok.background) }
            Section {
                Picker("Show", selection: $model.showDeleted) { Text("Active").tag(false); Text("Deleted").tag(true) }.pickerStyle(.segmented)
            }.listRowBackground(Color.clear)
            Section {
                if model.items.isEmpty && !model.loading {
                    Text(model.showDeleted ? "Nothing deleted." : "No documents yet. Attach one from a spend, or add one here.").foregroundStyle(Tok.muted)
                }
                ForEach(model.items) { e in
                    Button { if !model.showDeleted { editing = e } } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(e.title).foregroundStyle(Tok.text)
                            Text(e.sourceRef).font(.caption).foregroundStyle(Tok.muted).lineLimit(1)
                        }.frame(minHeight: 44, alignment: .leading)
                    }
                    .swipeActions {
                        if model.showDeleted { Button("Restore") { Task { await model.restore(e) } }.tint(Tok.accent) }
                        else { Button("Delete", role: .destructive) { Task { await model.delete(e) } } }
                    }
                }.listRowBackground(Tok.surface)
            } footer: { Text("Swipe to delete. Deleting a document that spends use asks before unlinking it.") }
        }
        .scrollContentBackground(.hidden).background(Tok.background)
        .toolbar { Button { adding = true } label: { Image(systemName: "plus") }.accessibilityLabel("Add document") }
        .sheet(isPresented: $adding) { EvidenceForm(existing: nil) { input in await model.create(input) != nil } }
        .sheet(item: $editing) { e in EvidenceForm(existing: e) { input in await model.update(e, to: input) } }
        .confirmationDialog("Used by spends", isPresented: Binding(get: { model.needsDetach != nil }, set: { if !$0 { model.needsDetach = nil } }), titleVisibility: .visible) {
            Button("Unlink and delete", role: .destructive) { if let e = model.needsDetach { Task { await model.delete(e, detach: true) } } }
        } message: { Text("“\(model.needsDetach?.title ?? "")” is attached to one or more spends. Deleting it unlinks it from them.") }
    }
}

/// Add or edit a document reference. `save` returns true when it worked, which closes the sheet.
struct EvidenceForm: View {
    let existing: Evidence?
    let save: (EvidenceInput) async -> Bool
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var ref = ""
    @State private var notes = ""
    @State private var saving = false
    @State private var failed = false

    private var valid: Bool { !title.trimmingCharacters(in: .whitespaces).isEmpty && !ref.trimmingCharacters(in: .whitespaces).isEmpty }

    var body: some View {
        NavigationStack {
            Form {
                Section { TextField("Title (e.g. Costco receipt)", text: $title); TextField("Where it lives (link or file name)", text: $ref).textInputAutocapitalization(.never).autocorrectionDisabled() }
                    .listRowBackground(Tok.surface)
                Section("Notes") { TextField("Optional", text: $notes, axis: .vertical) }.listRowBackground(Tok.surface)
                if failed { Text("Couldn't save. Check the details and try again.").foregroundStyle(Tok.critical).listRowBackground(Tok.surface) }
            }
            .scrollContentBackground(.hidden).background(Tok.background)
            .navigationTitle(existing == nil ? "Add document" : "Edit document").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        saving = true; failed = false
                        Task {
                            let t = { (s: String) in s.trimmingCharacters(in: .whitespaces) }
                            let ok = await save(EvidenceInput(title: t(title), sourceRef: t(ref), mediaType: existing?.mediaType, checksum: existing?.checksum, notes: t(notes).isEmpty ? nil : t(notes)))
                            saving = false
                            if ok { dismiss() } else { failed = true }
                        }
                    }.disabled(!valid || saving)
                }
            }
            .onAppear { if let e = existing { title = e.title; ref = e.sourceRef; notes = e.notes ?? "" } }
        }
        .presentationDetents([.medium, .large])
    }
}

/// Pick an existing document for a spend, or add a new one. Already-attached documents are left out.
struct EvidencePicker: View {
    let attached: Set<String>
    let onPick: (Evidence) -> Void
    @Environment(Engine.self) private var engine
    @Environment(\.dismiss) private var dismiss
    @State private var model: EvidenceModel?
    @State private var adding = false

    var body: some View {
        NavigationStack {
            List {
                Button { adding = true } label: { Label("New document", systemImage: "plus.circle") }.frame(minHeight: 44).listRowBackground(Tok.surface)
                if let m = model {
                    if let p = m.problem { Text(p).foregroundStyle(Tok.muted).listRowBackground(Tok.surface) }
                    ForEach(m.items.filter { !attached.contains($0.id) }) { e in
                        Button { onPick(e); dismiss() } label: {
                            VStack(alignment: .leading, spacing: 2) { Text(e.title).foregroundStyle(Tok.text); Text(e.sourceRef).font(.caption).foregroundStyle(Tok.muted).lineLimit(1) }
                                .frame(minHeight: 44, alignment: .leading)
                        }.listRowBackground(Tok.surface)
                    }
                }
            }
            .scrollContentBackground(.hidden).background(Tok.background)
            .navigationTitle("Attach document").navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Cancel") { dismiss() } }
            .task { if model == nil { let m = EvidenceModel(engine: engine); model = m; await m.load() } }
            .sheet(isPresented: $adding) {
                EvidenceForm(existing: nil) { input in
                    guard let m = model, let e = await m.create(input) else { return false }
                    onPick(e); dismiss(); return true
                }
            }
        }
        .presentationDetents([.large])
    }
}
