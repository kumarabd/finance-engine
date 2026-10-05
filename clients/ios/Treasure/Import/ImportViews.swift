import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import VisionKit

/// Owns the import entry points (camera, photos, files) and the review sheet; lives in the signed-in shell.
@MainActor @Observable
final class Importer {
    var showFiles = false
    var showPhotos = false
    var showCamera = false
    var photos: [PhotosPickerItem] = []
    var session: ImportSession?
    var failure: String?
}

/// Next to "+" in the top bar. A tap on "+" stays the fast manual add.
struct ImportMenu: View {
    @Environment(Importer.self) private var importer
    var body: some View {
        Menu {
            if VNDocumentCameraViewController.isSupported {
                Button { importer.showCamera = true } label: { Label("Scan with camera", systemImage: "doc.viewfinder") }
            }
            Button { importer.showPhotos = true } label: { Label("Choose photos", systemImage: "photo") }
            Button { importer.showFiles = true } label: { Label("Import file (PDF, image, CSV)", systemImage: "doc") }
        } label: { Image(systemName: "doc.text.viewfinder") }
        .accessibilityLabel("Import receipt or statement")
    }
}

/// The modifiers that make the entry points work. Applied once, to the signed-in shell.
struct ImportHost: ViewModifier {
    @Bindable var importer: Importer
    let engine: Engine, directory: Directory, spends: SpendsModel

    func body(content: Content) -> some View {
        content
            .fileImporter(isPresented: $importer.showFiles, allowedContentTypes: [.pdf, .image, .commaSeparatedText, .plainText]) { result in
                guard case .success(let url) = result else { return }
                begin { await $0.start(url: url) }
            }
            .photosPicker(isPresented: $importer.showPhotos, selection: $importer.photos, maxSelectionCount: 10, matching: .images)
            .onChange(of: importer.photos) { _, picked in
                guard !picked.isEmpty else { return }
                importer.photos = []
                begin { session in
                    var images: [UIImage] = []
                    for p in picked { if let d = try? await p.loadTransferable(type: Data.self), let i = UIImage(data: d) { images.append(i) } }
                    await session.start(images: images)
                }
            }
            .fullScreenCover(isPresented: $importer.showCamera) {
                DocumentCamera { images in
                    importer.showCamera = false
                    if !images.isEmpty { begin { await $0.start(images: images) } }
                }.ignoresSafeArea()
            }
            .sheet(item: $importer.session) { ImportReviewView(session: $0).environment(directory).environment(spends) }
    }

    private func begin(_ work: @escaping (ImportSession) async -> Void) {
        let s = ImportSession(engine: engine, directory: directory, spends: spends)
        importer.session = s
        Task { await work(s) }
    }
}

struct DocumentCamera: UIViewControllerRepresentable {
    let done: ([UIImage]) -> Void
    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let c = VNDocumentCameraViewController(); c.delegate = context.coordinator; return c
    }
    func updateUIViewController(_ vc: VNDocumentCameraViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(done) }

    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        let done: ([UIImage]) -> Void
        init(_ done: @escaping ([UIImage]) -> Void) { self.done = done }
        func documentCameraViewController(_ c: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
            done((0..<scan.pageCount).map { scan.imageOfPage(at: $0) })
        }
        func documentCameraViewControllerDidCancel(_ c: VNDocumentCameraViewController) { done([]) }
        func documentCameraViewController(_ c: VNDocumentCameraViewController, didFailWithError error: Error) { done([]) }
    }
}

struct ImportReviewView: View {
    @Bindable var session: ImportSession
    @Environment(Directory.self) private var directory
    @Environment(SpendsModel.self) private var spends
    @Environment(\.dismiss) private var dismiss
    @State private var showText = false

    var body: some View {
        NavigationStack {
            Group {
                switch session.phase {
                case .reading: VStack(spacing: 12) { ProgressView(); Text("Reading on this device…").foregroundStyle(Tok.muted) }
                case .review: review
                case .saving: VStack(spacing: 16) { ProgressView(value: session.progress).padding(.horizontal, 40); Text("Importing…").foregroundStyle(Tok.muted) }
                case .done(let made, let skipped): done(made, skipped)
                case .failed(let message): ContentUnavailableView { Label("Couldn't import", systemImage: "exclamationmark.triangle") } description: { Text(message) } actions: { Button("Close") { dismiss() } }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Tok.background)
            .navigationTitle("Import").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { if session.phase != .saving { Button("Close") { dismiss() } } } }
            .animation(.easeInOut(duration: 0.25), value: session.phase)
        }
        .interactiveDismissDisabled(session.phase == .saving)
    }

    // MARK: Review

