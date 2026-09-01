package dev.ealang.phonemcp.telegram

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.ealang.phonemcp.Config
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The long-lived TDLib keeper (docs/webhook-pipeline.md §10): the client and
 * its auth state machine. Step 1 of the stage-4 plan — log in, connect,
 * resolve the target chat. The send surface, the network-change kick and the
 * reconcile sweep come in the later steps; the queue is never known here.
 *
 * A process-wide object rather than a per-owner instance: TDLib allows one
 * client per database directory, and both the activity (the auth UI) and the
 * service (delivery, later) need the same one. Trimmed from
 * agent-voice-gateway's TelegramTdlibClient, which is where the non-obvious
 * moves (LoadChats priming the GetChat cache, the supergroup id offset) were
 * learned.
 *
 * Threading: TDLib delivers results on its own daemon thread; every handler
 * here posts to main, all mutable state is main-thread-confined, and public
 * methods must be called from the main thread.
 */
object TelegramEngine {

    enum class Stage { OFF, STARTING, PHONE, CODE, PASSWORD, READY, CLOSED }

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var client: Client? = null
    private var authState: TdApi.AuthorizationState? = null
    private var connectionLabel: String? = null
    private var accountLabel: String? = null

    /**
     * The last thing that went wrong, shown under the status line until the
     * next successful step. TDLib's auth errors ("PHONE_CODE_INVALID") are
     * the actionable feedback during login, so they surface here verbatim.
     */
    private var lastProblem: String? = null

    /** Repaint hook for whoever is on screen; always invoked on main. */
    var onChanged: () -> Unit = {}

    /**
     * TDLib confirmed a send: the message reached the server. The service
     * points this at the queue so the confirmed item can be deleted — until
     * then the item is the visible record that confirmation is owed
     * (docs/webhook-pipeline.md §7). Invoked on main with the *temporary*
     * message id, the one recorded at handoff.
     */
    var onSendSucceeded: (oldMessageId: Long) -> Unit = {}

    val stage: Stage
        get() = when (authState) {
            null -> if (client == null) Stage.OFF else Stage.STARTING
            is TdApi.AuthorizationStateWaitPhoneNumber -> Stage.PHONE
            is TdApi.AuthorizationStateWaitCode -> Stage.CODE
            is TdApi.AuthorizationStateWaitPassword -> Stage.PASSWORD
            is TdApi.AuthorizationStateReady -> Stage.READY
            is TdApi.AuthorizationStateClosed -> Stage.CLOSED
            else -> Stage.STARTING
        }

    /**
     * Bring the client up if the credentials exist; a no-op when it already
     * runs. Called from both the activity and the service — whichever gets
     * there first wins, the other finds it running. Changing the api id/hash
     * after the client exists needs a process restart (TDLib takes its
     * parameters exactly once); saving new values while running just leaves
     * the old client in place until then.
     */
    fun ensureStarted(context: Context) {
        appContext = context.applicationContext
        if (client != null) return
        val config = Config(context)
        if (config.telegramApiId == null || config.telegramApiHash == null) {
            changed()
            return
        }
        configureLogging(context)
        client = Client.create({ update -> main.post { handleUpdate(update) } }, null, null)
        changed()
    }

    /**
     * One input field serves the whole login: what the value means is decided
     * by which state TDLib is waiting in, exactly as the gateway does it.
     */
    fun submitAuthInput(raw: String) {
        val value = raw.trim()
        val tdClient = client ?: return
        if (value.isBlank()) return
        val request: TdApi.Function<*> = when (authState) {
            is TdApi.AuthorizationStateWaitPhoneNumber ->
                TdApi.SetAuthenticationPhoneNumber(value, null)
            is TdApi.AuthorizationStateWaitCode -> TdApi.CheckAuthenticationCode(value)
            is TdApi.AuthorizationStateWaitPassword -> TdApi.CheckAuthenticationPassword(value)
            else -> {
                lastProblem = "no login step is waiting for input"
                changed()
                return
            }
        }
        lastProblem = null
        tdClient.send(request) { result ->
            if (result is TdApi.Error) {
                main.post {
                    lastProblem = result.message
                    changed()
                }
            }
        }
    }

