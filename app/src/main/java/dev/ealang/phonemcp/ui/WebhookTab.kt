package dev.ealang.phonemcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import dev.ealang.phonemcp.Config
import dev.ealang.phonemcp.LOG_DIALOG_LINES
import dev.ealang.phonemcp.Logs
import dev.ealang.phonemcp.MainActivity
import dev.ealang.phonemcp.McpService
import dev.ealang.phonemcp.webhook.PebbleWebhookServer
import dev.ealang.phonemcp.webhook.RecordingStore
import dev.ealang.phonemcp.webhook.telegram.TelegramEngine

/**
 * Half the app on one page: the webhook server's switch, whatever
 * setup is missing, how the pipeline is doing, the server's own settings,
 * and the single destination — Telegram, always on, taking every press.
 */
@Composable
fun WebhookTab(activity: MainActivity) {
    activity.tick.intValue // subscribe
    val config = activity.config

    var pipelineDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val on = config.featureEnabled(Config.FEATURE_WEBHOOK)
        ServerHeader(
            icon = Icons.Filled.Watch,
            title = "Pebble webhook",
            on = on,
            bound = McpService.webhookRunning,
            offText = "Off — the watch gets connection refused and retries later.",
            boundText = "Listening on ${PebbleWebhookServer.BIND}:${config.webhookPort}.",
            failingText = "On but not listening — see the server log.",
            onChange = { activity.setWebhookEnabled(it) },
        )

        SetupCard(activity, listOf(BATTERY_GRANT))

        SectionCard("Pipeline") {
            val (pending, failed) = McpService.pipelineSnapshot()
                ?: RecordingStore(activity.filesDir).let { it.pending().size to it.failed() }

            // Two lines, two subjects: what HTTP did (counters since app
            // start, rejections included so an auth failure doesn't read as
            // a message that vanished), then what became of the messages.
            // Queued/failed are live from the store and survive a restart
            // the way the counters do not. Every state always shows, zeros
            // included — one fixed shape, only the numbers move.
            StatusText(
                requestsLine(Logs.webhook) + "\n" +
                    "Message status — queued: $pending   sent: " +
                    "${Logs.webhook.count("sent")}   failed: ${failed.size}   " +
                    "dropped: ${Logs.webhook.count("dropped")}",
            )

            // No per-item list: individual Retry/Discard was overkill.
            // Failures are counted above and explained
            // in the log; the controls are bulk — Retry re-queues every
            // failed item, Clear discards everything queued and failed.
            // Each appears only when it has something to act on.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pipelineDialog = true }) { Text("View server log") }
                if (failed.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        McpService.retryAllFailed(activity)
                        activity.bumpSoon()
                    }) { Text("Retry") }
                }
                if (pending > 0 || failed.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        McpService.clearMessages(activity)
                        activity.bumpSoon()
                    }) { Text("Clear messages") }
                }
            }
        }

        CollapsibleCard("Server settings") {
            TokenBlock(
                activity,
                token = { activity.config.webhookToken },
                regenerate = { activity.regenerateWebhookToken() },
            )

            HorizontalDivider()

            StatusText("Receives at ${activity.webhookUrl()} — this phone only.")
            OutlinedButton(onClick = {
                activity.copyToClipboard("webhook URL", activity.webhookUrl())
            }) { Text("Copy webhook URL") }

            var portText by remember { mutableStateOf(config.webhookPort.toString()) }
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { activity.saveWebhookPort(portText) }) {
                Text("Save and apply")
            }
        }

        CollapsibleCard("Telegram forwarding") {
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
                activity.toast("Saved — applies from the next forwarded message")
            }) { Text("Save signature") }
        }
    }

    if (pipelineDialog) {
        LogDialog(
            title = "Webhook server log",
            lines = Logs.webhook.tail(LOG_DIALOG_LINES),
            onDismiss = { pipelineDialog = false },
            onClear = {
                Logs.webhook.clear()
                activity.toast("Log cleared")
            },
        )
    }
}
