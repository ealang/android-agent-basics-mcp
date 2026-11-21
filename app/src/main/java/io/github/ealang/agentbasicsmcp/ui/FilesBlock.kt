package io.github.ealang.agentbasicsmcp.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ealang.agentbasicsmcp.MainActivity
import io.github.ealang.agentbasicsmcp.mcp.files.FileFolder
import io.github.ealang.agentbasicsmcp.mcp.files.FileStore

/**
 * The folders the user has added for the file tools, shown under the Files
 * switch while it is on: each one picked in the system's folder picker and
 * named. tmp is the app's own and is not listed; there is nothing to set.
 */
@Composable
fun FilesBlock(activity: MainActivity) {
    val config = activity.config
    val folders = config.fileFolders

    Text(
        "Folders",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    if (folders.isEmpty()) StatusText("None added.")

    folders.forEach { folder ->
        // One folder is one block with nothing between its two rows: both
        // end in a control with Material's own 48dp touch target, which is
        // all the air they need. The gap between folders is the card's.
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(folder.name, style = MaterialTheme.typography.bodyLarge)
                    StatusText(where(folder.treeUri))
                }
                // The error colour is the theme's word for destructive, in
                // light and dark alike.
                TextButton(
                    onClick = { activity.removeFileFolder(folder) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Remove") }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Write access", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = folder.writable,
                    onCheckedChange = { on ->
                        config.fileFolders = config.fileFolders.map {
                            if (it.name == folder.name) it.copy(writable = on) else it
                        }
                        activity.bump()
                    },
                )
            }
        }
    }

    OutlinedButton(onClick = { activity.pickFileFolder() }) { Text("Add folder") }

    activity.pickedFolder.value?.let { picked ->
        NameFolderDialog(
            picked = picked,
            taken = folders.map { it.name } + FileStore.TMP,
            onDismiss = { activity.dropPickedFolder() },
            onConfirm = { name -> activity.addFileFolder(name, picked) },
        )
    }
}

@Composable
private fun NameFolderDialog(
    picked: Uri,
    taken: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(picked) { mutableStateOf(suggestion(picked, taken)) }
    val problem = when {
        name.isEmpty() -> "Give it a name."
        !NAME.matches(name) -> "Letters, digits, - and _ only."
        taken.any { it.equals(name, ignoreCase = true) } -> "That name is taken."
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this folder") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusText(
                    "${where(picked.toString())}\n\nAgents see the name, not where " +
                        "the folder is. It starts read only.",
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = { problem?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = problem == null, onClick = { onConfirm(name) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val NAME = Regex("[A-Za-z0-9_-]{1,40}")

/** Where a picked folder is, as the picker's own id for it: "primary:Books". */
private fun where(treeUri: String): String = try {
    DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
} catch (e: IllegalArgumentException) {
    treeUri
}

/** The folder's own name, made to fit the rules, as a starting point. */
private fun suggestion(picked: Uri, taken: List<String>): String {
    val last = where(picked.toString()).substringAfterLast(':').substringAfterLast('/')
    val name = last.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(40)
    return if (taken.any { it.equals(name, ignoreCase = true) }) "" else name
}