    /**
     * Resolve a manually-entered numeric chat id to its title, as the UI's
     * "does this id reach the right place" check. Local cache first (free —
     * LoadChats primed it at login); on a miss, the server request that suits
     * the id's kind. The id forms: positive = a user, below the supergroup
     * offset = a supergroup/channel, other negatives = a basic group, which
     * only GetChat can answer.
     */
    fun checkChat(chatId: Long, onResult: (String) -> Unit) {
        val tdClient = client
        if (tdClient == null || stage != Stage.READY) {
            onResult("FAILED: Telegram is not connected yet.")
            return
        }
        val report = Client.ResultHandler { result ->
            main.post {
                onResult(
                    when (result) {
                        is TdApi.Chat ->
                            "Resolved: ${result.title.ifBlank { "(untitled chat)" }}"
                        is TdApi.Error -> "FAILED: ${result.message}"
                        else -> "FAILED: unexpected TDLib response"
                    },
                )
            }
        }
        tdClient.send(TdApi.GetChat(chatId)) { cached ->
            when {
                cached is TdApi.Chat -> report.onResult(cached)
                chatId < -SUPERGROUP_ID_OFFSET ->
                    tdClient.send(
                        TdApi.CreateSupergroupChat(-chatId - SUPERGROUP_ID_OFFSET, false),
                        report,
                    )
                chatId > 0 -> tdClient.send(TdApi.CreatePrivateChat(chatId, false), report)
                else -> report.onResult(cached) // GetChat's own error is the answer
            }
        }
    }

    /**
     * Hand one message to TDLib. Unlike the rest of this object it may be
     * called from any thread — the pipeline worker is the caller — so it hops
     * to main itself. The outcome reported is TDLib's *synchronous* answer:
     * a message id means accepted into TDLib's own persistent outbox (which
     * survives process death and retries internally), not delivered.
     * Delivery is announced later through [onSendSucceeded]. The callback
     * runs on an arbitrary thread.
     */
    fun sendText(chatId: Long, text: String, onOutcome: (messageId: Long?, problem: String?) -> Unit) {
        main.post {
            val tdClient = client
            if (tdClient == null || stage != Stage.READY) {
                onOutcome(null, "Telegram is not connected")
                return@post
            }
            val content = TdApi.InputMessageText(TdApi.FormattedText(text, null), null, true)
            tdClient.send(TdApi.SendMessage(chatId, null, null, null, null, content)) { result ->
                when (result) {
                    is TdApi.Message -> onOutcome(result.id, null)
                    is TdApi.Error -> onOutcome(null, result.message)
                    else -> onOutcome(null, "unexpected TDLib response")
                }
            }
        }
    }

    /** One line for the UI, composed from auth stage, connection, and account. */
    fun statusLine(): String {
        val problem = lastProblem?.let { "\nProblem: $it" } ?: ""
        return when (stage) {
            Stage.OFF -> "Not configured — save an API ID and hash below."
            Stage.STARTING -> "Starting…$problem"
            Stage.PHONE -> "Enter your phone number, with country code (+1…).$problem"
            Stage.CODE -> "Telegram sent a login code — enter it below.$problem"
            Stage.PASSWORD -> "Enter your two-step verification password.$problem"
            Stage.CLOSED -> "Telegram client closed; restart the app.$problem"
            Stage.READY -> {
                val who = accountLabel?.let { "Connected as $it" } ?: "Connected"
                val conn = connectionLabel
                if (conn != null && conn != "connected") "$who — $conn$problem" else "$who$problem"
            }
        }
    }

    // ------------------------------ internals ------------------------------

