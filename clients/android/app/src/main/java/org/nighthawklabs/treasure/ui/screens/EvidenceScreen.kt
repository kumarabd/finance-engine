package org.nighthawklabs.treasure.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nighthawklabs.treasure.Session
import org.nighthawklabs.treasure.data.Evidence
import org.nighthawklabs.treasure.data.EvidenceFields
import org.nighthawklabs.treasure.data.EvidenceStore
import org.nighthawklabs.treasure.ui.components.EmptyView
import org.nighthawklabs.treasure.ui.theme.Treasure

/** More → Receipts & documents: the references the engine holds. Files stay wherever they already live. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvidenceScreen(session: Session, onBack: () -> Unit) {
    val t = Treasure.tok
    val scope = rememberCoroutineScope()
    val store = remember { EvidenceStore(session.engine) { session.spends.reload() } }
    val state by store.state.collectAsState()
    var form by remember { mutableStateOf<Evidence?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { store.load() }

    Scaffold(
        containerColor = t.background,
        topBar = {
            TopAppBar(
                title = { Text("Receipts & documents") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = t.background),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add document") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                        listOf(false to "Active", true to "Deleted").forEachIndexed { i, (deleted, label) ->
                            SegmentedButton(selected = state.showDeleted == deleted, onClick = { scope.launch { store.load(deleted) } }, shape = SegmentedButtonDefaults.itemShape(i, 2), label = { Text(label) })
                        }
                    }
                }
                state.problem?.let { p -> item { Text(p, color = t.critical, modifier = Modifier.padding(16.dp)) } }
                items(state.items, key = { it.id }) { e ->
                    SwipeToDelete(onDelete = { scope.launch { if (state.showDeleted) store.restore(e) else store.delete(e) } }, modifier = Modifier.animateItem(), restore = state.showDeleted) {
                        Column(Modifier.testTag("evidence-row").fillMaxWidth().background(t.surface).clickable(enabled = !state.showDeleted) { form = e }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.sourceRef, color = t.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else if (state.items.isEmpty() && state.problem == null) EmptyView(if (state.showDeleted) "Nothing deleted" else "No documents yet. Attach one from a spend, or add one here.")
        }
    }

    if (adding) EvidenceForm(null, onDismiss = { adding = false }) { f -> store.create(f) != null }
    form?.let { e -> EvidenceForm(e, onDismiss = { form = null }) { f -> store.update(e, f) } }
    state.needsDetach?.let { e ->
        AlertDialog(
            onDismissRequest = store::dismissDetach, title = { Text("Used by spends") },
            text = { Text("“${e.title}” is attached to one or more spends. Deleting it unlinks it from them.") },
            confirmButton = { TextButton(modifier = Modifier.testTag("confirm-unlink"), onClick = { scope.launch { store.delete(e, detach = true) } }) { Text("Unlink and delete") } },
            dismissButton = { TextButton(onClick = store::dismissDetach) { Text("Cancel") } },
        )
    }
}

/** Add or edit a document reference. [save] returns true when it worked, which closes the dialog. */
@Composable
fun EvidenceForm(existing: Evidence?, onDismiss: () -> Unit, save: suspend (EvidenceFields) -> Boolean) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var ref by remember { mutableStateOf(existing?.sourceRef ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(if (existing == null) "Add document" else "Edit document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("Title (e.g. Costco receipt)") }, modifier = Modifier.testTag("evidence-title"))
                OutlinedTextField(ref, { ref = it }, singleLine = true, label = { Text("Where it lives (link or file name)") }, modifier = Modifier.testTag("evidence-ref"))
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") })
                if (failed) Text("Couldn't save. Check the details and try again.", color = Treasure.tok.critical)
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && ref.isNotBlank() && !saving, modifier = Modifier.testTag("evidence-save"), onClick = {
                saving = true; failed = false
                scope.launch {
                    val ok = save(EvidenceFields(title.trim(), ref.trim(), existing?.mediaType, existing?.checksum, notes.trim().ifEmpty { null }))
                    saving = false
                    if (ok) onDismiss() else failed = true
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick an existing document for a spend, or add a new one. Already-attached documents are left out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvidencePicker(session: Session, attached: Set<String>, onPick: (Evidence) -> Unit, onDismiss: () -> Unit) {
    val t = Treasure.tok
    val store = remember { EvidenceStore(session.engine) }
    val state by store.state.collectAsState()
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { store.load() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = t.background) {
        Text("Attach document", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        TextButton(onClick = { adding = true }, modifier = Modifier.testTag("new-document").padding(horizontal = 8.dp)) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("New document") }
        state.problem?.let { Text(it, color = t.muted, modifier = Modifier.padding(20.dp)) }
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(state.items.filter { it.id !in attached }, key = { it.id }) { e ->
                Column(Modifier.testTag("pick-doc").fillMaxWidth().clickable { onPick(e); onDismiss() }.heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.sourceRef, color = t.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (adding) EvidenceForm(null, onDismiss = { adding = false }) { f -> store.create(f)?.let { onPick(it); adding = false; onDismiss(); true } ?: false }
}
