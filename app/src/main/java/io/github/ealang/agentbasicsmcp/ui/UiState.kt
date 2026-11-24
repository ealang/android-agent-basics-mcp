package io.github.ealang.agentbasicsmcp.ui

import io.github.ealang.agentbasicsmcp.Endpoint
import io.github.ealang.agentbasicsmcp.LOG_DIALOG_LINES
import io.github.ealang.agentbasicsmcp.Logs
import io.github.ealang.agentbasicsmcp.MainActivity
import io.github.ealang.agentbasicsmcp.McpService
import io.github.ealang.agentbasicsmcp.Networks
import io.github.ealang.agentbasicsmcp.SecretStore
import io.github.ealang.agentbasicsmcp.mcp.files.FileFolder

/**
 * Everything the page shows, read in one go. The page is drawn from one of
 * these and from nothing else: a composable takes the values it shows as
 * arguments, so Compose re-runs exactly the parts whose values changed.
 * Nothing on the page reads Config, the service or a grant for itself.
 */
data class UiState(
    /** The switch: the server is meant to be on. */
    val enabled: Boolean,
    /** The socket is up. */
    val listening: Boolean,
    val port: Int,
    /** As saved; blank means every interface. */
    val bindAddress: String,
    /** The addresses the phone holds right now. */
    val endpoints: List<Endpoint>,
    /** Null until the Keystore has been opened. */
    val token: String?,
    /** Keys of the tool rows that are switched on. */
    val featuresOn: Set<String>,
    /** Of [ALL_GRANTS], the ones not held. */
    val missingGrants: Set<Grant>,
    val folders: List<FileFolder>,
    val requests: Long,
    val rejected: Long,
    val failures: Long,
    val logTail: List<String>,
)

/** Reads are cheap and never block; called on the main thread. */
fun readUiState(activity: MainActivity): UiState {
    val config = activity.config
    return UiState(
        enabled = config.enabled,
        listening = McpService.running,
        port = config.port,
        bindAddress = config.bindAddress,
        endpoints = Networks.live(activity),
        token = if (SecretStore.ready) config.token else null,
        featuresOn = FEATURES.map { it.key }.filterTo(HashSet()) { config.featureEnabled(it) },
        missingGrants = ALL_GRANTS.filterNotTo(HashSet()) { it.granted(activity) },
        folders = config.fileFolders,
        requests = Logs.mcp.count("requests"),
        rejected = Logs.mcp.count("rejected"),
        failures = Logs.mcp.count("failures"),
        logTail = Logs.mcp.tail(LOG_DIALOG_LINES),
    )
}

/**
 * What the page can ask for. The activity implements it; composables are
 * handed this rather than the activity, so they can act but not read.
 */
interface PageActions {
    /** Read the state again; for the page's own timer. */
    fun refresh()
    fun setServerEnabled(on: Boolean)
    fun saveNetwork(portText: String, bindText: String)
    fun regenerateToken()
    fun copyToken()
    fun copyMcpUrl()
    fun openServiceNotificationSettings()
    fun setFeatureEnabled(key: String, on: Boolean)
    fun openGrant(grant: Grant)
    fun pickFileFolder()
    fun addFileFolder(name: String, tree: android.net.Uri)
    fun dropPickedFolder()
    fun removeFileFolder(folder: FileFolder)
    fun setFolderWritable(folder: FileFolder, on: Boolean)
    fun clearLog()
    fun copyLog(text: String)
}
