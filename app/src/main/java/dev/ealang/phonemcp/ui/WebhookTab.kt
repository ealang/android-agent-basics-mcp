package dev.ealang.phonemcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
                // Only in a build made without credentials — nothing the
                // user can fix on the phone, so it names the build, not a
                // field that no longer exists.
                !TelegramEngine.configured ->
                    "This build has no Telegram credentials." to false
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

            // No API ID/hash fields: the pair is baked in at build time
            // (2026-09-19). They identify the app rather than the account,
            // so every install shares one pair and there was nothing for
            // the user to decide — only a my.telegram.org errand standing
            // between them and a working app.

            // Login lives in a dialog now, not an
            // always-there field: Log in whenever anything short of a
            // session exists — a stale login included, per the owner's
            // simplification — and Log out when connected. Closing the
            // dialog abandons the attempt; reopening starts a new one.
            var loginDialog by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (health == TelegramEngine.Health.NOT_AUTHENTICATED) {
                    OutlinedButton(onClick = {
                        // Always from the phone number.
                        // Resuming mid-flight sounded considerate and was a
                        // trap: a login code that never arrives left the
                        // dialog stuck at "enter the code" forever.
                        if (TelegramEngine.stage != TelegramEngine.Stage.PHONE) {
                            TelegramEngine.restartLogin()
                        }
                        loginDialog = true
                    }) { Text("Log in") }
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

            // The destination, named rather than numbered. The id field and its Test button are gone: an
            // id that came from a picker needs no testing, and one nobody
            // can mistype needs no validating. The picker is only offered
            // when connected, since it is a live server search.
            Text(
                config.telegramChatId?.let { id ->
                    "Sending to: " + config.telegramChatTitle.ifBlank { "chat $id" }
                } ?: "No destination chat chosen.",
                style = MaterialTheme.typography.bodySmall,
            )
            var chatPicker by remember { mutableStateOf(false) }
            OutlinedButton(
                enabled = TelegramEngine.stage == TelegramEngine.Stage.READY,
                onClick = { chatPicker = true },
            ) { Text(if (config.telegramChatId == null) "Choose chat" else "Change chat") }
            if (chatPicker) {
                ChatPickerDialog(
                    onPick = { hit ->
                        config.telegramChatId = hit.id
                        config.telegramChatTitle = hit.title
                        chatPicker = false
                        activity.toast("Sending to ${hit.title}")
                    },
                    onDismiss = { chatPicker = false },
                )
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
 * TDLib, and the Log in button resets it before opening this, so there is
 * never a half-finished attempt to resume into and no Back button to
 * build. Close is therefore a real cancel, not a pause.
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

/**
 * Pick the destination chat by name. Opens blank on purpose: there is no recents list to show, because nothing in this
 * app keeps a chat list — the results are whatever the server just said,
 * merged with nothing.
 *
 * Search-as-you-type over a network call needs two guards, both supplied
 * by [LaunchedEffect] keyed on the query rather than by hand. Typing a
 * letter cancels the previous effect, so the debounce below only elapses
 * once typing pauses; and a search already in flight has its continuation
 * abandoned, so a slow early response cannot overwrite a fast later one.
 * Getting that wrong shows the results for "mu" above the results for
 * "mum", which is the classic form of this bug.
 */
@Composable
private fun ChatPickerDialog(
    onPick: (TelegramEngine.ChatHit) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TelegramEngine.ChatHit>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            hits = emptyList()
            note = null
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        note = "Searching…"
        val found = suspendCancellableCoroutine { cont ->
            TelegramEngine.searchChats(trimmed) { cont.resume(it) }
        }
        hits = found.getOrDefault(emptyList())
        note = found.fold(
            onSuccess = { if (it.isEmpty()) "No chat matches that name." else null },
            onFailure = { "Search failed: ${it.message}" },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose destination chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Say what is being searched (the bare
                // field read like it might search the whole of Telegram,
                // and picking an unknown stranger as the destination is
                // exactly the mistake worth making impossible to worry
                // about). "Your chats" rather than "recent chats" because
                // recency is not the filter — TDLib matches the names of
                // every chat the account already has, however old.
                Text(
                    "Searches your existing Telegram chats by name.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                note?.let { StatusText(it) }
                Column(
                    Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                ) {
                    hits.forEach { hit ->
                        TextButton(
                            onClick = { onPick(hit) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                hit.title,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Long enough to swallow a burst of typing, short enough to feel live. */
private const val SEARCH_DEBOUNCE_MS = 300L
