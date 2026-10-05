package org.nighthawklabs.treasure.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.nighthawklabs.treasure.Session
import org.nighthawklabs.treasure.data.*
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.problem
import org.nighthawklabs.treasure.ui.components.SearchBox
import org.nighthawklabs.treasure.ui.components.SinglePickerSheet
import org.nighthawklabs.treasure.ui.theme.Treasure

/** Divide a purchase across categories. "Done" stays disabled until the shares add up to the amount. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitSheet(session: Session, rows: List<SplitRow>, total: Long, currency: String, onChange: (List<SplitRow>) -> Unit, onDone: () -> Unit) {
    val t = Treasure.tok
    val spends by session.spends.state.collectAsState()
    val cats = remember(spends.spends, session.directory.categories.collectAsState().value) { session.directory.categoriesByUse(spends.spends) }
    val problem = Splits.problem(total, rows, currency)
    val blocked = rows.size > 1 && problem != null
    var picking by remember { mutableStateOf<String?>(null) } // id of the row choosing a category
    ModalBottomSheet(
        onDismissRequest = { if (!blocked) onDone() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { !blocked || it != SheetValue.Hidden }),
        containerColor = t.background,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Split", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDone, enabled = !blocked, modifier = Modifier.testTag("split-done")) { Text("Done") }
            }
            rows.forEach { row ->
                SplitLine(row, currency, session.directory.category(row.categoryId), onPick = { picking = row.id },
                    onAmount = { m -> onChange(rows.map { if (it.id == row.id) it.copy(amountMinor = m) else it }) },
                    onRemove = { onChange(rows.filter { it.id != row.id }) })
            }
            TextButton(onClick = { onChange(Splits.adding(rows, total)) }, modifier = Modifier.testTag("add-split")) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Add a split")
            }
            Text("Removing down to one line makes it a normal spend.", color = t.muted, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = t.hairline)
            Row { Text("Total", Modifier.weight(1f), color = t.muted); Text(Money.format(total, currency)) }
            if (problem != null) Text(problem, color = t.warn, modifier = Modifier.testTag("split-problem"))
            else Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Check, null, tint = t.live); Spacer(Modifier.width(6.dp)); Text("Adds up", color = t.live) }
        }
    }
    picking?.let { id ->
        SinglePickerSheet("Category", cats, onPick = { cid -> onChange(rows.map { if (it.id == id) it.copy(categoryId = cid.ifEmpty { null }) else it }); picking = null },
            onDismiss = { picking = null }, noneLabel = "No category", selected = rows.firstOrNull { it.id == id }?.categoryId ?: "")
    }
}

@Composable
private fun SplitLine(row: SplitRow, currency: String, name: String?, onPick: () -> Unit, onAmount: (Long) -> Unit, onRemove: () -> Unit) {
    val t = Treasure.tok
    var text by remember(row.id) { mutableStateOf(if (row.amountMinor > 0) Money.plain(row.amountMinor, currency) else "") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onPick, modifier = Modifier.weight(1f).testTag("split-category")) {
            Text(name ?: "No category", color = if (name == null) t.muted else t.text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
        }
        OutlinedTextField(text, { text = it; onAmount(Money.parseMinor(it, currency) ?: 0) }, singleLine = true, placeholder = { Text("0") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
            modifier = Modifier.width(120.dp).testTag("split-amount"))
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove split") }
    }
}

/** Choose the expense a refund belongs to. Only expenses in the refund's currency qualify, as the engine requires. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OriginalPicker(session: Session, currency: String, selected: String?, onPick: (id: String?, label: String) -> Unit, onDismiss: () -> Unit) {
    val t = Treasure.tok
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Spend>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        if (query.isNotEmpty()) kotlinx.coroutines.delay(250)
        val r = session.spends.search(SpendFilter(kind = "expense", currency = currency, search = query))
        if (r is Api.Ok) { results = r.value; problem = null } else problem = r.problem
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.background) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Text("Refund of", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            SearchBox(query, { query = it }, "Search expenses")
            problem?.let { Text(it, color = t.muted, modifier = Modifier.padding(20.dp)) }
            LazyColumn {
                item("none") {
                    Row(Modifier.testTag("original-none").fillMaxWidth().heightIn(min = 52.dp).clickable { onPick(null, ""); onDismiss() }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("No linked expense", Modifier.weight(1f)); if (selected == null) Icon(Icons.Filled.Check, "Selected", tint = t.accent)
                    }
                }
                items(results, key = { it.id }) { s ->
                    Row(Modifier.testTag("original-row").fillMaxWidth().heightIn(min = 52.dp).clickable { onPick(s.id, originalLabel(s, session.directory)); onDismiss() }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(session.directory.merchant(s.merchantId) ?: s.description?.takeIf { it.isNotEmpty() } ?: "Expense")
                            Text(DayGroups.title(s.occurredOn), color = t.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(Money.format(s.amountMinor, s.currency)); if (selected == s.id) Icon(Icons.Filled.Check, "Selected", tint = t.accent)
                    }
                }
            }
        }
    }
}

fun originalLabel(s: Spend, directory: Directory) =
    (directory.merchant(s.merchantId) ?: s.description?.takeIf { it.isNotEmpty() } ?: "Expense") + " · " + DayGroups.title(s.occurredOn)
