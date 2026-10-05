package org.nighthawklabs.treasure.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import org.nighthawklabs.treasure.Session
import org.nighthawklabs.treasure.data.DayGroups
import org.nighthawklabs.treasure.data.Money
import org.nighthawklabs.treasure.ingest.ImportPhase
import org.nighthawklabs.treasure.ingest.ImportSession
import org.nighthawklabs.treasure.ingest.ParsedItem
import org.nighthawklabs.treasure.ui.components.PrimaryButton
import org.nighthawklabs.treasure.ui.components.SkeletonList
import org.nighthawklabs.treasure.ui.components.rememberHaptics
import org.nighthawklabs.treasure.ui.theme.Tabular
import org.nighthawklabs.treasure.ui.theme.Treasure

/** The three ways to bring a document in; provided by [ImportHost] so any top bar can show the menu. */
class ImportActions(val scan: () -> Unit, val photos: () -> Unit, val file: () -> Unit)

val LocalImportActions = compositionLocalOf<ImportActions?> { null }

/** Next to "+" in the top bar. A tap on "+" stays the fast manual add. */
@Composable
fun ImportMenu() {
    val actions = LocalImportActions.current ?: return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.DocumentScanner, contentDescription = "Import receipt or statement") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Scan with camera") }, leadingIcon = { Icon(Icons.Filled.CameraAlt, null) }, onClick = { open = false; actions.scan() })
            DropdownMenuItem(text = { Text("Choose photos") }, leadingIcon = { Icon(Icons.Filled.Photo, null) }, onClick = { open = false; actions.photos() })
            DropdownMenuItem(text = { Text("Import file (PDF, image, CSV)") }, leadingIcon = { Icon(Icons.Filled.Description, null) }, onClick = { open = false; actions.file() })
        }
    }
}

/** Owns the import launchers and the review sheet; wraps the signed-in shell. */
@Composable
fun ImportHost(session: Session, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var active by remember { mutableStateOf<ImportSession?>(null) }
    fun begin(work: suspend (ImportSession) -> Unit) {
        val s = session.newImport(); active = s
        session.scope.launch { work(s) }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { u -> begin { it.startFile(u) } } }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> if (uris.isNotEmpty()) begin { it.startImages(uris) } }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pages = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages.orEmpty().map { it.imageUri }
            if (pages.isNotEmpty()) begin { it.startImages(pages) }
        }
    }
    val actions = remember {
        ImportActions(
            scan = {
                val options = GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).setPageLimit(10)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG).setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
                GmsDocumentScanning.getClient(options).getStartScanIntent(context as Activity)
                    .addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }
                    .addOnFailureListener { Toast.makeText(context, "The scanner isn't available on this device. Try Choose photos.", Toast.LENGTH_LONG).show() }
            },
            photos = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            file = { filePicker.launch(arrayOf("application/pdf", "image/*", "text/csv", "text/comma-separated-values", "text/plain")) },
        )
    }
    CompositionLocalProvider(LocalImportActions provides actions) { content() }
    active?.let { ImportReviewSheet(session, it) { active = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportReviewSheet(session: Session, import: ImportSession, onClose: () -> Unit) {
    val t = Treasure.tok
    val state by import.state.collectAsState()
    val scope = rememberCoroutineScope()
    val saving by rememberUpdatedState(state.phase == ImportPhase.Saving)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !saving })
    val closing = { scope.launch { sheet.hide(); onClose() }; Unit }

    ModalBottomSheet(onDismissRequest = { if (!saving) onClose() }, sheetState = sheet, containerColor = t.background, contentColor = t.text) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.96f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { closing() }, enabled = !saving) { Text("Close") }
                Text("Import", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(72.dp))
            }
            AnimatedContent(state.phase::class, Modifier.weight(1f), label = "phase", transitionSpec = { fadeIn() togetherWith fadeOut() }) { phase ->
                when (phase) {
                    ImportPhase.Reading::class -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                        CircularProgressIndicator(); Text("Reading on this device…", color = t.muted, modifier = Modifier.padding(top = 12.dp))
                    }
                    ImportPhase.Review::class -> Review(session, import) 
                    ImportPhase.Saving::class -> Column(Modifier.fillMaxSize().padding(40.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                        LinearProgressIndicator(progress = { state.progress }, Modifier.fillMaxWidth()); Text("Importing…", color = t.muted, modifier = Modifier.padding(top = 12.dp))
                    }
                    ImportPhase.Done::class -> (state.phase as? ImportPhase.Done)?.let { Done(it, onDone = { closing() }) }
                    else -> (state.phase as? ImportPhase.Failed)?.let { f ->
                        Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Warning, null, tint = t.warn); Text("Couldn't import", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                            Text(f.message, color = t.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
                            OutlinedButton(onClick = { closing() }) { Text("Close") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Review(session: Session, import: ImportSession) {
    val t = Treasure.tok
    val scope = rememberCoroutineScope()
    val state by import.state.collectAsState()
    val spendsState by session.spends.state.collectAsState()
    val categories by session.directory.categories.collectAsState()
    val cats = remember(spendsState.spends, categories) { session.directory.categoriesByUse(spendsState.spends) }
    var showText by remember { mutableStateOf(false) }
    val summary = buildString {
        append("${state.items.size} found · ${state.selected} selected")
        if (state.duplicates > 0) append(" · ${state.duplicates} look like ones you already have")
        append(". Check each row before importing.")
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.doc?.isCSV == false) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf(false to "Receipt", true to "Statement").forEachIndexed { i, (v, label) ->
                                SegmentedButton(selected = state.asStatement == v, onClick = { import.setAsStatement(v); scope.launch { import.reparse() } }, shape = SegmentedButtonDefaults.itemShape(i, 2), label = { Text(label) })
                            }
                        }
                    }
                    if (state.asStatement) {
                        Text("Money out is shown as", color = t.muted, style = MaterialTheme.typography.labelMedium)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf(true to "Negative", false to "Positive").forEachIndexed { i, (v, label) ->
                                SegmentedButton(selected = state.negativeIsSpend == v, onClick = { import.setNegativeIsSpend(v); scope.launch { import.reparse() } }, shape = SegmentedButtonDefaults.itemShape(i, 2), label = { Text(label) })
                            }
                        }
                    }
                    var currencyMenu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Currency", color = t.muted, modifier = Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { currencyMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(state.currency) }
                            DropdownMenu(currencyMenu, { currencyMenu = false }) {
                                (listOf("USD", "EUR", "GBP", "INR", "JPY", "CAD", "AUD") + state.currency).distinct().sorted().forEach { c ->
                                    DropdownMenuItem(text = { Text(c) }, onClick = { currencyMenu = false; import.setCurrency(c); scope.launch { import.reparse() } })
                                }
                            }
                        }
                    }
                    Text(summary, color = t.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.items.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No transactions found", style = MaterialTheme.typography.titleMedium)
                    Text("Try a clearer photo, or switch between Receipt and Statement.", color = t.muted, textAlign = TextAlign.Center)
                }
            }
            items(state.items, key = { it.id }) { item -> ReviewRow(item, categories.mapValues { it.value.name }, cats.map { it.id to it.name }, import) }
            state.doc?.text?.takeIf { it.isNotEmpty() }?.let { text ->
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        TextButton(onClick = { showText = !showText }) { Text(if (showText) "Hide text read from the file" else "Show text read from the file") }
                        if (showText) Text(text, color = t.muted, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                    }
                }
            }
        }
        Box(Modifier.padding(16.dp)) {
            PrimaryButton("Import ${state.selected} spend${if (state.selected == 1) "" else "s"}", enabled = state.selected > 0) { scope.launch { import.save() } }
        }
    }
}

