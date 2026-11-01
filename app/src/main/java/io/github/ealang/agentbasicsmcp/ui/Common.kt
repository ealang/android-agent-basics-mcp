package io.github.ealang.agentbasicsmcp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One card per subject, the page's building block. */
@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/**
 * A card whose body folds away behind its own title row. Collapsed by
 * default: these hold settings that are set once and then in the way.
 * [warn] puts a warning icon beside the title — the card's way of saying
 * something inside needs the user even while it is folded shut.
 */
@Composable
fun CollapsibleCard(
    title: String,
    initiallyExpanded: Boolean = false,
    warn: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (warn) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = "Needs attention",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
            if (expanded) content()
        }
    }
}

/**
 * Title + optional supporting line on the left, a switch on the right.
 * [warn] puts the same warning icon [CollapsibleCard] uses beside the
 * title — the one signal for "needs setup", everywhere it appears.
 */
@Composable
fun SwitchRow(
    title: String,
    supporting: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    warn: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (warn) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = "Needs attention",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * Smaller status prose, the tone the old single page used for state lines.
 * [warn] paints it in the error color — for a missing grant or a stopped
 * server, so the fact something needs doing is visible before reading.
 */
@Composable
fun StatusText(text: String, warn: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/**
 * The "this is fine" green for status text, beside [StatusText]'s red.
 * Deliberately not a theme slot: the app uses Material You dynamic
 * color, so `tertiary` is derived from the user's wallpaper and can
 * come out red, which reads as an alarm on a healthy line. A palette
 * position carries no meaning; only a literal color does. Two shades so
 * it stays legible on either background.
 */
val okGreen: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF7BC67E) else Color(0xFF2E7D32)

/** A ring-buffer log in a dialog, monospace, scrolling on its own. */
@Composable
fun LogDialog(
    title: String,
    lines: List<String>,
    onDismiss: () -> Unit,
    onClear: (() -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
) {
    val text = lines.joinToString("\n")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (lines.isEmpty()) "(nothing yet)" else text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (onClear == null && onCopy == null) null else {
            {
                Row {
                    if (onClear != null) {
                        TextButton(onClick = { onClear(); onDismiss() }) { Text("Clear") }
                    }
                    if (onCopy != null && lines.isNotEmpty()) {
                        TextButton(onClick = { onCopy(text) }) { Text("Copy") }
                    }
                }
            }
        },
    )
}
