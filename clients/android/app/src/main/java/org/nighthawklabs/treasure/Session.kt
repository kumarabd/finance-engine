package org.nighthawklabs.treasure

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.nighthawklabs.treasure.auth.Auth
import org.nighthawklabs.treasure.data.*
import org.nighthawklabs.treasure.net.Engine
import org.nighthawklabs.treasure.net.FinanceApi
import java.io.File

/** What the Add/Edit sheet is showing; owned by the session so any tab can open it. */
sealed interface EditorTarget {
    data object New : EditorTarget
    data class Edit(val spend: Spend) : EditorTarget
}

class Composer { var target by mutableStateOf<EditorTarget?>(null) }

/** Everything that belongs to one signed-in user. Rebuilt on sign-in and dropped on sign-out, so another account never sees it. */
class Session(context: Context, val userId: String) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val name = "u-" + userId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    /** Re-fetchable lists: the OS may purge the cache directory. */
    private val cacheDir = File(context.cacheDir, name)
    private val cache = DiskCache(cacheDir)
    /** Unsent spends are the user's data, not a cache: they live in app-private files the OS never purges. */
    private val durable = DiskCache(File(context.filesDir, name))

    val engine = Engine(
        FinanceApi((DevMode.engineUrl ?: (BuildConfig.ROUTER_BASE_URL.trimEnd('/') + "/finance")) + "/api/v1"), userId,
        currentUser = { if (DevMode.engineUrl != null) DevMode.USER else (Auth.state.value as? org.nighthawklabs.treasure.auth.AuthState.SignedIn)?.userId },
    ) { skipCache -> if (DevMode.engineUrl != null) "dev" else Auth.token(skipCache) }
    val directory = Directory(engine, cache)
    val spends = SpendsStore(engine, cache, Outbox(durable), resolveMerchant = { directory.resolveMerchant(it) })
    val insights = Insights(engine)
    val composer = Composer()
    private val extractor = org.nighthawklabs.treasure.ingest.TextExtractor(context)

    fun newImport() = org.nighthawklabs.treasure.ingest.ImportSession(engine, directory, spends, extractor)

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { scope.launch { spends.flushOutbox() } }
    }

    fun start() {
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
        scope.launch { directory.refresh(); spends.flushOutbox() }
    }

    /** On sign-out: this user's re-fetchable lists go. Spends saved offline and not yet sent stay, in their own folder, and are sent only when they sign in again. */
    fun purgeCaches() { cacheDir.deleteRecursively() }

    fun close() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
