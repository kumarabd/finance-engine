package org.nighthawklabs.treasure.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nighthawklabs.treasure.EditorTarget
import org.nighthawklabs.treasure.Session
import org.nighthawklabs.treasure.data.*
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.problem
import org.nighthawklabs.treasure.ui.components.AnimatedAmount
import org.nighthawklabs.treasure.ui.theme.HeroStyle
import org.nighthawklabs.treasure.ui.theme.Tabular
import org.nighthawklabs.treasure.ui.theme.Treasure

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(session: Session, id: String, onBack: () -> Unit) {
    val t = Treasure.tok
    val state by session.spends.state.collectAsState()
    // The newest copy the list holds, so an edit shows here the moment it is saved.
    val spend = state.spends.firstOrNull { it.id == id }
    val categories by session.directory.categories.collectAsState()
    val tagsById by session.directory.tags.collectAsState()
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<Change>>(emptyList()) }
    var historyProblem by remember { mutableStateOf<String?>(null) }

    var documents by remember { mutableStateOf<List<Evidence>>(emptyList()) }
    var attaching by remember { mutableStateOf(false) }
    var linkProblem by remember { mutableStateOf<String?>(null) }
    val evidence = remember { EvidenceStore(session.engine) }
    LaunchedEffect(id, spend?.version) { documents = spend?.evidenceIds.orEmpty().mapNotNull { evidence.get(it) } }
    var originalSpend by remember { mutableStateOf<Spend?>(null) }
    var refunds by remember { mutableStateOf<List<Spend>>(emptyList()) }
    LaunchedEffect(id, spend?.version) {
        originalSpend = null; refunds = emptyList()
        val s = spend ?: return@LaunchedEffect
        if (s.kind == "refund" && s.originalSpendId != null) originalSpend = session.spends.lookup(s.originalSpendId)
        if (s.kind == "expense") (session.spends.search(SpendFilter(kind = "refund", originalSpendId = s.id), 50) as? Api.Ok)?.let { refunds = it.value }
    }

    LaunchedEffect(id) { if (spend == null) session.spends.fetch(id) }
    LaunchedEffect(id, spend?.version) {
        when (val r = session.spends.history(id)) {
            is Api.Ok -> { history = r.value.items; historyProblem = null }
            else -> historyProblem = r.problem
        }
    }

    Scaffold(
        containerColor = t.background,
        topBar = {
            TopAppBar(
                title = {}, colors = TopAppBarDefaults.topAppBarColors(containerColor = t.background),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (spend != null && spend.deletedAt != null) TextButton(onClick = { scope.launch { session.spends.bulkRestore(listOf(spend)) } }) { Text("Restore") }
                    else if (spend != null) TextButton(onClick = { session.composer.target = EditorTarget.Edit(spend) }) { Text("Edit") }
                },
            )
        },
    ) { padding ->
        if (spend == null) { Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return@Scaffold }
        val merchants by session.directory.merchants.collectAsState()
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp)) {
                    AnimatedAmount(Money.format(spend.amountMinor, spend.currency), HeroStyle, if (spend.kind == "refund") t.live else t.text)
                    Text(merchants[spend.merchantId]?.name ?: spend.kind.replaceFirstChar { it.uppercase() }, color = t.muted)
                }
            }
            item { SectionLabel("Details") }
            item { DetailRow("Date", DayGroups.title(spend.occurredOn)) }
            item { DetailRow("Type", spend.kind.replaceFirstChar { it.uppercase() }) }
            spend.description?.takeIf { it.isNotEmpty() }?.let { d -> item { DetailRow("Note", d) } }
            spend.accountRef?.takeIf { it.isNotEmpty() }?.let { a -> item { DetailRow("Account", a) } }
            originalSpend?.let { o -> item { DetailRow("Refund of", originalLabel(o, session.directory)) } }
            spend.tagIds.orEmpty().mapNotNull { tagsById[it]?.name }.takeIf { it.isNotEmpty() }?.let { names -> item { DetailRow("Tags", names.joinToString(", ")) } }
            items(spend.allocations.orEmpty()) { a -> DetailRow(categories[a.categoryId]?.name ?: "Uncategorized", Money.format(a.amountMinor, spend.currency)) }
            if (refunds.isNotEmpty()) {
                item { SectionLabel("Refunds") }
                items(refunds, key = { it.id }) { r -> DetailRow(DayGroups.title(r.occurredOn), Money.format(r.amountMinor, r.currency)) }
                item { DetailRow("Net after refunds", Money.format(spend.amountMinor - refunds.sumOf { it.amountMinor }, spend.currency)) }
            }
            if (spend.deletedAt != null) item { DetailRow("Status", "Deleted") }
            item { SectionLabel("Receipts & documents") }
            items(documents, key = { "doc-" + it.id }) { e ->
                Row(Modifier.fillMaxWidth().background(t.surface).heightIn(min = 56.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) { Text(e.title); Text(e.sourceRef, color = t.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                    if (spend.deletedAt == null) TextButton(onClick = { scope.launch { (session.spends.link(spend, e.id, false) as? Api.Ok) ?: run { linkProblem = "Couldn't detach." } } }) { Text("Detach") }
                }
            }
            if (spend.deletedAt == null) item { TextButton(onClick = { attaching = true }, modifier = Modifier.testTag("attach-document").padding(horizontal = 8.dp)) { Text("Attach a receipt or document") } }
            linkProblem?.let { p -> item { Text(p, color = t.critical, modifier = Modifier.padding(16.dp)) } }
            item { SectionLabel("History") }
            historyProblem?.let { p -> item { Text(p, color = t.muted, modifier = Modifier.padding(16.dp)) } }
            items(history, key = { it.id }) { c ->
                DetailRow(c.operation.replace('_', ' ').replaceFirstChar { it.uppercase() }, c.occurredAt.take(16).replace('T', ' '), tabularValue = true)
            }
        }
    }

    if (attaching && spend != null) {
        EvidencePicker(session, spend.evidenceIds.orEmpty().toSet(), onPick = { e ->
            scope.launch { val r = session.spends.link(spend, e.id, true); linkProblem = if (r is Api.Ok) null else "Couldn't attach." }
        }, onDismiss = { attaching = false })
    }
}

@Composable
private fun DetailRow(label: String, value: String, tabularValue: Boolean = false) {
    val t = Treasure.tok
    Row(Modifier.fillMaxWidth().background(t.surface).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = t.muted, modifier = Modifier.weight(1f))
        Text(value, textAlign = TextAlign.End, style = if (tabularValue) Tabular else LocalTextStyle.current)
    }
}
