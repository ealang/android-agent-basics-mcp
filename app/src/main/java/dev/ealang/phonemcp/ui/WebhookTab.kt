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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

        // Health is three states, not seven: everything
        // short of a logged-in client is "not authenticated", offline is
        // visible but not a failure. The warning icon shows through the
        // collapsed card for the states that need the user — a dead login,
        // or no destination chat, either of which quietly queues every
        // message (found in testing: only the Test button revealed why).
        val health = TelegramEngine.health
        val noChat = config.telegramChatId == null
        CollapsibleCard(
            "Telegram forwarding",
            warn = health == TelegramEngine.Health.NOT_AUTHENTICATED ||
                (health == TelegramEngine.Health.HEALTHY && noChat),
        ) {
            // The one-liner at the top of the open card. It says what state
            // we are in and what to do about it — never what step of a login
            // is pending, which is the dialog's job (the
            // card telling you to enter your phone number was redundant with
            // the dialog that then asks for it, and unclear about how to get
            // there). Offline claims nothing else: an unreachable server
            // cannot confirm the session is still good, so "connected as …"
            // alongside "offline" was a contradiction, not extra detail.
            val (line, fine) = when {
                TelegramEngine.stage == TelegramEngine.Stage.OFF ->
                    "Not configured — save an API ID and hash below." to false
                health == TelegramEngine.Health.NOT_AUTHENTICATED ->
                    "Not authenticated — log in below." to false
                health == TelegramEngine.Health.OFFLINE ->
                    "Offline — messages queue until the network is back." to false
                noChat -> "Connected, but no destination chat is set below." to false
                else -> {
                    val who = TelegramEngine.account?.let { " as $it" } ?: ""
                    "Healthy — connected$who." to true
                }
            }
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = if (fine) okGreen else MaterialTheme.colorScheme.error,
            )

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

            // Login lives in a dialog now, not an
            // always-there field: Log in whenever anything short of a
            // session exists — a stale login included, per the owner's
            // simplification — and Log out when connected. Closing the
            // dialog early loses nothing: the stage is TDLib's, and
            // reopening resumes wherever it waits.
            var loginDialog by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (health == TelegramEngine.Health.NOT_AUTHENTICATED) {
                    OutlinedButton(onClick = { loginDialog = true }) { Text("Log in") }
                }
                if (TelegramEngine.stage == TelegramEngine.Stage.READY) {
                    OutlinedButton(onClick = { TelegramEngine.logOut() }) { Text("Log out") }
                }
            }
            if (loginDialog) {
                LoginDialog(
                    stage = TelegramEngine.stage,
                    status = TelegramEngine.statusLine(),
                    onDismiss = { loginDialog = false },
                )
            }

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

/**
 * The whole login in one dialog: a single field whose meaning follows
 * the stage TDLib is waiting in (phone → code → password when two-step
 * is on), and a status line carrying TDLib's own error when a step is
 * rejected. Closes itself once the session is up. Deliberately owns no
 * wizard state (keep it simple) — the stage lives in
 * TDLib, so Close and reopen resumes in place, never restarts.
 */
@Composable
private fun LoginDialog(
    stage: TelegramEngine.Stage,
    status: String,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }

    LaunchedEffect(stage) {
        if (stage == TelegramEngine.Stage.READY) onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log in to Telegram") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusText(status)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = {
                        Text(
                            when (stage) {
                                TelegramEngine.Stage.PHONE -> "Phone number (+1…)"
                                TelegramEngine.Stage.CODE -> "Login code"
                                TelegramEngine.Stage.PASSWORD -> "Two-step password"
                                else -> "(no input needed right now)"
                            },
                        )
                    },
                    visualTransformation = if (stage == TelegramEngine.Stage.PASSWORD) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = when (stage) {
                        TelegramEngine.Stage.PHONE ->
                            KeyboardOptions(keyboardType = KeyboardType.Phone)
                        TelegramEngine.Stage.CODE ->
                            KeyboardOptions(keyboardType = KeyboardType.Number)
                        else -> KeyboardOptions.Default
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                TelegramEngine.submitAuthInput(input)
                input = ""
            }) { Text("Submit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
