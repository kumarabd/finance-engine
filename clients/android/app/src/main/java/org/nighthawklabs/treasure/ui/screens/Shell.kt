package org.nighthawklabs.treasure.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.fragment.app.FragmentActivity
import org.nighthawklabs.treasure.Session
import org.nighthawklabs.treasure.auth.AppLock
import org.nighthawklabs.treasure.auth.AuthState
import org.nighthawklabs.treasure.data.DimensionKind
import org.nighthawklabs.treasure.ui.theme.Treasure

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home), Spends("Spends", Icons.Filled.List),
    Insights("Insights", Icons.Filled.BarChart), More("More", Icons.Filled.MoreHoriz),
}

/** Four tabs always one tap away; adding a spend is a task, so it is a sheet rather than a destination. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Shell(
    session: Session, user: AuthState.SignedIn, activity: FragmentActivity, lock: AppLock,
    openSpend: (String) -> Unit, openOrganize: (DimensionKind) -> Unit,
) {
    val t = Treasure.tok
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    ImportHost(session) {
    Scaffold(
        containerColor = t.background,
        bottomBar = {
            NavigationBar(containerColor = t.surface) {
                Tab.entries.forEach {
                    NavigationBarItem(
                        selected = tab == it, onClick = { tab = it }, icon = { Icon(it.icon, null) }, label = { Text(it.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer, selectedIconColor = t.accent, selectedTextColor = t.text, unselectedIconColor = t.muted, unselectedTextColor = t.muted),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            when (tab) {
                Tab.Home -> HomeScreen(session, openSpend)
                Tab.Spends -> SpendsScreen(session, openSpend)
                Tab.Insights -> InsightsScreen(session)
                Tab.More -> MoreScreen(session, user, activity, lock, openOrganize)
            }
        }
    }
    }
    session.composer.target?.let { target ->
        SpendEditor(session, target, onDismiss = { session.composer.target = null })
    }
}

/** The "+" shown in the top bar of Home and Spends. */
@Composable
fun AddAction(session: Session) {
    ImportMenu()
    IconButton(onClick = { session.composer.target = org.nighthawklabs.treasure.EditorTarget.New }) {
        Icon(Icons.Filled.Add, contentDescription = "Add spend")
    }
}