@Composable
private fun ReviewRow(item: ParsedItem, categoryNames: Map<String, String>, cats: List<Pair<String, String>>, import: ImportSession) {
    val t = Treasure.tok
    var menu by remember { mutableStateOf(false) }
    val category = item.categoryId?.let { categoryNames[it] }
    Row(Modifier.fillMaxWidth().background(t.surface).heightIn(min = 64.dp).padding(start = 4.dp, end = 4.dp).then(if (item.include) Modifier else Modifier.background(t.surface)),
        verticalAlignment = Alignment.Top) {
        IconButton(onClick = { import.toggle(item.id) }) {
            Icon(if (item.include) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, if (item.include) "Selected" else "Not selected", tint = if (item.include) t.accent else t.muted)
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(item.merchantName ?: item.description.ifEmpty { item.kind.replaceFirstChar { it.uppercase() } }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (item.include) t.text else t.muted)
            Text("${DayGroups.title(item.date)} · ${category ?: if (item.kind == "expense") "Uncategorized" else item.kind.replaceFirstChar { it.uppercase() }}", color = t.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (item.duplicateOf != null) Text("Looks like one you already have", color = t.warn, style = MaterialTheme.typography.bodySmall)
            else if (item.possibleDuplicateOf != null) Text("Might match an entry you already have", color = t.muted, style = MaterialTheme.typography.bodySmall)
            else if (item.merchantId == null && item.merchantName != null) Text("New merchant", color = t.muted, style = MaterialTheme.typography.bodySmall)
        }
        Text(Money.format(item.amountMinor, item.currency), style = Tabular, color = if (item.kind == "refund") t.live else if (item.include) t.text else t.muted, modifier = Modifier.padding(top = 14.dp, end = 4.dp))
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Edit type and category") }
            DropdownMenu(menu, { menu = false }) {
                listOf("expense" to "Expense", "refund" to "Refund", "transfer" to "Transfer").forEach { (k, label) ->
                    DropdownMenuItem(text = { Text(label) }, leadingIcon = { if (item.kind == k) Icon(Icons.Filled.Check, null) }, onClick = { import.setKind(item.id, k); menu = false })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("No category") }, leadingIcon = { if (item.categoryId == null) Icon(Icons.Filled.Check, null) }, onClick = { import.setCategory(item.id, null); menu = false })
                cats.forEach { (id, name) ->
                    DropdownMenuItem(text = { Text(name) }, leadingIcon = { if (item.categoryId == id) Icon(Icons.Filled.Check, null) }, onClick = { import.setCategory(item.id, id); menu = false })
                }
            }
        }
    }
}

@Composable
private fun Done(done: ImportPhase.Done, onDone: () -> Unit) {
    val t = Treasure.tok
    val haptics = rememberHaptics()
    var shown by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (shown) 1f else 0.4f, spring(Spring.DampingRatioMediumBouncy), label = "check")
    LaunchedEffect(Unit) { shown = true; haptics.success() }
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(Icons.Filled.CheckCircle, null, tint = t.accent, modifier = Modifier.size(64.dp).scale(scale))
        Text("Imported ${done.created} spend${if (done.created == 1) "" else "s"}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        if (done.skipped > 0) Text("${done.skipped} were already in Treasure.", color = t.muted)
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Done", onClick = onDone)
    }
}
