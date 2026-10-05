package org.nighthawklabs.treasure.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nighthawklabs.treasure.data.Dimension
import org.nighthawklabs.treasure.ui.theme.Treasure

/** Choose several records (tags). Search narrows the list; typing a new name offers to create it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiPickerSheet(
    title: String,
    items: List<Dimension>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    /** Creates a record from the typed name and returns it (null if it couldn't be created). Omit to disallow creating. */
    create: (suspend (String) -> Dimension?)? = null,
    emptyHint: String = "Nothing here yet.",
) {
    val t = Treasure.tok
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val q = query.trim()
    val shown = if (q.isEmpty()) items else items.filter { it.name.contains(q, ignoreCase = true) }
    val canCreate = create != null && q.isNotEmpty() && items.none { it.name.equals(q, ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = items.size > 3), containerColor = t.background) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            SearchBox(query, { query = it }, if (create != null) "Search or create" else "Search")
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                if (canCreate) item("create") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = !creating) {
                        creating = true
                        scope.launch { create?.invoke(q)?.let { onChange(selected + it.id); query = "" }; creating = false }
                    }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, null, tint = t.accent); Spacer(Modifier.width(12.dp)); Text("Create “$q”")
                    }
                }
                if (items.isEmpty() && !canCreate) item("empty") { Text(emptyHint, color = t.muted, modifier = Modifier.padding(20.dp)) }
                items(shown, key = { it.id }) { d ->
                    val on = d.id in selected
                    Row(Modifier.testTag("pick-${d.name}").fillMaxWidth().heightIn(min = 52.dp).background(t.surface).semantics(mergeDescendants = true) { this.selected = on }
                        .clickable(role = Role.Checkbox) { onChange(if (on) selected - d.id else selected + d.id) }.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(d.name, modifier = Modifier.weight(1f)); if (on) Icon(Icons.Filled.Check, "Selected", tint = t.accent)
                    }
                }
            }
        }
    }
}

/** Choose one record (a category or a merchant), or "nothing" when [noneLabel] is given (reported as an empty string). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SinglePickerSheet(
    title: String,
    items: List<Dimension>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    noneLabel: String? = null,
    selected: String? = null,
    create: (suspend (String) -> Dimension?)? = null,
) {
    val t = Treasure.tok
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val q = query.trim()
    val shown = if (q.isEmpty()) items else items.filter { d -> d.name.contains(q, ignoreCase = true) || d.aliases.orEmpty().any { it.contains(q, ignoreCase = true) } }
    val canCreate = create != null && q.isNotEmpty() && items.none { it.name.equals(q, ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = items.size > 3), containerColor = t.background) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            SearchBox(query, { query = it }, "Search")
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                if (noneLabel != null && q.isEmpty()) item("none") { PickRow(noneLabel, selected == "") { onPick("") } }
                if (canCreate) item("create") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = !creating) {
                        creating = true
                        scope.launch { create?.invoke(q)?.let { onPick(it.id) }; creating = false }
                    }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, null, tint = t.accent); Spacer(Modifier.width(12.dp)); Text("Create “$q”")
                    }
                }
                items(shown, key = { it.id }) { d -> PickRow(d.name, selected == d.id) { onPick(d.id) } }
            }
        }
    }
}

@Composable
private fun PickRow(name: String, on: Boolean, onClick: () -> Unit) {
    val t = Treasure.tok
    Row(Modifier.testTag("pick-$name").fillMaxWidth().heightIn(min = 52.dp).background(t.surface).semantics(mergeDescendants = true) { this.selected = on }
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.weight(1f)); if (on) Icon(Icons.Filled.Check, "Selected", tint = t.accent)
    }
}

@Composable
fun SearchBox(value: String, onChange: (String) -> Unit, placeholder: String) {
    val t = Treasure.tok
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, placeholder = { Text(placeholder) }, leadingIcon = { Icon(Icons.Filled.Search, null) },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = t.raised, unfocusedContainerColor = t.raised, focusedBorderColor = t.accent, unfocusedBorderColor = t.hairline),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
