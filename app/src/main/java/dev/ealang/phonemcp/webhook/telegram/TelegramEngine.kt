package dev.ealang.phonemcp.webhook.telegram

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.ealang.phonemcp.BuildConfig
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
 * moves (the network-type kick, the two-layer chat search) were learned —
 * though only the server layer of that search survived here; see
 * [searchChats] for why this app needs no local chat cache at all.
 *
 * Threading: TDLib delivers results on its own daemon thread; every handler
 * here posts to main, all mutable state is main-thread-confined, and public
 * methods must be called from the main thread.
 */
object TelegramEngine {

    enum class Stage { OFF, STARTING, PHONE, CODE, PASSWORD, READY, CLOSED }

    /**
     * The classification the UI paints: three states,
     * not seven. Everything short of a logged-in client — no credentials,
     * login mid-flight, session closed or revoked — collapses to
     * NOT_AUTHENTICATED, because to the user they are one condition:
     * delivery is down until they act on this card. OFFLINE is the one
     * non-failure — nothing to fix, TDLib reconnects on its own (helped
     * along by the network kick below). No active probe anywhere: TDLib
     * pushes every auth and connection transition, including a server-side
     * logout the moment it reconnects and finds the session dead, so this
     * is always current for free. The cost of deriving rather than testing:
     * a brief NOT_AUTHENTICATED flash at client boot before the stored
     * session reports Ready.
     */
    enum class Health { HEALTHY, OFFLINE, NOT_AUTHENTICATED }

    val health: Health
        get() = when {
            stage != Stage.READY -> Health.NOT_AUTHENTICATED
            waitingForNetwork -> Health.OFFLINE
            else -> Health.HEALTHY
        }

    /** Main-thread, like all state here; from UpdateConnectionState. */
    private var waitingForNetwork = false

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var client: Client? = null
    private var authState: TdApi.AuthorizationState? = null
    private var connectionLabel: String? = null
    private var accountLabel: String? = null

    /** Who we are logged in as, once GetMe has answered; null before that. */
    val account: String? get() = accountLabel

    /**
     * The last thing that went wrong, shown under the status line until the
     * next successful step. TDLib's auth errors ("PHONE_CODE_INVALID") are
     * the actionable feedback during login, so they surface here verbatim.
     */
    private var lastProblem: String? = null

    /** Repaint hook for whoever is on screen; always invoked on main. */
    var onChanged: () -> Unit = {}

    /**
     * "Something about delivery changed" — a send succeeded or failed, or the
     * connection came back. Deliberately parameter-free: the service maps it
     * to worker.wake(), and the drain *discovers* what changed by reconciling
     * the handed-off items. Carrying details here would just build a second,
     * racier channel for facts the reconcile already reads from TDLib.
     * Invoked on main.
     */
    var onDeliveryEvent: () -> Unit = {}

    /** What [checkSend] found out about a handed-off message. */
    sealed interface SendState {
        /** The recorded id no longer answers, or answers with no sending
         * state: the send completed and TDLib reassigned the id. */
        data object Delivered : SendState

        /** Still in TDLib's outbox; nothing to do. */
        data object Pending : SendState

        /** TDLib gave up on it. [canRetry] is TDLib's own verdict on whether
         * ResendMessages would be accepted. */
        data class Failed(val reason: String, val canRetry: Boolean) : SendState

        /** Not connected, or an answer that fits no case: act on nothing. */
        data object Unknown : SendState
    }

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

    /** Whether this build carries Telegram credentials at all. */
    val configured: Boolean
        get() = BuildConfig.TELEGRAM_API_ID > 0 && BuildConfig.TELEGRAM_API_HASH.isNotEmpty()

