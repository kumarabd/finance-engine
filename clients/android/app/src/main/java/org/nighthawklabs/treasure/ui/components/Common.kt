package org.nighthawklabs.treasure.ui.components

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.togetherWith
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.nighthawklabs.treasure.ui.theme.Treasure

/** True when the user turned system animations off; every animation here falls back to an instant change. */
@Composable
fun reduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

class Haptics(private val view: View) {
    fun tick() { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    fun detent() { view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK) }
    fun success() { view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS) }
}

@Composable
fun rememberHaptics(): Haptics { val v = LocalView.current; return remember(v) { Haptics(v) } }

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val t = Treasure.tok
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = t.accent, contentColor = t.onAccent, disabledContainerColor = t.accent.copy(alpha = .35f), disabledContentColor = t.onAccent),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

/** A pill the user can tap to pick; selected is filled with the accent. At least 48dp tall to be a comfortable target. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val t = Treasure.tok
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
            .background(if (selected) t.accent else t.raised)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) t.onAccent else t.text, style = MaterialTheme.typography.labelLarge) }
}

/** An opaque panel with a hairline border, the same surface treatment as iOS. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t = Treasure.tok
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = t.surface, border = BorderStroke(1.dp, t.hairline), content = content)
}

/** Shown when a screen has nothing to display because a request failed. */
@Composable
fun ProblemView(message: String, modifier: Modifier = Modifier, retry: () -> Unit) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.WifiOff, null, tint = Treasure.tok.muted)
        Text("Can't load", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(message, color = Treasure.tok.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        OutlinedButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
    }
}

@Composable
fun EmptyView(title: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(title, color = Treasure.tok.muted, style = MaterialTheme.typography.titleMedium)
    }
}

/** Placeholder rows while the first page loads. Static under reduced motion. */
@Composable
fun SkeletonList(modifier: Modifier = Modifier) {
    val still = reduceMotion()
    val alpha = if (still) 1f else rememberInfiniteTransition(label = "skeleton").animateFloat(
        1f, 0.5f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha",
    ).value
    Column(modifier.fillMaxWidth().padding(20.dp).alpha(alpha).semantics { contentDescription = "Loading" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(7) { Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(Treasure.tok.raised)) }
    }
}

/**
 * Money text whose digits roll when they change, character by character from the right so the decimals stay anchored.
 * Tabular figures keep every glyph the same width. Instant under reduced motion.
 */
@androidx.compose.runtime.Composable
fun AnimatedAmount(text: String, style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val still = reduceMotion()
    androidx.compose.foundation.layout.Row(modifier.semantics(mergeDescendants = true) { contentDescription = text }) {
        text.forEachIndexed { i, ch ->
            androidx.compose.runtime.key(text.length - i) {
                androidx.compose.animation.AnimatedContent(
                    targetState = ch, label = "digit",
                    transitionSpec = {
                        if (still) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                        else (androidx.compose.animation.slideInVertically(tween(180)) { it / 2 } + androidx.compose.animation.fadeIn(tween(180))) togetherWith
                            (androidx.compose.animation.slideOutVertically(tween(120)) { -it / 2 } + androidx.compose.animation.fadeOut(tween(120)))
                    },
                ) { c -> Text(c.toString(), style = style, color = color, maxLines = 1, softWrap = false, modifier = Modifier.clearAndSetSemantics {}) }
            }
        }
    }
}
