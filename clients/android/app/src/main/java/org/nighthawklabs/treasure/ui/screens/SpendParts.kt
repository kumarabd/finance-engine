package org.nighthawklabs.treasure.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.nighthawklabs.treasure.Session
import org.nighthawklabs.treasure.data.Money
import org.nighthawklabs.treasure.data.OutboxItem
import org.nighthawklabs.treasure.data.Spend
import org.nighthawklabs.treasure.ui.theme.Tabular
import org.nighthawklabs.treasure.ui.theme.Treasure

@Composable
fun SpendRow(session: Session, spend: Spend, modifier: Modifier = Modifier) {
    val merchants by session.directory.merchants.collectAsState()
    val categories by session.directory.categories.collectAsState()
    val tags by session.directory.tags.collectAsState()
    val t = Treasure.tok
    val title = merchants[spend.merchantId]?.name ?: spend.description?.takeIf { it.isNotEmpty() } ?: spend.kind.replaceFirstChar { it.uppercase() }
    val cats = spend.allocations.orEmpty().mapNotNull { categories[it.categoryId]?.name }
    val base = cats.joinToString(", ").ifEmpty { if (spend.kind == "expense") "Uncategorized" else spend.kind.replaceFirstChar { it.uppercase() } }
    val tagNames = spend.tagIds.orEmpty().mapNotNull { tags[it]?.name }
    val subtitle = if (tagNames.isEmpty()) base else base + " · " + tagNames.joinToString(" ") { "#$it" }
    val amount = Money.format(spend.amountMinor, spend.currency)
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = t.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(amount, style = Tabular, color = if (spend.kind == "refund") t.live else t.text)
    }
}

/** Spends saved offline, shown until they reach the engine. A refused one explains why and can be discarded. */
@Composable
fun PendingRow(item: OutboxItem, onDiscard: () -> Unit, onRetry: () -> Unit = {}) {
    val t = Treasure.tok
    val failed = item.failure != null
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (failed) Icons.Filled.Warning else Icons.Filled.CloudOff, null, tint = if (failed) t.warn else t.muted)
        Column(Modifier.weight(1f)) {
            Text(item.merchantName ?: item.spend.description ?: item.spend.kind.replaceFirstChar { it.uppercase() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.failure ?: "Will send when you're back online", color = t.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        Text(Money.format(item.spend.amountMinor, item.spend.currency), style = Tabular)
        if (failed) { TextButton(onClick = onRetry) { Text("Retry") }; TextButton(onClick = onDiscard) { Text("Discard") } }
    }
}

@Composable
fun SectionLabel(text: String, trailing: String? = null) {
    val t = Treasure.tok
    Row(Modifier.fillMaxWidth().background(t.background).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, color = t.muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = t.muted, style = MaterialTheme.typography.labelLarge.merge(Tabular))
    }
}
