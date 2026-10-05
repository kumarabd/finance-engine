package org.nighthawklabs.treasure.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nighthawklabs.treasure.Session
import org.nighthawklabs.treasure.data.DayGroups
import org.nighthawklabs.treasure.data.Money
import org.nighthawklabs.treasure.data.Spend
import org.nighthawklabs.treasure.ui.components.EmptyView
import org.nighthawklabs.treasure.ui.components.ProblemView
import org.nighthawklabs.treasure.ui.components.SkeletonList
import org.nighthawklabs.treasure.ui.theme.Treasure

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SpendsScreen(session: Session, openSpend: (String) -> Unit) {
    val store = session.spends
    val state by store.state.collectAsState()
    val pending by store.outbox.items.collectAsState()
    val t = Treasure.tok
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var refreshing by remember { mutableStateOf(false) }

    // First run goes straight through; typing is debounced.
    LaunchedEffect(state.search) {
        if (state.search.isNotEmpty()) delay(300)
        store.reload()
    }
    // "Spend deleted. Undo" for a few seconds after a delete; Undo calls spends_restore.
    LaunchedEffect(state.undo?.id) {
        val u = state.undo ?: return@LaunchedEffect
        val r = snackbar.showSnackbar("Spend deleted", actionLabel = "Undo", duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) store.undoDelete() else store.dismissUndo(u.id)
    }

    Scaffold(
        containerColor = t.background,
        topBar = { TopAppBar(title = { Text("Spends") }, actions = { AddAction(session) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = t.background)) },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it, containerColor = t.raised, contentColor = t.text, actionColor = t.accent) } },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; store.flushOutbox(); store.reload(); session.directory.refresh(); refreshing = false } },
            modifier = Modifier.padding(padding),
        ) {
            val groups = remember(state.spends) { DayGroups.make(state.spends) }
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "search") { SearchField(state.search, store::setSearch) }
                if (state.problem != null && state.spends.isNotEmpty()) item { Text(state.problem!!, color = t.muted, modifier = Modifier.padding(16.dp)) }
                if (pending.isNotEmpty()) {
                    item(key = "pending-h") { SectionLabel("Waiting to sync") }
                    items(pending, key = { "p-" + it.id }) { PendingRow(it, onDiscard = { store.outbox.discard(it.id) }, onRetry = { store.outbox.retry(it.id); scope.launch { store.flushOutbox() } }) }
                }
                groups.forEach { group ->
                    stickyHeader(key = "h-" + group.day) {
                        SectionLabel(DayGroups.title(group.day), group.net?.takeIf { it.first != 0L }?.let { Money.format(it.first, it.second) })
                    }
                    items(group.spends, key = { it.id }) { s ->
                        LaunchedEffect(s.id) { store.loadMore(s) }
                        SwipeToDelete(onDelete = { scope.launch { store.delete(s) } }, modifier = Modifier.animateItem()) {
                            SpendRow(session, s, Modifier.background(t.surface).clickable { openSpend(s.id) })
                        }
                    }
                }
                if (state.hasMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
            }
            if (state.spends.isEmpty()) {
                when {
                    state.loading -> SkeletonList(Modifier.padding(top = 72.dp))
                    state.problem != null -> ProblemView(state.problem!!) { scope.launch { store.reload() } }
                    pending.isEmpty() -> EmptyView(if (state.search.isEmpty()) "No spends yet" else "No matches")
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val t = Treasure.tok
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text("Merchant, note or account") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = t.raised, unfocusedContainerColor = t.raised, focusedBorderColor = t.accent, unfocusedBorderColor = t.hairline),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Swipe left to delete. The row snaps back until the delete is confirmed by the caller removing it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDelete(onDelete: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t = Treasure.tok
    val box = rememberSwipeToDismissBoxState(confirmValueChange = { if (it == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false })
    SwipeToDismissBox(
        state = box, modifier = modifier, enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(t.critical).padding(horizontal = 20.dp), Alignment.CenterEnd) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = t.onAccent)
            }
        },
    ) { content() }
}
