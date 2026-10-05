package org.nighthawklabs.treasure.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nighthawklabs.treasure.EditorTarget
import org.nighthawklabs.treasure.Session
import org.nighthawklabs.treasure.data.*
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.problem
import org.nighthawklabs.treasure.ui.components.AnimatedAmount
import org.nighthawklabs.treasure.ui.components.Chip
import org.nighthawklabs.treasure.ui.components.rememberHaptics
import org.nighthawklabs.treasure.ui.theme.HeroStyle
import org.nighthawklabs.treasure.ui.theme.Treasure
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

private val CURRENCIES = listOf("USD", "EUR", "GBP", "INR", "JPY", "CAD", "AUD")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpendEditor(session: Session, target: EditorTarget, onDismiss: () -> Unit) {
    val store = session.spends
    val directory = session.directory
    val t = Treasure.tok
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("treasure", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // The newest list copy of the spend being edited, if any.
    var original by remember {
        mutableStateOf((target as? EditorTarget.Edit)?.spend?.let { s -> store.state.value.spends.firstOrNull { it.id == s.id } ?: s })
    }
    var entry by remember { mutableStateOf(AmountEntry.of(original?.amountMinor ?: 0)) }
    var kind by remember { mutableStateOf(original?.kind ?: "expense") }
    var currency by remember { mutableStateOf(original?.currency ?: prefs.getString("lastCurrency", null) ?: java.util.Currency.getInstance(Locale.getDefault()).currencyCode) }
    var date by remember { mutableStateOf(original?.occurredOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()) }
    var merchant by remember { mutableStateOf(directory.merchant(original?.merchantId) ?: "") }
    var categoryId by remember { mutableStateOf(original?.allocations?.takeIf { it.size == 1 }?.first()?.categoryId) }
    var note by remember { mutableStateOf(original?.description ?: "") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    var merchantFocused by remember { mutableStateOf(false) }
    var noteFocused by remember { mutableStateOf(false) }
    // One key per editing session: a retry after a dropped connection repeats the same write instead of duplicating it.
    var key by remember { mutableStateOf(UUID.randomUUID().toString()) }

    val merchants by directory.merchants.collectAsState()
    val spendsState by store.state.collectAsState()
    val splits = (original?.allocations?.size ?: 0) > 1
    val typing = merchantFocused || noteFocused
    val canSave = entry.minor > 0 && !saving

    fun fill(s: Spend) {
        original = s; entry = AmountEntry.of(s.amountMinor); kind = s.kind; currency = s.currency
        date = runCatching { LocalDate.parse(s.occurredOn) }.getOrDefault(LocalDate.now())
        merchant = directory.merchant(s.merchantId) ?: ""; note = s.description ?: ""
        categoryId = s.allocations?.takeIf { it.size == 1 }?.first()?.categoryId
    }

    fun close() { scope.launch { sheet.hide(); onDismiss() } }

    suspend fun finishSaved() {
        prefs.edit().putString("lastCurrency", currency).apply()
        haptics.success()
        delay(150) // let the confirmation haptic land before the sheet leaves
        sheet.hide(); onDismiss()
    }

    suspend fun save() {
        saving = true; error = null
        try {
            val typed = merchant.trim()
            var merchantId: String? = null
            var unsaved: String? = null // a new merchant can't be created offline; the outbox creates it later
            val o = original
            when (val m = directory.resolveMerchant(merchant)) {
                is Api.Ok -> merchantId = m.value
                is Api.Retry -> if (o == null && typed.isNotEmpty()) unsaved = typed else { error = m.problem; return }
                else -> { error = m.problem; return }
            }
            var input = o?.let(::SpendInput) ?: SpendInput(date.toString(), kind, 0, currency)
            input = input.copy(occurredOn = date.toString(), kind = kind, currency = currency, merchantId = merchantId, description = note.trim().ifEmpty { null })
            if (!splits) input = input.copy(amountMinor = entry.minor, allocations = categoryId?.let { listOf(Allocation(it, entry.minor)) })
            if (o == null) input = input.copy(source = "android", sourceRecordId = key)

            val r: Api<Spend> = when {
                o != null -> store.update(o, input, key)
                unsaved != null -> Api.Retry("offline")
                else -> store.create(input, key)
            }
            if (o == null && r is Api.Retry) {
                // Offline: keep the spend, send it when the connection returns. Same key, so a late reply can't duplicate it.
                store.enqueue(input, unsaved, key)
                finishSaved(); return
            }
            when {
                r is Api.Ok -> finishSaved()
                r is Api.Failed && r.code == "conflict" && o != null -> {
                    // Someone changed it elsewhere: show their version and let the user decide again.
                    store.fetch(o.id)?.let { fill(it); key = UUID.randomUUID().toString() }
                    error = "This spend changed elsewhere. The latest version is shown; review it and save again."
                }
                else -> error = r.problem
            }
        } finally { saving = false }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = t.background, contentColor = t.text) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.96f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { close() }) { Text("Cancel") }
                Text(if (original == null) "Add spend" else "Edit spend", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (saving) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(24.dp), strokeWidth = 2.dp)
                else TextButton(onClick = { scope.launch { save() } }, enabled = canSave) { Text("Save") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("expense" to "Expense", "refund" to "Refund", "transfer" to "Transfer").forEachIndexed { i, (k, label) ->
                        SegmentedButton(selected = kind == k, onClick = { kind = k }, shape = SegmentedButtonDefaults.itemShape(i, 3), label = { Text(label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (entry.minor == 0L) AnimatedAmount(Money.format(0, currency), HeroStyle, t.muted)
                        else AnimatedAmount(Money.format(entry.minor, currency), HeroStyle, if (kind == "refund") t.live else t.text)
                    }
                    if (original == null) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(currency) }
                            DropdownMenu(menu, { menu = false }) {
                                (CURRENCIES + currency).distinct().forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { currency = c; menu = false }) }
                            }
                        }
                    } else Text(currency, color = t.muted)
                }

                Field(merchant, { merchant = it }, "Merchant", capitalize = true, onFocus = { merchantFocused = it })
                val typedLower = merchant.trim().lowercase()
                val hints = if (typedLower.isEmpty()) emptyList()
                else merchants.values.filter { it.name.lowercase().startsWith(typedLower) && it.name.lowercase() != typedLower }.sortedBy { it.name }.take(5)
                if (hints.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(hints, key = { it.id }) { Chip(it.name, false) { merchant = it.name } } }

                if (splits) Text("Split across ${original?.allocations?.size} categories. Editing splits isn't available yet.", color = t.muted, style = MaterialTheme.typography.bodySmall)
                else {
                    val cats = remember(spendsState.spends, directory.categories.collectAsState().value) { directory.categoriesByUse(spendsState.spends) }
                    if (cats.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(cats, key = { it.id }) { c -> Chip(c.name, categoryId == c.id) { categoryId = if (categoryId == c.id) null else c.id } }
                    }
                }

                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(t.raised).clickable { pickDate = true }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Date", color = t.muted, modifier = Modifier.weight(1f))
                    Text(DayGroups.title(date.toString()))
                }
                Field(note, { note = it }, "Note", onFocus = { noteFocused = it })
                error?.let { Text(it, color = t.critical) }
                Spacer(Modifier.height(8.dp))
            }
            AnimatedVisibility(!typing, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Keypad(enabled = !splits) { k ->
                    haptics.tick()
                    entry = if (k == "⌫") entry.backspace() else entry.press(k)
                }
            }
        }
    }

    if (pickDate) {
        // The picker speaks UTC-midnight millis; convert both ways in UTC so the calendar date never shifts a day.
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = { picker.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; pickDate = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(picker) }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, capitalize: Boolean = false, onFocus: (Boolean) -> Unit) {
    val t = Treasure.tok
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(label) }, shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(capitalization = if (capitalize) KeyboardCapitalization.Words else KeyboardCapitalization.Sentences),
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = t.raised, unfocusedContainerColor = t.raised, focusedBorderColor = t.accent, unfocusedBorderColor = t.hairline),
        modifier = Modifier.fillMaxWidth().onFocusChanged { onFocus(it.isFocused) },
    )
}

/** A register-style numeric pad: amounts are typed as minor units, so no decimal key is needed. */
@Composable
private fun Keypad(enabled: Boolean, onKey: (String) -> Unit) {
    val t = Treasure.tok
    Column(Modifier.fillMaxWidth().background(t.background).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("00", "0", "⌫")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(14.dp)).background(t.surface).border(1.dp, t.hairline, RoundedCornerShape(14.dp))
                            .clickable(enabled = enabled) { onKey(k) }.semantics { contentDescription = if (k == "⌫") "Delete" else k },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == "⌫") Icon(Icons.AutoMirrored.Filled.Backspace, null, tint = if (enabled) t.text else t.muted)
                        else Text(k, style = MaterialTheme.typography.headlineSmall, color = if (enabled) t.text else t.muted)
                    }
                }
            }
        }
    }
}
