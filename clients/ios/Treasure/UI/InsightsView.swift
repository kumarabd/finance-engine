import Charts
import SwiftUI

/// Money as a chart-friendly number, using the currency's own fraction digits.
private func chartValue(_ minor: Int64, _ currency: String) -> Double {
    let f = NumberFormatter(); f.numberStyle = .currency; f.currencyCode = currency
    return Double(minor) / pow(10, Double(f.maximumFractionDigits))
}

/// "Oct 4", "Oct", etc. for a bucket key.
private func axisLabel(_ key: String, group: String) -> String {
    guard let d = CalendarDate.date(from: key) else { return key }
    return group == "month" ? d.formatted(.dateTime.month(.abbreviated)) : d.formatted(.dateTime.month(.abbreviated).day())
}

struct InsightsView: View {
    @Environment(Engine.self) private var engine
    @Environment(SpendsModel.self) private var spends
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var period: Period = .month
    @State private var snapshot: Snapshot?
    @State private var currency: String?
    @State private var problem: String?
    @State private var selected: String?
    @State private var drawn = false

    private var shownCurrency: String? { currency ?? snapshot?.currencies.first }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Picker("Period", selection: $period) { ForEach(Period.allCases) { Text($0.title).tag($0) } }
                        .pickerStyle(.segmented)
                    if let snap = snapshot, let cur = shownCurrency {
                        if snap.currencies.count > 1 {
                            Picker("Currency", selection: Binding(get: { cur }, set: { currency = $0; selected = nil })) {
                                ForEach(snap.currencies, id: \.self) { Text($0).tag($0) }
                            }.pickerStyle(.segmented)
                        }
                        header(snap, cur)
                        TrendChart(snap: snap, currency: cur, selected: $selected, drawn: drawn)
                        BreakdownSection(title: "Categories", rows: Breakdown.top(snap.categories.current, currency: cur), currency: cur)
                        BreakdownSection(title: "Top merchants", rows: Breakdown.top(snap.merchants.current, currency: cur), currency: cur)
                    } else if snapshot != nil {
                        ContentUnavailableView("No spending in this period", systemImage: "chart.bar")
                    } else if let problem {
                        ProblemView(message: problem) { await load() }
                    } else {
                        SkeletonList().frame(height: 300)
                    }
                }
                .padding(20)
            }
            .background(Tok.background)
            .navigationTitle("Insights")
            .refreshable { await load() }
            .task(id: period) { await load() }
            .task(id: spends.revision) { if snapshot != nil { await load() } }
        }
    }

    @ViewBuilder private func header(_ snap: Snapshot, _ cur: String) -> some View {
        let series = snap.series(cur)
        let point = selected.flatMap { k in series.first { $0.key == k } }
        let t = snap.total(cur)
        VStack(alignment: .leading, spacing: 4) {
            Text(point.map { axisLabel($0.key, group: snap.period.trendGroup) } ?? "Spent")
                .font(.subheadline).foregroundStyle(Tok.muted)
            Text(Money.format(point?.minor ?? t.current, currency: cur))
                .heroAmount().contentTransition(.numericText())
                .animation(.snappy(duration: 0.2), value: point?.minor ?? t.current)
            if point == nil, let d = Delta.text(current: t.current, previous: t.previous, against: snap.period.comparedWith) {
                Text(d).font(.subheadline).foregroundStyle(Tok.muted)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func load() async {
        let r = await Insights(engine: engine).snapshot(period)
        switch r {
        case .ok(let s):
            snapshot = s; problem = nil; selected = nil
            if let c = currency, !s.currencies.contains(c) { currency = nil }
            if reduceMotion { drawn = true } else { drawn = false; withAnimation(.easeOut(duration: 0.4)) { drawn = true } }
        default: problem = r.problem
        }
    }
}

struct TrendChart: View {
    let snap: Snapshot
    let currency: String
    @Binding var selected: String?
    let drawn: Bool

    var body: some View {
        let series = snap.series(currency)
        let previous = snap.previousSeries(currency)
        let group = snap.period.trendGroup
        let step = max(1, series.count / 4)
        let labelKeys = series.enumerated().filter { $0.offset % step == 0 }.map(\.element.key)
        Chart {
            ForEach(series) { p in
                BarMark(x: .value("Date", p.key), y: .value("Spent", drawn ? chartValue(max(p.minor, 0), currency) : 0))
                    .foregroundStyle(selected == nil || selected == p.key ? Tok.accent : Tok.accent.opacity(0.35))
                    .cornerRadius(3)
            }
            // The earlier period, lined up bucket for bucket underneath.
            ForEach(Array(previous.enumerated()), id: \.offset) { i, p in
                if i < series.count {
                    LineMark(x: .value("Date", series[i].key), y: .value("Previous", drawn ? chartValue(max(p.minor, 0), currency) : 0),
                             series: .value("Series", "Previous"))
                        .foregroundStyle(Tok.muted)
                        .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
                        .interpolationMethod(.monotone)
                }
            }
        }
        .chartXSelection(value: $selected)
        .chartXAxis {
            AxisMarks(values: labelKeys) { v in
                AxisValueLabel { if let k = v.as(String.self) { Text(axisLabel(k, group: group)).font(.caption2) } }
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading) { v in
                AxisGridLine().foregroundStyle(Tok.hairline)
                AxisValueLabel { if let d = v.as(Double.self) { Text(d, format: .currency(code: currency).precision(.fractionLength(0))).font(.caption2) } }
            }
        }
        .frame(height: 200)
        .sensoryFeedback(.selection, trigger: selected)
        .overlay(alignment: .bottomTrailing) {
            if !previous.isEmpty {
                Label(snap.period.comparedWith.capitalized, systemImage: "line.diagonal").font(.caption2).foregroundStyle(Tok.muted)
                    .padding(4).background(Tok.background.opacity(0.8), in: Capsule())
            }
        }
        .accessibilityElement()
        .accessibilityLabel("Spending trend. Total \(Money.format(series.reduce(0) { $0 + $1.minor }, currency: currency))")
        .accessibilityChartDescriptor(TrendDescriptor(series: series, currency: currency, group: group))
    }
}

/// Lets VoiceOver read the chart bar by bar and play its audio graph.
private struct TrendDescriptor: AXChartDescriptorRepresentable {
    let series: [TrendPoint]
    let currency: String
    let group: String

    func makeChartDescriptor() -> AXChartDescriptor {
        let x = AXCategoricalDataAxisDescriptor(title: "Date", categoryOrder: series.map { axisLabel($0.key, group: group) })
        let values = series.map { chartValue($0.minor, currency) }
        let y = AXNumericDataAxisDescriptor(title: "Spent", range: 0...(values.max() ?? 1), gridlinePositions: []) {
            Money.format(Int64(($0 * 100).rounded()), currency: currency)
        }
        let s = AXDataSeriesDescriptor(name: "Spent", isContinuous: false, dataPoints: zip(series, values).map {
            AXDataPoint(x: axisLabel($0.0.key, group: group), y: $0.1)
        })
        return AXChartDescriptor(title: "Spending trend", summary: nil, xAxis: x, yAxis: y, additionalAxes: [], series: [s])
    }
}

/// A ranked list, not a donut: bars on one baseline compare precisely, and every row carries its own name and amount.
struct BreakdownSection: View {
    let title: String
    let rows: [BreakdownRow]
    let currency: String

    var body: some View {
        if !rows.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Text(title).font(.headline)
                ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(row.label).lineLimit(1)
                            Spacer()
                            Text(Money.format(row.minor, currency: currency)).amountStyle()
                            Text(row.share, format: .percent.precision(.fractionLength(0))).font(.caption).foregroundStyle(Tok.muted)
                                .frame(width: 40, alignment: .trailing).amountStyle()
                        }
                        GeometryReader { g in
                            Capsule().fill(row.key == "other" ? Tok.muted.opacity(0.5) : Tok.series[i % Tok.series.count])
                                .frame(width: max(4, g.size.width * row.share))
                        }
                        .frame(height: 6)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
            .padding(16)
            .background(Tok.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Tok.hairline))
            .animation(.easeInOut(duration: 0.25), value: rows)
        }
    }
}
