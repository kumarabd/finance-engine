package org.nighthawklabs.treasure.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.nighthawklabs.treasure.data.Dimension
import org.nighthawklabs.treasure.data.Exporter
import org.nighthawklabs.treasure.data.FilterChip
import org.nighthawklabs.treasure.data.SpendFilter
import org.nighthawklabs.treasure.data.SpendPatch
import org.nighthawklabs.treasure.data.SpendsStore
import org.nighthawklabs.treasure.data.chips
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.problem
import org.nighthawklabs.treasure.ui.components.MultiPickerSheet
import org.nighthawklabs.treasure.ui.components.SinglePickerSheet
import org.nighthawklabs.treasure.ui.components.rememberHaptics
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

private enum class BulkAction { Category, AddTags, RemoveTags, Merchant }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SpendsScreen(session: Session, openSpend: (String) -> Unit) {
    val store = session.spends
    val directory = session.directory
    val state by store.state.collectAsState()
    val pending by store.outbox.items.collectAsState()
    val categories by directory.categories.collectAsState()
    val merchants by directory.merchants.collectAsState()
    val tags by directory.tags.collectAsState()
    val t = Treasure.tok
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    var refreshing by remember { mutableStateOf(false) }

    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var showFilter by remember { mutableStateOf(false) }
    var bulk by remember { mutableStateOf<BulkAction?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var lastLoadedSearch by remember { mutableStateOf("") }

    val filter = state.filter
    val inTrash = filter.state == "deleted"
    val selectedSpends = state.spends.filter { it.id in selection }
    val chips = remember(filter, categories, merchants, tags) { filter.chips({ categories[it]?.name }, { merchants[it]?.name }, { tags[it]?.name }) }

    // Typing is debounced; any other change (a chip, the filter sheet, Trash) goes straight through.
    LaunchedEffect(filter) {
        if (filter.search != lastLoadedSearch && filter.search.isNotEmpty()) delay(300)
        lastLoadedSearch = filter.search
        store.reload()
    }
    LaunchedEffect(filter.state) { selection = emptySet(); selecting = false }
    // "N spends deleted. Undo" for a few seconds after a delete; Undo calls spends_bulk_restore.
    LaunchedEffect(state.undo?.id) {
        val u = state.undo ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(if (u.spends.size == 1) "Spend deleted" else "${u.spends.size} spends deleted", actionLabel = "Undo", duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) store.undoDelete() else store.dismissUndo(u.id)
    }

    fun run(work: suspend () -> SpendsStore.BulkOutcome) {
        scope.launch {
            val outcome = work()
            if (outcome.ok) { selection = emptySet(); selecting = false; haptics.success() }
            else snackbar.showSnackbar(outcome.failure ?: "Couldn't finish")
        }
    }
    fun exitSelection() { selecting = false; selection = emptySet() }
    BackHandler(enabled = selecting) { exitSelection() }

    Scaffold(
        containerColor = t.background,
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) (if (selection.isEmpty()) "Select spends" else "${selection.size} selected") else if (inTrash) "Trash" else "Spends") },
                navigationIcon = { if (selecting) IconButton(onClick = ::exitSelection) { Icon(Icons.Filled.Close, "Cancel selection") } },
                actions = {
                    if (selecting) {
                        TextButton(onClick = { selection = if (selection.size == state.spends.size) emptySet() else state.spends.map { it.id }.toSet() }) { Text(if (selection.size == state.spends.size) "Select none" else "Select all") }
                    } else {
                        IconButton(onClick = { showFilter = true }, modifier = Modifier.testTag("filter-button")) {
                            BadgedBox(badge = { if (filter.activeCount > 0) Badge { Text("${filter.activeCount}") } }) { Icon(Icons.Filled.FilterList, if (filter.activeCount > 0) "Filter, ${filter.activeCount} active" else "Filter") }
                        }
                        if (!inTrash) AddAction(session)
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; selecting = true })
                                DropdownMenuItem(text = { Text(if (inTrash) "Back to spends" else "Trash") }, onClick = { menu = false; store.setFilter(filter.copy(state = if (inTrash) "active" else "deleted")) })
                                DropdownMenuItem(text = { Text(if (exporting) "Exporting…" else "Export these results") }, enabled = !exporting, onClick = {
                                    menu = false
                                    scope.launch {
                                        exporting = true
                                        when (val r = Exporter.csv(session.engine, filter)) { is Api.Ok -> shareCsv(context, r.value); else -> snackbar.showSnackbar(r.problem ?: "Couldn't export") }
                                        exporting = false
                                    }
                                })
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = t.background),
            )
        },
        bottomBar = {
            if (selecting) BulkBar(
                enabled = selection.isNotEmpty(), inTrash = inTrash,
                onCategory = { bulk = BulkAction.Category }, onAddTags = { bulk = BulkAction.AddTags }, onRemoveTags = { bulk = BulkAction.RemoveTags },
                onMerchant = { bulk = BulkAction.Merchant }, onDelete = { confirmDelete = true }, onRestore = { run { store.bulkRestore(selectedSpends) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it, containerColor = t.raised, contentColor = t.text, actionColor = t.accent) } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(filter.search, store::setSearch)
            if (chips.isNotEmpty() && !selecting) FilterChipsRow(chips, onRemove = { c -> store.setFilter(c.clear(filter)) }, onClearAll = { store.setFilter(SpendFilter(state = filter.state)) })
            Box(Modifier.weight(1f)) {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { scope.launch { refreshing = true; store.flushOutbox(); store.reload(); directory.refresh(); refreshing = false } },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val groups = remember(state.spends) { DayGroups.make(state.spends) }
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (state.problem != null && state.spends.isNotEmpty()) item { Text(state.problem!!, color = t.muted, modifier = Modifier.padding(16.dp)) }
                        if (pending.isNotEmpty() && !inTrash) {
                            item(key = "pending-h") { SectionLabel("Waiting to sync") }
                            items(pending, key = { "p-" + it.id }) { PendingRow(it, onDiscard = { store.outbox.discard(it.id) }, onRetry = { store.outbox.retry(it.id); scope.launch { store.flushOutbox() } }) }
                        }
                        groups.forEach { group ->
                            stickyHeader(key = "h-" + group.day) {
                                SectionLabel(DayGroups.title(group.day), group.net?.takeIf { it.first != 0L }?.let { Money.format(it.first, it.second) })
                            }
                            items(group.spends, key = { it.id }) { s ->
                                LaunchedEffect(s.id) { store.loadMore(s) }
                                val on = s.id in selection
                                val rowModifier = Modifier.testTag("spend-row").background(if (on) MaterialTheme.colorScheme.secondaryContainer else t.surface)
                                    .combinedClickable(
                                        onClick = { if (selecting) selection = if (on) selection - s.id else selection + s.id else openSpend(s.id) },
                                        onLongClick = { if (!selecting) { selecting = true; selection = setOf(s.id); haptics.tick() } },
                                    ).semantics { if (selecting) this.selected = on }
                                val row: @Composable () -> Unit = {
                                    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
                                        if (selecting) Checkbox(checked = on, onCheckedChange = { selection = if (on) selection - s.id else selection + s.id }, modifier = Modifier.padding(start = 4.dp))
                                        SpendRow(session, s, Modifier.weight(1f))
                                    }
                                }
                                if (selecting) Box(Modifier.animateItem()) { row() }
                                else SwipeToDelete(onDelete = { scope.launch { if (inTrash) store.bulkRestore(listOf(s)) else store.delete(s) } }, modifier = Modifier.animateItem(), restore = inTrash) { row() }
                            }
                        }
                        if (state.hasMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                    }
                    if (state.spends.isEmpty()) {
                        when {
                            state.loading -> SkeletonList(Modifier.padding(top = 8.dp))
                            state.problem != null -> ProblemView(state.problem!!) { scope.launch { store.reload() } }
                            pending.isEmpty() || inTrash -> EmptyView(if (inTrash) "Trash is empty" else if (filter.activeCount > 0) "No matches" else "No spends yet")
                        }
                    }
                }
            }
        }
    }

    if (showFilter) SpendFilterSheet(session, filter, state.spends, onApply = { store.setFilter(it); showFilter = false }, onDismiss = { showFilter = false })

    when (bulk) {
        BulkAction.Category -> SinglePickerSheet("Categorize ${selection.size}", directory.categoriesByUse(state.spends), { id -> bulk = null; run { store.bulkUpdate(selectedSpends, SpendPatch(categoryId = id)) } }, { bulk = null }, noneLabel = "No category")
        BulkAction.Merchant -> SinglePickerSheet("Merchant for ${selection.size}", merchants.values.sortedBy { it.name.lowercase() }, { id -> bulk = null; run { store.bulkUpdate(selectedSpends, SpendPatch(merchantId = id)) } }, { bulk = null },
            noneLabel = "No merchant", create = { name -> (directory.resolveMerchant(name) as? Api.Ok)?.value?.let { directory.merchants.value[it] } })
        BulkAction.AddTags -> TagBulkSheet("Add tags", directory.tagsByUse(state.spends), true, directory, onApply = { ids -> bulk = null; run { store.bulkUpdate(selectedSpends, SpendPatch(addTagIds = ids)) } }, onDismiss = { bulk = null })
        BulkAction.RemoveTags -> {
            val present = selectedSpends.flatMap { it.tagIds.orEmpty() }.toSet()
            TagBulkSheet("Remove tags", directory.tagsByUse(state.spends).filter { it.id in present }, false, directory, onApply = { ids -> bulk = null; run { store.bulkUpdate(selectedSpends, SpendPatch(removeTagIds = ids)) } }, onDismiss = { bulk = null })
        }
        null -> {}
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${selection.size} spend${if (selection.size == 1) "" else "s"}?") },
        text = { Text("You can undo right after, or restore them later from Trash.") },
        confirmButton = { TextButton(modifier = Modifier.testTag("confirm-delete"), onClick = { confirmDelete = false; run { store.bulkDelete(selectedSpends) } }) { Text("Delete", color = t.critical) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

/** Tags are ticked in a sheet that stays open, so the change is applied when it closes. */
@Composable
private fun TagBulkSheet(title: String, items: List<Dimension>, canCreate: Boolean, directory: org.nighthawklabs.treasure.data.Directory, onApply: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf(setOf<String>()) }
    MultiPickerSheet(
        title, items, picked, { picked = it }, { if (picked.isNotEmpty()) onApply(picked.sorted()) else onDismiss() },
        create = if (canCreate) { name -> (directory.resolveTag(name) as? Api.Ok)?.value } else null,
        emptyHint = if (canCreate) "No tags yet. Type a name to create one." else "None of these spends have tags.",
    )
}

@Composable
private fun BulkBar(enabled: Boolean, inTrash: Boolean, onCategory: () -> Unit, onAddTags: () -> Unit, onRemoveTags: () -> Unit, onMerchant: () -> Unit, onDelete: () -> Unit, onRestore: () -> Unit) {
    val t = Treasure.tok
    var tagMenu by remember { mutableStateOf(false) }
    Surface(color = t.surface, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (inTrash) BarButton("Restore", Icons.Filled.Restore, enabled, onClick = onRestore)
            else {
                BarButton("Category", Icons.Filled.Category, enabled, onClick = onCategory)
                Box {
                    BarButton("Tags", Icons.Filled.Sell, enabled) { tagMenu = true }
                    DropdownMenu(tagMenu, { tagMenu = false }) {
                        DropdownMenuItem(text = { Text("Add tags") }, onClick = { tagMenu = false; onAddTags() })
                        DropdownMenuItem(text = { Text("Remove tags") }, onClick = { tagMenu = false; onRemoveTags() })
                    }
                }
                BarButton("Merchant", Icons.Filled.Storefront, enabled, onClick = onMerchant)
                BarButton("Delete", Icons.Filled.Delete, enabled, destructive = true, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun BarButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, destructive: Boolean = false, onClick: () -> Unit) {
    val t = Treasure.tok
    val color = if (!enabled) t.muted else if (destructive) t.critical else t.accent
    Column(Modifier.testTag("bulk-${label.lowercase()}").widthIn(min = 72.dp).heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = color); Text(label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

/** Active conditions as removable chips. */
@Composable
private fun FilterChipsRow(chips: List<FilterChip>, onRemove: (FilterChip) -> Unit, onClearAll: () -> Unit) {
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chips, key = { it.id }) { c ->
            InputChip(selected = false, onClick = { onRemove(c) }, label = { Text(c.label, maxLines = 1) },
                trailingIcon = { Icon(Icons.Filled.Close, "Remove filter ${c.label}", Modifier.size(18.dp)) })
        }
        if (chips.size > 1) item { TextButton(onClick = onClearAll) { Text("Clear all") } }
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
fun SwipeToDelete(onDelete: () -> Unit, modifier: Modifier = Modifier, restore: Boolean = false, content: @Composable () -> Unit) {
    val t = Treasure.tok
    val box = rememberSwipeToDismissBoxState(confirmValueChange = { if (it == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false })
    SwipeToDismissBox(
        state = box, modifier = modifier, enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(if (restore) t.accent else t.critical).padding(horizontal = 20.dp), Alignment.CenterEnd) {
                Icon(if (restore) Icons.Filled.Restore else Icons.Filled.Delete, contentDescription = if (restore) "Restore" else "Delete", tint = t.onAccent)
            }
        },
    ) { content() }
}