    private var review: some View {
        VStack(spacing: 0) {
            List {
                Section {
                    if session.doc?.isCSV == false {
                        Picker("Document", selection: $session.asStatement) { Text("Receipt").tag(false); Text("Statement").tag(true) }.pickerStyle(.segmented)
                    }
                    if session.asStatement {
                        Picker("Money out is shown as", selection: $session.negativeIsSpend) { Text("Negative").tag(true); Text("Positive").tag(false) }.pickerStyle(.segmented)
                    }
                    HStack {
                        Text("Currency").foregroundStyle(Tok.muted)
                        Spacer()
                        Picker("Currency", selection: $session.currency) {
                            ForEach(Array(Set(["USD", "EUR", "GBP", "INR", "JPY", "CAD", "AUD", session.currency])).sorted(), id: \.self) { Text($0).tag($0) }
                        }.pickerStyle(.menu)
                    }
                    Text(summary).font(.footnote).foregroundStyle(Tok.muted)
                }
                .listRowBackground(Color.clear)
                .onChange(of: session.asStatement) { Task { await session.reparse() } }
                .onChange(of: session.negativeIsSpend) { Task { await session.reparse() } }
                .onChange(of: session.currency) { Task { await session.reparse() } }

                if session.items.isEmpty {
                    ContentUnavailableView("No transactions found", systemImage: "doc.text.magnifyingglass",
                                           description: Text("Try a clearer photo, or switch between Receipt and Statement."))
                        .listRowBackground(Color.clear)
                }
                ForEach($session.items) { $item in row($item).listRowBackground(Tok.surface) }

                if let text = session.doc?.text, !text.isEmpty {
                    Section {
                        DisclosureGroup("Text read from the file", isExpanded: $showText) {
                            Text(text).font(.system(.caption, design: .monospaced)).foregroundStyle(Tok.muted).textSelection(.enabled)
                        }
                    }.listRowBackground(Tok.surface)
                }
            }
            .scrollContentBackground(.hidden)

            Button("Import \(session.selected) spend\(session.selected == 1 ? "" : "s")") { Task { await session.save() } }
                .buttonStyle(PrimaryButtonStyle()).disabled(session.selected == 0).padding(16)
        }
    }

    private var summary: String {
        var parts = ["\(session.items.count) found", "\(session.selected) selected"]
        if session.duplicates > 0 { parts.append("\(session.duplicates) look like ones you already have") }
        return parts.joined(separator: " · ") + ". Check each row before importing."
    }

    private func row(_ item: Binding<ParsedItem>) -> some View {
        let i = item.wrappedValue
        let category = directory.category(i.categoryId)
        return HStack(alignment: .top, spacing: 8) {
            Button { item.include.wrappedValue.toggle() } label: {
                Image(systemName: i.include ? "checkmark.circle.fill" : "circle").font(.title3).foregroundStyle(i.include ? Tok.accent : Tok.muted).frame(minWidth: 36, minHeight: 44)
            }
            .buttonStyle(.plain).accessibilityLabel(i.include ? "Selected" : "Not selected")
            VStack(alignment: .leading, spacing: 2) {
                Text(i.merchantName ?? (i.description.isEmpty ? i.kind.capitalized : i.description)).lineLimit(1)
                Text("\(DayGroups.title(i.date)) · \(category ?? (i.kind == "expense" ? "Uncategorized" : i.kind.capitalized))")
                    .font(.caption).foregroundStyle(Tok.muted).lineLimit(1)
                if i.duplicateOf != nil { Text("Looks like one you already have").font(.caption).foregroundStyle(Tok.warn) }
                else if i.possibleDuplicateOf != nil { Text("Might match an entry you already have").font(.caption).foregroundStyle(Tok.muted) }
                else if i.merchantId == nil, i.merchantName != nil { Text("New merchant").font(.caption).foregroundStyle(Tok.muted) }
            }
            Spacer(minLength: 8)
            Text(Money.format(i.amountMinor, currency: i.currency)).amountStyle().foregroundStyle(i.kind == "refund" ? Tok.live : Tok.text)
            Menu {
                Picker("Type", selection: item.kind) { Text("Expense").tag("expense"); Text("Refund").tag("refund"); Text("Transfer").tag("transfer") }
                Picker("Category", selection: item.categoryId) {
                    Text("None").tag(String?.none)
                    ForEach(directory.categoriesByUse(spends.spends)) { Text($0.name).tag(Optional($0.id)) }
                }
            } label: { Image(systemName: "ellipsis.circle").frame(minWidth: 36, minHeight: 44) }
            .accessibilityLabel("Edit type and category")
        }
        .opacity(i.include ? 1 : 0.55)
        .animation(.easeOut(duration: 0.15), value: i.include)
    }

    private func done(_ made: Int, _ skipped: Int) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "checkmark.circle.fill").font(.system(size: 56)).foregroundStyle(Tok.accent).symbolEffect(.bounce, value: made)
            Text("Imported \(made) spend\(made == 1 ? "" : "s")").font(.title3.weight(.semibold))
            if skipped > 0 { Text("\(skipped) were already in Treasure.").foregroundStyle(Tok.muted) }
            Button("Done") { dismiss() }.buttonStyle(PrimaryButtonStyle()).padding(.horizontal, 24).padding(.top, 16)
        }
        .sensoryFeedback(.success, trigger: made)
    }
}
