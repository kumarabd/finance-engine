package org.nighthawklabs.treasure.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.nighthawklabs.treasure.Session
import org.nighthawklabs.treasure.data.*
import org.nighthawklabs.treasure.ui.components.Chip
import org.nighthawklabs.treasure.ui.components.MultiPickerSheet
import org.nighthawklabs.treasure.ui.components.PrimaryButton
import org.nighthawklabs.treasure.ui.components.SinglePickerSheet
import org.nighthawklabs.treasure.ui.theme.Treasure
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

private enum class Picking { Category, Merchant, Tags }

/** Edits a copy of the list's filter; "Apply" hands it back, "Reset" clears every condition. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpendFilterSheet(session: Session, initial: SpendFilter, loaded: List<Spend>, onApply: (SpendFilter) -> Unit, onDismiss: () -> Unit) {
    val t = Treasure.tok
    val directory = session.directory
    val merchants by directory.merchants.collectAsState()
    var draft by remember { mutableStateOf(initial) }
    var preset by remember { mutableStateOf(DatePreset.matching(initial.from, initial.to)) }
    val digits = org.nighthawklabs.treasure.ingest.AmountParse.fractionDigits(initial.currency ?: "USD")
    fun text(v: Long?) = v?.let { java.math.BigDecimal.valueOf(it, digits).toPlainString() } ?: ""
    var minText by remember { mutableStateOf(text(initial.minAmountMinor)) }
    var maxText by remember { mutableStateOf(text(initial.maxAmountMinor)) }
    var picking by remember { mutableStateOf<Picking?>(null) }
    var pickDate by remember { mutableStateOf<String?>(null) } // "from" or "to"
    val currencies = (listOf("USD", "EUR", "GBP", "INR", "JPY", "CAD", "AUD") + loaded.map { it.currency } + listOfNotNull(draft.currency)).distinct().sorted()

    fun commit() {
        var f = draft
        val lo = minText.trim(); val hi = maxText.trim()
        if (lo.isNotEmpty() || hi.isNotEmpty() || f.sort.startsWith("amount")) {
            // Amounts only mean something in one currency: take the one picked, else the user's usual one.
            val cur = f.currency ?: java.util.Currency.getInstance(Locale.getDefault()).currencyCode
            f = f.copy(currency = cur, minAmountMinor = if (lo.isEmpty()) null else Money.parseMinor(lo, cur), maxAmountMinor = if (hi.isEmpty()) null else Money.parseMinor(hi, cur))
        } else f = f.copy(minAmountMinor = null, maxAmountMinor = null)
        onApply(f)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.background) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { draft = SpendFilter(state = draft.state); preset = DatePreset.Any; minText = ""; maxText = "" }) { Text("Reset") }
                Text("Filter", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick = { commit() }) { Text("Apply") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Section("When") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(DatePreset.entries.toList()) { p ->
                            Chip(p.title, preset == p) {
                                preset = p
                                p.range()?.let { (f, to) -> draft = draft.copy(from = f, to = to) }
                                if (p == DatePreset.Custom && draft.from == null && draft.to == null) draft = draft.copy(from = LocalDate.now().toString())
                            }
                        }
                    }
                    if (preset == DatePreset.Custom) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DateButton("From", draft.from, Modifier.weight(1f)) { pickDate = "from" }
                        DateButton("To", draft.to, Modifier.weight(1f)) { pickDate = "to" }
                    }
                }
                Section("What") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(null to "Any", "expense" to "Expense", "refund" to "Refund", "transfer" to "Transfer").forEachIndexed { i, (k, label) ->
                            SegmentedButton(selected = draft.kind == k, onClick = { draft = draft.copy(kind = k) }, shape = SegmentedButtonDefaults.itemShape(i, 4), label = { Text(label, maxLines = 1) }, modifier = Modifier.testTag("kind-${k ?: "any"}"))
                        }
                    }
                    PickRowButton("Category", if (draft.uncategorized) "Uncategorized" else directory.category(draft.categoryId)) { picking = Picking.Category }
                    PickRowButton("Merchant", directory.merchant(draft.merchantId)) { picking = Picking.Merchant }
                    PickRowButton("Tags", draft.tagIds.mapNotNull { directory.tag(it) }.joinToString(", ").ifEmpty { null }) { picking = Picking.Tags }
                    OutlinedTextField(draft.accountRef, { draft = draft.copy(accountRef = it) }, label = { Text("Account") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Section("Amount") {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Currency: ${draft.currency ?: "Any"}") }
                        DropdownMenu(open, { open = false }) {
                            DropdownMenuItem(text = { Text("Any") }, onClick = { draft = draft.copy(currency = null); open = false })
                            currencies.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { draft = draft.copy(currency = c); open = false }) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(minText, { minText = it }, label = { Text("Min") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                        OutlinedTextField(maxText, { maxText = it }, label = { Text("Max") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    }
                    Text("Amounts are in one currency, so setting them picks one.", color = t.muted, style = MaterialTheme.typography.bodySmall)
                }
                Section("Order") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("date_desc" to "Newest first", "date_asc" to "Oldest first", "amount_desc" to "Largest first", "amount_asc" to "Smallest first")) { (k, label) ->
                            Chip(label, draft.sort == k) { draft = draft.copy(sort = k) }
                        }
                    }
                    if (draft.sortNeedsCurrency) Text("Sorting by amount needs a currency; one will be chosen.", color = t.muted, style = MaterialTheme.typography.bodySmall)
                }
                Section("Show") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("active" to "Active", "deleted" to "Deleted", "all" to "Both").forEachIndexed { i, (k, label) ->
                            SegmentedButton(selected = draft.state == k, onClick = { draft = draft.copy(state = k) }, shape = SegmentedButtonDefaults.itemShape(i, 3), label = { Text(label) })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Box(Modifier.padding(16.dp)) { PrimaryButton("Apply") { commit() } }
        }
    }

    when (picking) {
        Picking.Category -> SinglePickerSheet("Category", directory.categoriesByUse(loaded), { id -> draft = draft.copy(uncategorized = id.isEmpty(), categoryId = id.ifEmpty { null }); picking = null }, { picking = null },
            noneLabel = "Uncategorized", selected = if (draft.uncategorized) "" else draft.categoryId)
        Picking.Merchant -> SinglePickerSheet("Merchant", merchants.values.sortedBy { it.name.lowercase() }, { id -> draft = draft.copy(merchantId = id.ifEmpty { null }); picking = null }, { picking = null },
            noneLabel = "Any merchant", selected = draft.merchantId ?: "")
        Picking.Tags -> MultiPickerSheet("Tags (all must match)", directory.tagsByUse(loaded), draft.tagIds.toSet(), { draft = draft.copy(tagIds = it.sorted()) }, { picking = null }, emptyHint = "No tags yet.")
        null -> {}
    }
    pickDate?.let { which ->
        val current = (if (which == "from") draft.from else draft.to)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        val state = rememberDatePickerState(initialSelectedDateMillis = current.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms -> // the picker speaks UTC-midnight millis; read it in UTC so the day never shifts
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        draft = if (which == "from") draft.copy(from = d) else draft.copy(to = d)
                    }
                    pickDate = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = null }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Treasure.tok.muted, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

@Composable
private fun PickRowButton(title: String, value: String?, onClick: () -> Unit) {
    val t = Treasure.tok
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f)); Text(value ?: "Any", color = t.muted, maxLines = 1); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = t.muted)
    }
}

@Composable
private fun DateButton(label: String, value: String?, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("$label: ${value?.let { DayGroups.title(it) } ?: "—"}") }
}
