package org.nighthawklabs.treasure.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.nighthawklabs.treasure.Session
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.testTag
import org.nighthawklabs.treasure.data.*
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.problem
import org.nighthawklabs.treasure.ui.components.*
import org.nighthawklabs.treasure.ui.theme.HeroStyle
import org.nighthawklabs.treasure.ui.theme.Tabular
import org.nighthawklabs.treasure.ui.theme.Treasure
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

private fun axisLabel(key: String, group: String): String {
    val d = runCatching { LocalDate.parse(key) }.getOrNull() ?: return key
    return d.format(DateTimeFormatter.ofPattern(if (group == "month") "MMM" else "MMM d", Locale.getDefault()))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(session: Session, openSpends: (SpendFilter) -> Unit) {
    val t = Treasure.tok
    val scope = rememberCoroutineScope()
    val still = reduceMotion()
    val revision by remember { derivedStateOf { session.spends.state.value.revision } }
    var period by remember { mutableStateOf(Period.Month) }
    var snapshot by remember { mutableStateOf<Snapshot?>(null) }
    var currency by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var loaded by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        when (val r = session.insights.snapshot(period)) {
            is Api.Ok -> { snapshot = r.value; problem = null; selected = null; loaded++ ; if (currency != null && currency !in r.value.currencies) currency = null }
            else -> problem = r.problem
        }
    }
    LaunchedEffect(period) { load() }
    val rev by session.spends.state.collectAsState()
    LaunchedEffect(rev.revision) { if (snapshot != null) load() }

    Scaffold(
        containerColor = t.background,
        topBar = { TopAppBar(title = { Text("Insights") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = t.background)) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { refreshing = true; load(); refreshing = false } }, modifier = Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Period.entries.forEachIndexed { i, p ->
                        SegmentedButton(selected = period == p, onClick = { period = p }, shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size), label = { Text(p.title, maxLines = 1) })
                    }
                }
                val snap = snapshot
                val cur = currency ?: snap?.currencies?.firstOrNull()
                when {
                    snap != null && cur != null -> {
                        if (snap.currencies.size > 1) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            snap.currencies.forEachIndexed { i, c -> SegmentedButton(selected = c == cur, onClick = { currency = c; selected = null }, shape = SegmentedButtonDefaults.itemShape(i, snap.currencies.size), label = { Text(c) }) }
                        }
                        val series = snap.series(cur)
                        val point = selected?.let { series.getOrNull(it) }
                        val total = snap.total(cur)
                        Column {
                            Text(point?.let { axisLabel(it.key, snap.period.trendGroup) } ?: "Spent", color = t.muted)
                            AnimatedAmount(Money.format(point?.minor ?: total.first, cur), HeroStyle, t.text)
                            if (point == null) Delta.text(total.first, total.second, snap.period.comparedWith)?.let { Text(it, color = t.muted) }
                        }
                        TrendChart(snap, cur, selected, { selected = it }, key = loaded, still = still)
                        fun drill(d: Drill.Dimension): (BreakdownRow) -> Unit = { row -> Drill.filter(row, d, snap.window, cur)?.let(openSpends) }
                        BreakdownSection("Categories", Breakdown.top(snap.categories.current, cur), cur, onSelect = drill(Drill.Dimension.Category))
                        BreakdownSection("Top merchants", Breakdown.top(snap.merchants.current, cur), cur, onSelect = drill(Drill.Dimension.Merchant))
                        BreakdownSection("Tags", Breakdown.top(snap.tags.current, cur), cur, onSelect = drill(Drill.Dimension.Tag),
                            footnote = "A spend with several tags counts under each, so these add up to more than the total.")
                    }
                    snap != null -> EmptyView("No spending in this period", Modifier.height(240.dp))
                    problem != null -> ProblemView(problem!!, Modifier.height(320.dp)) { scope.launch { load() } }
                    else -> SkeletonList()
                }
            }
        }
    }
}

