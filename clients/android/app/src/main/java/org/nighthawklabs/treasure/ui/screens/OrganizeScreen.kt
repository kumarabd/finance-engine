package org.nighthawklabs.treasure.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nighthawklabs.treasure.Session
import org.nighthawklabs.treasure.data.Dimension
import org.nighthawklabs.treasure.data.DimensionKind
import org.nighthawklabs.treasure.data.OrganizeStore
import org.nighthawklabs.treasure.ui.components.EmptyView
import org.nighthawklabs.treasure.ui.theme.Treasure

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun OrganizeScreen(session: Session, kind: DimensionKind, onBack: () -> Unit) {
    val t = Treasure.tok
    val scope = rememberCoroutineScope()
    val store = remember(kind) { OrganizeStore(kind, session.engine) { session.directory.refresh(); session.spends.reload() } }
    val state by store.state.collectAsState()
    var naming by remember { mutableStateOf<NameTarget?>(null) }
    var merging by remember { mutableStateOf<Dimension?>(null) }
    LaunchedEffect(kind) { store.load() }

    Scaffold(
        containerColor = t.background,
        topBar = {
            TopAppBar(
                title = { Text(kind.title) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = t.background),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { naming = NameTarget(null) }) { Icon(Icons.Filled.Add, "Add ${kind.singular}") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                state.problem?.let { p -> item { Text(p, color = t.critical, modifier = Modifier.padding(16.dp)) } }
                items(state.items, key = { it.id }) { d ->
                    SwipeToDelete(onDelete = { scope.launch { store.delete(d) } }, modifier = Modifier.animateItem()) {
                        var menu by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().background(t.surface).heightIn(min = 56.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                d.aliases?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(", "), color = t.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options for ${d.name}") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; naming = NameTarget(d) })
                                    DropdownMenuItem(text = { Text("Merge into…") }, onClick = { menu = false; merging = d })
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; scope.launch { store.delete(d) } })
                                }
                            }
                        }
                    }
                }
            }
            if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else if (state.items.isEmpty() && state.problem == null) EmptyView("No ${kind.plural} yet")
        }
    }

    naming?.let { n ->
        var text by remember(n) { mutableStateOf(n.existing?.name ?: "") }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(if (n.existing == null) "New ${kind.singular}" else "Rename") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    val name = text.trim(); naming = null
                    scope.launch { n.existing?.let { store.rename(it, name) } ?: store.create(name) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } },
        )
    }
    merging?.let { src ->
        TargetPicker("Merge “${src.name}” into", state.items.filter { it.id != src.id }, onDismiss = { merging = null }) { target -> merging = null; scope.launch { store.merge(src, target) } }
    }
    state.needsReplacement?.let { d ->
        TargetPicker("Spends use “${d.name}”. Move them to", state.items.filter { it.id != d.id }, onDismiss = store::dismissReplacement) { target ->
            store.dismissReplacement(); scope.launch { store.delete(d, target) }
        }
    }
}

private data class NameTarget(val existing: Dimension?)

/** Choose another record: used to merge, or to take over spends when deleting something still in use. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetPicker(title: String, candidates: List<Dimension>, onDismiss: () -> Unit, onPick: (Dimension) -> Unit) {
    val t = Treasure.tok
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = t.background) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (candidates.isEmpty()) Text("Nothing to move them to", color = t.muted, modifier = Modifier.padding(20.dp))
        LazyColumn { items(candidates, key = { it.id }) { c -> TextButton(onClick = { onPick(c) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(c.name, modifier = Modifier.fillMaxWidth()) } } }
    }
}
