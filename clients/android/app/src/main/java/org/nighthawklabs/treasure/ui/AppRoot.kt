package org.nighthawklabs.treasure.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import org.nighthawklabs.treasure.Session
import org.nighthawklabs.treasure.auth.AppLock
import org.nighthawklabs.treasure.auth.Auth
import org.nighthawklabs.treasure.auth.AuthState
import org.nighthawklabs.treasure.data.DimensionKind
import org.nighthawklabs.treasure.ui.components.PrimaryButton
import org.nighthawklabs.treasure.ui.components.reduceMotion
import org.nighthawklabs.treasure.ui.screens.*
import org.nighthawklabs.treasure.ui.theme.Treasure
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Composable
fun AppRoot(activity: FragmentActivity, lock: AppLock) {
    val auth by Auth.state.collectAsState()
    val locked by lock.locked.collectAsState()
    val t = Treasure.tok
    val still = reduceMotion()
    val scope = rememberCoroutineScope()
    // A Surface supplies the content color, so bare Text (the welcome screen) follows the theme in dark mode.
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = t.background, contentColor = t.text) {
      Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = auth, label = "auth",
            contentKey = { it::class },
            transitionSpec = { (if (still) androidx.compose.animation.EnterTransition.None else fadeIn(tween(250))) togetherWith
                (if (still) androidx.compose.animation.ExitTransition.None else fadeOut(tween(150))) },
        ) { state ->
            when (state) {
                AuthState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { Icon(Icons.Filled.Diamond, null, tint = t.accent, modifier = Modifier.size(44.dp)) }
                AuthState.SignedOut -> Welcome()
                is AuthState.SignedIn -> SignedIn(state, activity, lock)
            }
        }
        if (locked) LockCover { scope.launch { lock.unlock(activity) } }
      }
    }
}

@Composable
private fun LockCover(onUnlock: () -> Unit) {
    val t = Treasure.tok
    Column(Modifier.fillMaxSize().background(t.background), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Diamond, null, tint = t.accent, modifier = Modifier.size(44.dp))
        Text("Treasure is locked", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 16.dp))
        OutlinedButton(onClick = onUnlock, modifier = Modifier.heightIn(min = 48.dp)) { Text("Unlock") }
    }
}

@Composable
private fun Welcome() {
    val t = Treasure.tok
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.Diamond, null, tint = t.accent, modifier = Modifier.size(44.dp))
        Text("Treasure", fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
        Text("Every spend, in one place you own.", color = t.muted, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.weight(1f))
        error?.let { Text(it, color = t.critical, modifier = Modifier.padding(bottom = 12.dp)) }
        PrimaryButton(if (busy) "Signing in…" else "Continue with Google", enabled = !busy) {
            busy = true
            scope.launch { error = Auth.signInWithGoogle(); busy = false }
        }
    }
}

/** Builds the per-user session once and drops it on sign-out. */
@Composable
private fun SignedIn(state: AuthState.SignedIn, activity: FragmentActivity, lock: AppLock) {
    val context = LocalContext.current
    val session = remember(state.userId) { Session(context.applicationContext, state.userId) }
    DisposableEffect(session) { session.start(); onDispose { session.close() } }
    // Coming back to the foreground is a good moment to send anything saved offline.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner, session) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) session.scope.launch { session.spends.flushOutbox() }
        }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    Nav(session, state, activity, lock)
}

@Composable
private fun Nav(session: Session, user: AuthState.SignedIn, activity: FragmentActivity, lock: AppLock) {
    val nav = rememberNavController()
    val still = reduceMotion()
    val slideIn = if (still) androidx.compose.animation.EnterTransition.None else slideInHorizontally(tween(250)) { it / 4 } + fadeIn(tween(250))
    val slideOut = if (still) androidx.compose.animation.ExitTransition.None else slideOutHorizontally(tween(200)) { it / 4 } + fadeOut(tween(150))
    NavHost(
        nav, startDestination = "tabs",
        enterTransition = { slideIn }, exitTransition = { if (still) androidx.compose.animation.ExitTransition.None else fadeOut(tween(150)) },
        popEnterTransition = { if (still) androidx.compose.animation.EnterTransition.None else fadeIn(tween(200)) }, popExitTransition = { slideOut },
    ) {
        composable("tabs") {
            Shell(session, user, activity, lock,
                openSpend = { nav.navigate("spend/${android.net.Uri.encode(it)}") },
                openOrganize = { nav.navigate("organize/${it.name}") })
        }
        composable("spend/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            DetailScreen(session, entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable("organize/{kind}", arguments = listOf(navArgument("kind") { type = NavType.StringType })) { entry ->
            val kind = DimensionKind.valueOf(entry.arguments?.getString("kind") ?: "Category")
            OrganizeScreen(session, kind, onBack = { nav.popBackStack() })
        }
    }
}