@Composable
private fun TrendChart(snap: Snapshot, currency: String, selected: Int?, onSelect: (Int?) -> Unit, key: Int, still: Boolean) {
    val t = Treasure.tok
    val haptics = rememberHaptics()
    val measurer = rememberTextMeasurer()
    val series = remember(snap, currency) { snap.series(currency) }
    val previous = remember(snap, currency) { snap.previousSeries(currency) }
    val group = snap.period.trendGroup
    val progress = remember(key) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(key) { if (!still) progress.animateTo(1f, tween(400, easing = FastOutSlowInEasing)) }
    LaunchedEffect(selected) { if (selected != null) haptics.detent() }

    val values = series.map { Money.toDouble(max(it.minor, 0), currency) }
    val prev = previous.map { Money.toDouble(max(it.minor, 0), currency) }
    val maxV = (values + prev).maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val whole = remember(currency) { NumberFormat.getCurrencyInstance().apply { this.currency = Currency.getInstance(currency); maximumFractionDigits = 0; minimumFractionDigits = 0 } }
    val labelStyle = TextStyle(fontSize = 11.sp, color = t.muted, fontFeatureSettings = "tnum")
    val summary = remember(series) {
        val top = series.maxByOrNull { it.minor }
        "Spending trend. Total ${Money.format(series.sumOf { it.minor }, currency)}." + (top?.takeIf { it.minor > 0 }?.let { " Highest: ${axisLabel(it.key, group)}, ${Money.format(it.minor, currency)}." } ?: "")
    }

    Column {
        Canvas(
            Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = summary }
                .pointerInput(series.size) {
                    val left = 44.dp.toPx()
                    fun idx(x: Float) = (((x - left) / (size.width - left)) * series.size).toInt().coerceIn(0, (series.size - 1).coerceAtLeast(0))
                    detectHorizontalDragGestures(onDragStart = { onSelect(idx(it.x)) }, onDragEnd = { onSelect(null) }, onDragCancel = { onSelect(null) }) { change, _ -> onSelect(idx(change.position.x)) }
                }
                .pointerInput(series.size) {
                    val left = 44.dp.toPx()
                    detectTapGestures { p -> val i = (((p.x - left) / (size.width - left)) * series.size).toInt().coerceIn(0, (series.size - 1).coerceAtLeast(0)); onSelect(if (selected == i) null else i) }
                },
        ) {
            val left = 44.dp.toPx(); val bottom = 20.dp.toPx(); val top = 8.dp.toPx()
            val plotW = size.width - left; val plotH = size.height - bottom - top
            val n = series.size.coerceAtLeast(1)
            val slot = plotW / n
            // Gridlines at 0, half and max, labeled in whole currency units.
            for (f in listOf(0.0, 0.5, 1.0)) {
                val y = top + plotH * (1 - f.toFloat())
                drawLine(t.hairline, Offset(left, y), Offset(size.width, y), 1.dp.toPx())
                val label = whole.format(maxV * f)
                val m = measurer.measure(label, labelStyle)
                drawText(m, topLeft = Offset(left - m.size.width - 6.dp.toPx(), y - m.size.height / 2f))
            }
            // Bars grow from the baseline as the chart draws in.
            series.forEachIndexed { i, _ ->
                val h = (values[i] / maxV).toFloat() * plotH * progress.value
                val dim = selected != null && selected != i
                drawRoundRect(t.accent.copy(alpha = if (dim) .35f else 1f), Offset(left + slot * i + slot * .15f, top + plotH - h), Size(slot * .7f, h), CornerRadius(3.dp.toPx()))
            }
            // The earlier period, lined up bucket for bucket.
            if (prev.isNotEmpty()) {
                val path = Path()
                prev.take(series.size).forEachIndexed { i, v ->
                    val x = left + slot * i + slot / 2; val y = top + plotH - (v / maxV).toFloat() * plotH * progress.value
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, t.muted, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            }
            // About four x labels.
            val step = (series.size / 4).coerceAtLeast(1)
            series.forEachIndexed { i, p ->
                if (i % step == 0) {
                    val m = measurer.measure(axisLabel(p.key, group), labelStyle)
                    drawText(m, topLeft = Offset((left + slot * i + slot / 2 - m.size.width / 2f).coerceIn(left, size.width - m.size.width), size.height - bottom + 4.dp.toPx()))
                }
            }
        }
        if (previous.isNotEmpty()) Text("Dashed: ${snap.period.comparedWith}", color = t.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
    }
}

private fun max(a: Long, b: Long) = if (a > b) a else b

/** A ranked list, not a donut: bars on one baseline compare precisely, and every row carries its own name and amount. */
@Composable
private fun BreakdownSection(title: String, rows: List<BreakdownRow>, currency: String, onSelect: (BreakdownRow) -> Unit, footnote: String? = null) {
    if (rows.isEmpty()) return
    val t = Treasure.tok
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            rows.forEachIndexed { i, row ->
                val share by animateFloatAsState(row.share.toFloat(), tween(300), label = "share")
                Column(Modifier.testTag("breakdown-${row.label}").fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Show these spends") { onSelect(row) }.semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text(row.label, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(Money.format(row.minor, currency), style = Tabular)
                        Text("${Math.round(row.share * 100)}%", color = t.muted, style = MaterialTheme.typography.bodySmall.merge(Tabular), modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                    Box(Modifier.fillMaxWidth().height(6.dp)) {
                        Box(Modifier.fillMaxWidth(share.coerceAtLeast(.01f)).fillMaxHeight().background(if (row.key == "other") t.muted.copy(alpha = .5f) else t.series[i % t.series.size], RoundedCornerShape(3.dp)))
                    }
                }
            }
            footnote?.let { Text(it, color = t.muted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
