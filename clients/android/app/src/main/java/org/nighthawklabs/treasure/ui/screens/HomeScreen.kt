package org.nighthawklabs.treasure.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nighthawklabs.treasure.Session
import org.nighthawklabs.treasure.data.Delta
import org.nighthawklabs.treasure.data.Money
import org.nighthawklabs.treasure.data.Period
import org.nighthawklabs.treasure.data.Snapshot
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.ui.components.AnimatedAmount
import org.nighthawklabs.treasure.ui.components.EmptyView
import org.nighthawklabs.treasure.ui.components.ProblemView
import org.nighthawklabs.treasure.ui.theme.HeroStyle
import org.nighthawklabs.treasure.ui.theme.Treasure

/** This month at a glance, then the ten newest spends. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(session: Session, openSpend: (String) -> Unit) {
    val store = session.spends
    val state by store.state.collectAsState()
    val pending by store.outbox.items.collectAsState()
    val t = Treasure.tok
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<Snapshot?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() { (session.insights.snapshot(Period.Month) as? Api.Ok)?.let { snapshot = it.value } }
    LaunchedEffect(state.revision) { load() }

    Scaffold(
        containerColor = t.background,
        topBar = { TopAppBar(title = { Text("Treasure") }, actions = { AddAction(session) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = t.background)) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; store.flushOutbox(); store.reload(); load(); refreshing = false } },
            modifier = Modifier.padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                snapshot?.let { snap -> snap.currencies.firstOrNull()?.let { cur -> item(key = "hero") { Hero(snap, cur) } } }
                if (state.problem != null && state.spends.isNotEmpty()) item { Text(state.problem!!, color = t.muted, modifier = Modifier.padding(16.dp)) }
                if (pending.isNotEmpty()) {
                    item(key = "pending-h") { SectionLabel("Waiting to sync") }
                    items(pending, key = { "p-" + it.id }) { PendingRow(it, onDiscard = { store.outbox.discard(it.id) }, onRetry = { store.outbox.retry(it.id); scope.launch { store.flushOutbox() } }) }
                }
                if (state.spends.isNotEmpty()) item(key = "recent-h") { SectionLabel("Recent") }
                items(state.spends.take(10), key = { it.id }) { s ->
                    SpendRow(session, s, Modifier.animateItem().background(t.surface).clickable { openSpend(s.id) })
                }
            }
            if (state.spends.isEmpty() && !state.loading) {
                if (state.problem != null) ProblemView(state.problem!!) { scope.launch { store.reload() } }
                else if (pending.isEmpty()) EmptyView("No spends yet")
            }
        }
    }
}

@Composable
private fun Hero(snap: Snapshot, currency: String) {
    val t = Treasure.tok
    val (current, previous) = snap.total(currency)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("This month", color = t.muted, style = MaterialTheme.typography.bodyMedium)
        AnimatedAmount(Money.format(current, currency), HeroStyle, t.text)
        Delta.text(current, previous, snap.period.comparedWith)?.let { Text(it, color = t.muted, style = MaterialTheme.typography.bodyMedium) }
    }
}
