package org.nighthawklabs.treasure.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nighthawklabs.treasure.Session
import org.nighthawklabs.treasure.auth.AppLock
import org.nighthawklabs.treasure.auth.Auth
import org.nighthawklabs.treasure.auth.AuthState
import org.nighthawklabs.treasure.data.CSVJoin
import org.nighthawklabs.treasure.data.DimensionKind
import org.nighthawklabs.treasure.data.SearchInput
import org.nighthawklabs.treasure.data.ExportResult
import org.nighthawklabs.treasure.net.Api
import org.nighthawklabs.treasure.net.call
import org.nighthawklabs.treasure.net.problem
import org.nighthawklabs.treasure.ui.theme.Treasure
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(session: Session, user: AuthState.SignedIn, activity: FragmentActivity, lock: AppLock, openOrganize: (DimensionKind) -> Unit) {
    val t = Treasure.tok
    val scope = rememberCoroutineScope()
    val lockOn by lock.enabled.collectAsState()
    var exporting by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var lockProblem by remember { mutableStateOf<String?>(null) }

    suspend fun export() {
        exporting = true; problem = null
        try {
            val pages = mutableListOf<String>()
            var offset = 0
            while (true) {
                when (val r = session.engine.call<SearchInput, ExportResult>("spends_export", SearchInput(limit = 200, offset = offset))) {
                    is Api.Ok -> { pages += r.value.csv; offset = r.value.nextOffset ?: break }
                    else -> { problem = r.problem; return }
                }
            }
            val uri = withContext(Dispatchers.IO) {
                val dir = File(activity.cacheDir, "exports").apply { mkdirs() }
                val f = File(dir, "treasure-spends.csv").apply { writeText(CSVJoin.join(pages)) }
                FileProvider.getUriForFile(activity, "${activity.packageName}.files", f)
            }
            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share spends"))
        } catch (e: Exception) {
            problem = "Couldn't write the export file."
        } finally { exporting = false }
    }

    Scaffold(containerColor = t.background, topBar = { TopAppBar(title = { Text("More") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = t.background)) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SectionLabel("Organize") }
            DimensionKind.entries.forEach { k ->
                item(key = k.name) {
                    Row(Modifier.fillMaxWidth().background(t.surface).clickable { openOrganize(k) }.heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(k.title, modifier = Modifier.weight(1f)); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = t.muted)
                    }
                }
            }
            item { SectionLabel("Data") }
            item {
                Row(Modifier.fillMaxWidth().background(t.surface).clickable(enabled = !exporting) { scope.launch { export() } }.heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Export all spends (CSV)", modifier = Modifier.weight(1f)); if (exporting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            problem?.let { p -> item { Text(p, color = t.critical, modifier = Modifier.padding(16.dp)) } }
            item { SectionLabel("Security") }
            item {
                Row(Modifier.fillMaxWidth().background(t.surface).heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Lock with fingerprint or screen lock", modifier = Modifier.weight(1f))
                    Switch(checked = lockOn, onCheckedChange = { on -> scope.launch { lockProblem = lock.setEnabled(activity, on) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = t.accent, checkedThumbColor = t.onAccent))
                }
            }
            lockProblem?.let { p -> item { Text(p, color = t.critical, modifier = Modifier.padding(16.dp)) } }
            item { SectionLabel("Account") }
            user.email?.let { e -> item { Text(e, color = t.muted, modifier = Modifier.fillMaxWidth().background(t.surface).padding(16.dp)) } }
            item {
                TextButton(onClick = { session.purgeCaches(); scope.launch { Auth.signOut() } }, modifier = Modifier.fillMaxWidth().background(t.surface).heightIn(min = 56.dp)) {
                    Text("Sign out", color = t.critical)
                }
            }
        }
    }
}
