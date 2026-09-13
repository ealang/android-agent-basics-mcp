package dev.ealang.phonemcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.ealang.phonemcp.MainActivity
import dev.ealang.phonemcp.webhook.WebhookConfig
import dev.ealang.phonemcp.webhook.telegram.TelegramEngine

/**
 * One card per destination a watch button press flows into. Each carries its
 * own on/off switch and a "which presses" selector; a press fans out to every
 * destination that is on and claims it (WebhookConfig.destinationsFor),
 * resolved at pickup — so a change here re-routes items still queued. The
 * pipeline's queue and the file-access grant live on the Status tab.
 */
@Composable
fun PebbleTab(activity: MainActivity) {
    activity.tick.intValue // subscribe
    val config = activity.config

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val telegramReady = TelegramEngine.stage == TelegramEngine.Stage.READY
        val telegramChatSet = config.telegramChatId != null
        FeatureCard(
            activity,
            title = "Telegram forwarding",
            switchTitle = "Forward recordings to Telegram",
            supporting = "Applies at pickup — recordings still queued follow the new setting.",
            featureKey = "telegram",
            status = when {
                !telegramReady -> "Not configured — not logged in to Telegram."
                !telegramChatSet -> "Not configured — no destination chat chosen."
                else -> "Configured — logged in, destination chat set."
            },
            statusWarn = !(telegramReady && telegramChatSet),
        ) {
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
                activity.toast("Saved — applies from the next forwarded message")
            }) { Text("Save signature") }
        }

        FeatureCard(
            activity,
            title = "Markdown notes",
            switchTitle = "Write notes to a Markdown file",
            supporting = "Applies at pickup — recordings still queued follow the new setting.",
            featureKey = "markdown",
            status = "Notes are written to ${WebhookConfig.NOTES_DIR}.",
        ) {
            ButtonAssignmentRow(activity, "markdown")
        }
    }
}

/**
 * A destination card whose settings collapse behind a Show/Hide link.
 * Flipping the switch resets the fold to match it (remember is keyed on
 * the switch): off collapses — the body is inert then, and hiding it says
 * so louder than dimming would — and on expands, ready to configure. The
 * [status] line stays visible either way, so a collapsed card still says
 * whether the destination is actually ready to receive.
 */
@Composable
private fun FeatureCard(
    activity: MainActivity,
    title: String,
    switchTitle: String,
    supporting: String?,
    featureKey: String,
    status: String? = null,
    statusWarn: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val config = activity.config
    val on = config.featureEnabled(featureKey)
    SectionCard(title) {
        SwitchRow(
            title = switchTitle,
            supporting = supporting,
            checked = on,
            onChange = { config.setFeatureEnabled(featureKey, it); activity.bump() },
        )
        status?.let { StatusText(it, warn = statusWarn) }
        var expanded by remember(on) { mutableStateOf(on) }
        if (expanded) content()
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide settings" else "Show settings")
        }
    }
}

/**
 * Which watch presses this destination claims. A press fans out to every
 * destination claiming it, so "Both" on two cards means both fire.
 */
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
