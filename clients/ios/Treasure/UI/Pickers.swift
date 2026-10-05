import SwiftUI

/// Choose several records (tags). Search narrows the list; typing a new name offers to create it.
struct MultiPicker: View {
    let title: String
    let items: [Dimension]
    @Binding var selection: Set<String>
    /// Creates a record from the typed name and returns it (nil if it couldn't be created). Omit to disallow creating.
    var create: ((String) async -> Dimension?)?
    var emptyHint = "Nothing here yet."
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var creating = false

    private var shown: [Dimension] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        return q.isEmpty ? items : items.filter { $0.name.lowercased().contains(q) }
    }
    private var canCreate: Bool {
        let q = query.trimmingCharacters(in: .whitespaces)
        return create != nil && !q.isEmpty && !items.contains { $0.name.lowercased() == q.lowercased() }
    }

    var body: some View {
        NavigationStack {
            List {
                if canCreate {
                    Button {
                        creating = true
                        Task {
                            if let made = await create?(query.trimmingCharacters(in: .whitespaces)) { selection.insert(made.id); query = "" }
                            creating = false
                        }
                    } label: { Label("Create “\(query.trimmingCharacters(in: .whitespaces))”", systemImage: "plus.circle") }
                    .disabled(creating).frame(minHeight: 44).listRowBackground(Tok.surface)
                }
                ForEach(shown) { item in
                    Button { if selection.contains(item.id) { selection.remove(item.id) } else { selection.insert(item.id) } } label: {
                        HStack {
                            Text(item.name).foregroundStyle(Tok.text)
                            Spacer()
                            if selection.contains(item.id) { Image(systemName: "checkmark").foregroundStyle(Tok.accent) }
                        }.frame(minHeight: 44)
                    }
                    .accessibilityAddTraits(selection.contains(item.id) ? .isSelected : [])
                    .listRowBackground(Tok.surface)
                }
            }
            .overlay { if items.isEmpty && !canCreate { ContentUnavailableView(emptyHint, systemImage: "tag") } }
            .scrollContentBackground(.hidden).background(Tok.background)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search or create")
            .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Done") { dismiss() }.fontWeight(.semibold) }
        }
        .presentationDetents([.medium, .large])
    }
}

/// Choose one record (a category or a merchant), or "nothing" when `noneLabel` is given (reported as an empty string).
struct SinglePicker: View {
    let title: String
    let items: [Dimension]
    var noneLabel: String?
    var selected: String?
    /// Creates a record from the typed name (merchants). Omit to disallow creating.
    var create: ((String) async -> Dimension?)?
    let onPick: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var creating = false

    private var shown: [Dimension] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        return q.isEmpty ? items : items.filter { $0.name.lowercased().contains(q) || ($0.aliases ?? []).contains { $0.lowercased().contains(q) } }
    }
    private var canCreate: Bool {
        let q = query.trimmingCharacters(in: .whitespaces)
        return create != nil && !q.isEmpty && !items.contains { $0.name.lowercased() == q.lowercased() }
    }

    private func pick(_ id: String) { onPick(id); dismiss() }

    var body: some View {
        NavigationStack {
            List {
                if let noneLabel, query.isEmpty {
                    Button { pick("") } label: { row(noneLabel, selected: selected == "") }.listRowBackground(Tok.surface)
                }
                if canCreate {
                    Button {
                        creating = true
                        Task { if let made = await create?(query.trimmingCharacters(in: .whitespaces)) { pick(made.id) }; creating = false }
                    } label: { Label("Create “\(query.trimmingCharacters(in: .whitespaces))”", systemImage: "plus.circle") }
                    .disabled(creating).frame(minHeight: 44).listRowBackground(Tok.surface)
                }
                ForEach(shown) { item in
                    Button { pick(item.id) } label: { row(item.name, selected: selected == item.id) }.listRowBackground(Tok.surface)
                }
            }
            .scrollContentBackground(.hidden).background(Tok.background)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search")
            .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Cancel") { dismiss() } }
        }
        .presentationDetents([.medium, .large])
    }

    private func row(_ name: String, selected: Bool) -> some View {
        HStack {
            Text(name).foregroundStyle(Tok.text)
            Spacer()
            if selected { Image(systemName: "checkmark").foregroundStyle(Tok.accent) }
        }
        .frame(minHeight: 44)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
