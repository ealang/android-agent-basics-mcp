package dev.ealang.phonemcp.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.ealang.phonemcp.LOG_DIALOG_LINES
import dev.ealang.phonemcp.MainActivity
import dev.ealang.phonemcp.McpService
import dev.ealang.phonemcp.webhook.RecordingStore
import dev.ealang.phonemcp.webhook.WebhookConfig
import dev.ealang.phonemcp.webhook.telegram.TelegramEngine

/**
 * Everything a watch button press flows into: the pipeline's queue, and one
 * card per destination. Each destination card carries its own on/off switch
 * and a "which presses" selector — both saved but not yet enforced (the
 * pipeline still fans out to whatever is configured); see Config.featureEnabled.
 */
@Composable
fun PebbleTab(activity: MainActivity) {
    activity.tick.intValue // subscribe
    val config = activity.config

    var pipelineDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard("Pipeline") {
            // Reads the queue directly when the service is down — safe,
            // because a stopped service means no worker is writing.
            val (pending, failed) = McpService.pipelineSnapshot()
                ?: RecordingStore(activity.filesDir).let { it.pending().size to it.failed() }

            StatusText("Queued: $pending   Failed: ${failed.size}")

            for (item in failed) {
                HorizontalDivider()
                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                StatusText(item.failureReason() ?: "(no reason recorded)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        McpService.retryFailed(activity, item.name)
                        activity.bumpSoon()
                    }) { Text("Retry") }
                    OutlinedButton(onClick = {
                        McpService.discardFailed(activity, item.name)
                        activity.bumpSoon()
                    }) { Text("Discard") }
                }
            }

            OutlinedButton(onClick = { pipelineDialog = true }) { Text("View pipeline log") }
        }

        SectionCard("Telegram forwarding") {
            SwitchRow(
                title = "Forward recordings to Telegram",
                supporting = "Saved, but not enforced yet — forwarding runs whenever a chat is configured.",
                checked = config.featureEnabled("telegram"),
                onChange = { config.setFeatureEnabled("telegram", it); activity.bump() },
            )
            ButtonAssignmentRow(activity, "telegram")

            HorizontalDivider()

            StatusText(TelegramEngine.statusLine())

            var apiId by remember { mutableStateOf(config.telegramApiId?.toString() ?: "") }
            var apiHash by remember { mutableStateOf(config.telegramApiHash ?: "") }
            OutlinedTextField(
                value = apiId,
                onValueChange = { apiId = it },
                label = { Text("API ID (my.telegram.org)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiHash,
                onValueChange = { apiHash = it },
                label = { Text("API hash") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                val id = apiId.trim().toIntOrNull()
                if (id == null || id <= 0) {
                    activity.toast("API ID must be a positive number")
                    return@OutlinedButton
                }
                config.telegramApiId = id
                config.telegramApiHash = apiHash
                if (config.telegramApiHash == null) {
                    activity.toast("API hash is empty")
                    return@OutlinedButton
                }
                // Takes effect now on first save; after that TDLib holds its
                // parameters until the process restarts.
                TelegramEngine.ensureStarted(activity)
                activity.toast("Saved")
                activity.bump()
            }) { Text("Save credentials") }

            var login by remember { mutableStateOf("") }
            val stage = TelegramEngine.stage
            OutlinedTextField(
                value = login,
                onValueChange = { login = it },
                label = { Text("Login") },
                placeholder = {
                    Text(
                        when (stage) {
                            TelegramEngine.Stage.PHONE -> "+1…"
                            TelegramEngine.Stage.CODE -> "login code"
                            TelegramEngine.Stage.PASSWORD -> "two-step password"
                            else -> "(no input needed right now)"
                        },
                    )
                },
                // The code and password should not echo; the phone number is fine.
                visualTransformation = if (stage == TelegramEngine.Stage.PASSWORD) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                TelegramEngine.submitAuthInput(login)
                login = ""
            }) { Text("Submit") }

            var chat by remember { mutableStateOf(config.telegramChatId?.toString() ?: "") }
            OutlinedTextField(
                value = chat,
                onValueChange = { chat = it },
                label = { Text("Destination chat (numeric id)") },
                placeholder = { Text("e.g. -1001234567890") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val id = chat.trim().toLongOrNull()
                    if (id == null || id == 0L) {
                        activity.toast("Chat id must be a non-zero number")
                        return@OutlinedButton
                    }
                    config.telegramChatId = id
                    activity.toast("Saved")
                }) { Text("Save chat") }
                OutlinedButton(onClick = {
                    val id = chat.trim().toLongOrNull()
                    if (id == null || id == 0L) {
                        activity.toast("Enter a chat id first")
                        return@OutlinedButton
                    }
                    activity.toast("Checking…")
                    TelegramEngine.checkChat(id) { activity.toast(it) }
                }) { Text("Test") }
            }

            var signature by remember { mutableStateOf(config.telegramSignature) }
            OutlinedTextField(
                value = signature,
                onValueChange = { signature = it },
                label = { Text("Signature (appended to forwarded messages)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                config.telegramSignature = signature
                activity.toast("Saved — not appended yet, UI only for now")
            }) { Text("Save signature") }
        }

        SectionCard("Markdown notes") {
            SwitchRow(
                title = "Write notes to a Markdown file",
                supporting = "Saved, but not enforced yet — note recordings are always written.",
                checked = config.featureEnabled("markdown"),
                onChange = { config.setFeatureEnabled("markdown", it); activity.bump() },
            )
            ButtonAssignmentRow(activity, "markdown")

            HorizontalDivider()

            val notesOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                Environment.isExternalStorageManager()
            StatusText(
                if (notesOk) {
                    "Can write notes to ${WebhookConfig.NOTES_DIR}."
                } else {
                    "All files access is not granted, so note recordings will " +
                        "fail. Grant it below."
                },
                warn = !notesOk,
            )
            Button(onClick = {
                // For writing notes into a top-level folder, which scoped
                // storage cannot address. Settings-screen grant, like overlay.
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:${activity.packageName}")),
                )
            }) { Text("Allow all files access") }
        }
    }

    if (pipelineDialog) {
        LogDialog(
            title = "Pipeline log",
            lines = activity.pipelineLog.tail(LOG_DIALOG_LINES),
            onDismiss = { pipelineDialog = false },
            onClear = {
                activity.pipelineLog.clear()
                activity.toast("Log cleared")
            },
        )
    }
}

/** Which watch presses this destination claims. Saved, not yet enforced. */
@Composable
private fun ButtonAssignmentRow(activity: MainActivity, destination: String) {
    val config = activity.config
    val options = listOf("single" to "Single", "double" to "Double", "both" to "Both")
    val current = config.buttonAssignment(destination)

    Column {
        Text("Watch button", style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = current == value,
                    onClick = {
                        config.setButtonAssignment(destination, value)
                        activity.bump()
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label) }
            }
        }
    }
}
