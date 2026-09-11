package dev.ealang.phonemcp.mcp

import org.json.JSONObject

/**
 * One value carrying everything the MCP server needs about one tool, so the
 * schema shown in tools/list and the handler behind tools/call live in the
 * same object and cannot drift apart.
 *
 * Registration is compile-time list concatenation, nothing dynamic: each tool
 * package exposes `specs(context)` (a function rather than a static list only
 * because handlers need the Context) and McpServer holds their sum, indexed
 * by name. Adding a tool is adding one entry to one list.
 */
class ToolSpec(
    val name: String,
    /**
     * The Config `feature_*` key that switches this tool off, or null for the
     * handful that are always on (get_location, find_my_phone,
     * silence_phone) — capabilities of the phone itself, not of an app
     * anyone would want to fence off.
     */
    val featureKey: String?,
    /** The tools/list schema entry, name and inputSchema included. */
    val definition: JSONObject,
    /** args → (text, isError). May throw; the server turns that into FAILED. */
    val handler: (JSONObject) -> Pair<String, Boolean>,
)

/**
 * A preflight reason turned into a tool failure, or null to carry on.
 *
 * Written as `blocked(...) ?: run { ... }` at the top of a handler, so the
 * check and the work it guards read in the order they happen and no handler
 * can quietly skip one.
 */
internal fun blocked(reason: String?): Pair<String, Boolean>? =
    reason?.let { "FAILED: $it" to true }