    /**
     * Bring the client up if this build has credentials; a no-op when it
     * already runs. Called from both the activity and the service — whichever
     * gets there first wins, the other finds it running.
     */
    fun ensureStarted(context: Context) {
        appContext = context.applicationContext
        if (client != null) return
        if (!configured) {
            changed()
            return
        }
        configureLogging(context)
        client = Client.create({ update -> main.post { handleUpdate(update) } }, null, null)
        registerNetworkKick(context.applicationContext)
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
     * End the session, server-side and locally — TDLib deletes its database
     * and closes. Closed is no longer an end state (see [handleAuth]): a
     * fresh client comes up immediately and parks at WaitPhoneNumber, so
     * the next login needs no app restart.
     */
    fun logOut() {
        val tdClient = client ?: return
        lastProblem = null
        tdClient.send(TdApi.LogOut()) { result ->
            if (result is TdApi.Error) {
                main.post {
                    lastProblem = result.message
                    changed()
                }
            }
        }
    }

    /** One search result: what the picker shows, and what it stores. */
    data class ChatHit(val id: Long, val title: String)

    /**
     * Find chats by name, for the destination picker.
     *
     * Server-side only, deliberately. The obvious
     * alternative — LoadChats at login, then GetChats for a recents list —
     * was removed with the manual id field it existed to serve. TDLib's own
     * docs push you further than that: GetChats is "for informational
     * purposes only. Use loadChats and updates processing instead to
     * maintain chat lists in a consistent state", i.e. a correct recents
     * list means keeping a chat-list model fed by UpdateChatPosition. That
     * is a permanent subscription in service of a screen opened once.
     *
     * SearchChatsOnServer matches "the title and username of already known
     * chats" — never message text, so a busy inbox cannot bury the group
     * being looked for. "Already known" is the one real limit: a public
     * channel never opened is not in the account's chat list and will not
     * be found. Open it once in Telegram and it is.
     *
     * Two round trips, because every chat-returning call yields ids alone:
     * the search, then GetChat per id. Those land out of order, so titles
     * are collected by id and re-read in the order the search returned —
     * that order is Telegram's own ranking and is worth preserving. A chat
     * whose GetChat fails is dropped rather than shown untitled; the search
     * just put it in TDLib's cache, so failure here means something is
     * wrong enough that offering it as a destination would be a lie.
     */
    fun searchChats(query: String, onResult: (Result<List<ChatHit>>) -> Unit) {
        val tdClient = client
        if (tdClient == null || stage != Stage.READY) {
            onResult(Result.failure(IllegalStateException("Telegram is not connected.")))
            return
        }
        tdClient.send(TdApi.SearchChatsOnServer(query, SEARCH_RESULT_LIMIT)) { found ->
            if (found !is TdApi.Chats) {
                val why = (found as? TdApi.Error)?.message ?: "unexpected TDLib response"
                main.post { onResult(Result.failure(IllegalStateException(why))) }
                return@send
            }
            val ids = found.chatIds
            if (ids.isEmpty()) {
                main.post { onResult(Result.success(emptyList())) }
                return@send
            }
            val titles = HashMap<Long, String>()
            var outstanding = ids.size
            for (id in ids) {
                tdClient.send(TdApi.GetChat(id)) { chat ->
                    main.post {
                        if (chat is TdApi.Chat) {
                            titles[id] = chat.title.ifBlank { "(untitled chat)" }
                        }
                        if (--outstanding == 0) {
                            onResult(Result.success(ids.toList().mapNotNull { got ->
                                titles[got]?.let { ChatHit(got, it) }
                            }))
                        }
                    }
                }
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

    /**
     * Ask TDLib what became of a handed-off send, by the message id recorded
     * at handoff. Reads the local message database (why useMessageDatabase is
     * on), so it answers offline and across restarts. The id semantics do the
     * heavy lifting: on success TDLib *reassigns* the id, so "not found" is
     * the delivered case — it is exactly what a confirmation we slept through
     * leaves behind. Any-thread, like [sendText]; callback on an arbitrary
     * thread.
     */
    fun checkSend(chatId: Long, messageId: Long, onOutcome: (SendState) -> Unit) {
        main.post {
            val tdClient = client
            if (tdClient == null || stage != Stage.READY) {
                onOutcome(SendState.Unknown)
                return@post
            }
            tdClient.send(TdApi.GetMessage(chatId, messageId)) { result ->
                onOutcome(
                    when {
                        result is TdApi.Message -> when (val s = result.sendingState) {
                            null -> SendState.Delivered
                            is TdApi.MessageSendingStatePending -> SendState.Pending
                            is TdApi.MessageSendingStateFailed ->
                                SendState.Failed(s.error.message, s.canRetry)
                            else -> SendState.Unknown
                        }
                        result is TdApi.Error && result.code == 404 -> SendState.Delivered
                        else -> SendState.Unknown
                    },
                )
            }
        }
    }

    /**
     * Ask TDLib to retry a failed send from its own outbox. Preferred over
     * composing a fresh SendMessage because TDLib retries the *same* message —
     * it cannot duplicate. Reports the replacement message id (resending
     * assigns a new one) for the caller to re-record.
     */
    fun resend(chatId: Long, messageId: Long, onOutcome: (newMessageId: Long?, problem: String?) -> Unit) {
        main.post {
            val tdClient = client
            if (tdClient == null || stage != Stage.READY) {
                onOutcome(null, "Telegram is not connected")
                return@post
            }
            tdClient.send(TdApi.ResendMessages(chatId, longArrayOf(messageId), null, 0)) { result ->
                when {
                    result is TdApi.Messages && result.messages.isNotEmpty() ->
                        onOutcome(result.messages.first().id, null)
                    result is TdApi.Error -> onOutcome(null, result.message)
                    else -> onOutcome(null, "unexpected TDLib response")
                }
            }
        }
    }

    /**
     * The login dialog's line: which step is waiting, and TDLib's own
     * complaint when one is rejected. Step-level prose belongs only here
     * (2026-09-19) — the card outside states the condition and points at
     * the dialog, rather than repeating the instruction the dialog is
     * about to give.
     */
    fun statusLine(): String {
        val problem = lastProblem?.let { "\nProblem: $it" } ?: ""
        return when (stage) {
            // Only reachable in a build made without TELEGRAM_API_ID and
            // TELEGRAM_API_HASH: nothing the user can fix on the phone.
            Stage.OFF -> "This build has no Telegram credentials."
            Stage.STARTING -> "Starting…$problem"
            Stage.PHONE -> "Enter your phone number, with country code (+1…).$problem"
            Stage.CODE -> "Telegram sent a login code — enter it below.$problem"
            Stage.PASSWORD -> "Enter your two-step verification password.$problem"
            // Normally invisible — Closed restarts the client at once — so
            // seeing it means the restart could not run.
            Stage.CLOSED -> "Telegram client closed; reopen the app.$problem"
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
            is TdApi.UpdateMessageSendSucceeded -> onDeliveryEvent()
            is TdApi.UpdateMessageSendFailed -> onDeliveryEvent()
            is TdApi.UpdateConnectionState -> {
                waitingForNetwork = update.state is TdApi.ConnectionStateWaitingForNetwork
                connectionLabel = when (update.state) {
                    is TdApi.ConnectionStateReady -> "connected"
                    is TdApi.ConnectionStateConnecting -> "connecting"
                    is TdApi.ConnectionStateConnectingToProxy -> "connecting (proxy)"
                    is TdApi.ConnectionStateUpdating -> "updating"
                    is TdApi.ConnectionStateWaitingForNetwork -> "waiting for network"
                    else -> "unknown"
                }
                // The connection coming back is the moment blocked work can
                // move — items waiting on "not connected" should not sit out
                // the retry gate's backoff.
                if (update.state is TdApi.ConnectionStateReady) onDeliveryEvent()
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
                    apiId = BuildConfig.TELEGRAM_API_ID
                    apiHash = BuildConfig.TELEGRAM_API_HASH
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
                // Nothing primes the chat cache any more (2026-09-19). A
                // LoadChats(main, 500) used to run here so a hand-typed id
                // could resolve locally; the picker replaced the typing,
                // and it asks the server directly. See [searchChats].
            }

            is TdApi.AuthorizationStateClosed -> {
                // Not an end state (2026-09-19, for the logout flow): tear
                // down and bring a fresh client straight up, which lands at
                // WaitPhoneNumber ready for the next login. Before this,
                // Closed meant "restart the app".
                client = null
                authState = null
                accountLabel = null
                appContext?.let { ensureStarted(it) }
                return
            }

            else -> Unit
        }
        changed()
    }

    private fun changed() = onChanged()

    /**
     * Tell TDLib about every network transition. Left to itself TDLib sits on
     * a dead socket for minutes after a Wi-Fi→LTE switch; SetNetworkType is
     * the documented kick that makes it drop and redial immediately. The
     * single hardest-won lesson from agent-voice-gateway's "not always the
     * best at staying online" era. Once per process; deduped by type so the
     * chatty onCapabilitiesChanged doesn't spam TDLib.
     */
    private fun registerNetworkKick(context: Context) {
        if (!networkKickRegistered.compareAndSet(false, true)) return
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: android.net.NetworkCapabilities,
            ) {
                val type: TdApi.NetworkType = when {
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ->
                        TdApi.NetworkTypeWiFi()
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ->
                        TdApi.NetworkTypeMobile()
                    else -> TdApi.NetworkTypeOther()
                }
                main.post { pushNetworkType(type) }
            }

            override fun onLost(network: android.net.Network) {
                main.post { pushNetworkType(TdApi.NetworkTypeNone()) }
            }
        })
    }

    /** Main thread. */
    private fun pushNetworkType(type: TdApi.NetworkType) {
        if (type.javaClass == lastNetworkType) return
        lastNetworkType = type.javaClass
        client?.send(TdApi.SetNetworkType(type)) { result ->
            if (result is TdApi.Error) {
                Log.w(TAG, "SetNetworkType failed: ${result.message}")
            }
        }
    }

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
    private val networkKickRegistered = AtomicBoolean(false)

    /** Main-thread; the dedupe for [pushNetworkType]. */
    private var lastNetworkType: Class<*>? = null

    /**
     * Enough that a reasonable name finds its chat, few enough that the
     * follow-up GetChat per result stays cheap. Nobody scrolls a picker
     * looking for something they can retype more precisely.
     */
    private const val SEARCH_RESULT_LIMIT = 30
}