    private fun handleUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> handleAuth(update.authorizationState)
            is TdApi.UpdateMessageSendSucceeded -> onSendSucceeded(update.oldMessageId)
            is TdApi.UpdateConnectionState -> {
                connectionLabel = when (update.state) {
                    is TdApi.ConnectionStateReady -> "connected"
                    is TdApi.ConnectionStateConnecting -> "connecting"
                    is TdApi.ConnectionStateConnectingToProxy -> "connecting (proxy)"
                    is TdApi.ConnectionStateUpdating -> "updating"
                    is TdApi.ConnectionStateWaitingForNetwork -> "waiting for network"
                    else -> "unknown"
                }
                changed()
            }
        }
    }

    private fun handleAuth(state: TdApi.AuthorizationState) {
        authState = state
        val tdClient = client ?: return
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val context = appContext ?: return
                val config = Config(context)
                val base = File(context.filesDir, "tdlib")
                val request = TdApi.SetTdlibParameters().apply {
                    useTestDc = false
                    databaseDirectory = File(base, "db").apply { mkdirs() }.absolutePath
                    filesDirectory = File(base, "files").apply { mkdirs() }.absolutePath
                    databaseEncryptionKey = config.telegramDbKey
                    useFileDatabase = true
                    useChatInfoDatabase = true
                    // The message database is what makes GetMessage answer for
                    // sends that outlived a restart — the reconcile sweep
                    // (step 3) depends on it, so it is on from the start.
                    useMessageDatabase = true
                    useSecretChats = false
                    apiId = config.telegramApiId ?: 0
                    apiHash = config.telegramApiHash.orEmpty()
                    systemLanguageCode = "en-US"
                    deviceModel = Build.MODEL ?: "Android"
                    systemVersion = "Android ${Build.VERSION.RELEASE ?: "unknown"}"
                    applicationVersion = "0.1"
                }
                tdClient.send(request) { result ->
                    if (result is TdApi.Error) {
                        main.post {
                            lastProblem = result.message
                            changed()
                        }
                    }
                }
            }

            is TdApi.AuthorizationStateReady -> {
                lastProblem = null
                tdClient.send(TdApi.GetMe()) { result ->
                    if (result is TdApi.User) {
                        main.post {
                            accountLabel = listOf(result.firstName, result.lastName)
                                .filter { it.isNotBlank() }
                                .joinToString(" ")
                                .ifBlank { result.phoneNumber.ifBlank { "user ${result.id}" } }
                            changed()
                        }
                    }
                }
                // Prime the local chat cache so a typed-in numeric id can
                // resolve without a server round-trip. Error 404 just means
                // "no more chats" and is fine (gateway finding).
                tdClient.send(TdApi.LoadChats(TdApi.ChatListMain(), CHAT_LIST_LOAD_LIMIT)) { result ->
                    if (result is TdApi.Error && result.code != 404) {
                        Log.w(TAG, "LoadChats failed: ${result.message}")
                    }
                }
            }

            is TdApi.AuthorizationStateClosed -> client = null

            else -> Unit
        }
        changed()
    }

    private fun changed() = onChanged()

    /**
     * Quiet TDLib down and point its (verbose, occasionally useful) log at a
     * file before the first client exists. Once per process.
     */
    private fun configureLogging(context: Context) {
        if (!loggingConfigured.compareAndSet(false, true)) return
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(1))
            val logFile = File(context.filesDir, "tdlib.log")
            Client.execute(TdApi.SetLogStream(TdApi.LogStreamFile(logFile.absolutePath, 1 shl 20, false)))
        } catch (e: Exception) {
            Log.e(TAG, "Could not configure TDLib logging", e)
        }
    }

    private const val TAG = "phone-mcp"
    private val loggingConfigured = AtomicBoolean(false)

    /** How many chats to pull into the cache at login; the gateway's number. */
    private const val CHAT_LIST_LOAD_LIMIT = 500

    /**
     * TDLib computes a supergroup/channel chat id as
     * -1_000_000_000_000 - supergroupId; anything below the offset is one.
     */
    private const val SUPERGROUP_ID_OFFSET = 1_000_000_000_000L
}
