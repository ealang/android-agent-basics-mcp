package dev.ealang.phonemcp.mcp.generic

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.util.Log
import org.json.JSONObject

/**
 * Experimental: the generic ContentProvider surface — the read side of the
 * intent tools. Intents are fire-and-forget; providers are the one generic
 * mechanism Android offers for *reading* another app's state (and writing
 * it, through insert/update, not built here yet).
 *
 * Same doctrine as IntentExplorer: raw, advanced-tier, no guards beyond
 * what Android itself enforces. A query against a guarded authority fails
 * with the platform's own SecurityException, reported as-is — that message
 * names the permission the caller lacks, which is exactly what an
 * exploring agent needs to know.
 */
object ProviderExplorer {

    private const val TAG = "phone-mcp"
    private const val DEFAULT_LIMIT = 50
    private const val MAX_CELL = 300

    // ---------------------------------------------------------------- list

    /**
     * Every exported provider on the device, grouped by package, with its
     * authority and permission guards. One call answers "what can be read
     * at all"; the guards say what each attempt will hit. An authority
     * with no read guard is open to any app — those are the interesting
     * ones — but path-level permissions can still fence off parts of it,
     * so open-looking authorities may refuse specific tables.
     */
    fun list(context: Context): String {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_PROVIDERS)
        val sections = mutableListOf<Pair<String, String>>() // label, body
        var total = 0
        for (info in packages) {
            val providers = info.providers?.filter { it.exported } ?: continue
            if (providers.isEmpty()) continue
            val label = info.applicationInfo?.loadLabel(pm)?.toString() ?: info.packageName
            val body = StringBuilder("$label (${info.packageName})\n")
            for (p in providers) {
                total++
                body.append(describeProvider(p, indent = "  "))
            }
            sections.add(label.lowercase() to body.toString())
        }
        sections.sortBy { it.first }
        return "$total exported providers on the device. Query one with " +
            "provider_query uri=content://<authority>/<path> — paths are not " +
            "discoverable from outside, so try the bare authority, then " +
            "documented or guessed paths.\n\n" +
            sections.joinToString("\n") { it.second }
    }

    /** One provider's line(s): authority, guards, grant flag. */
    fun describeProvider(p: ProviderInfo, indent: String): String {
        val sb = StringBuilder()
        // authority can be a semicolon-separated list.
        sb.append("$indent${p.authority}\n")
        val guards = mutableListOf<String>()
        p.readPermission?.let { guards.add("read: $it") }
        p.writePermission?.let { guards.add("write: $it") }
        if (guards.isEmpty()) guards.add("no permission required")
        if (p.grantUriPermissions) guards.add("grants uri permissions")
        sb.append("$indent  ${guards.joinToString("; ")}\n")
        p.pathPermissions?.forEach { pp ->
            val path = pp.path ?: "(pattern)"
            val r = pp.readPermission?.let { "read: $it" }
            val w = pp.writePermission?.let { "write: $it" }
            sb.append("$indent  path $path — ${listOfNotNull(r, w).joinToString("; ")}\n")
        }
        return sb.toString()
    }

    // --------------------------------------------------------------- query

    /**
     * A ContentResolver query, rows rendered as text. Errors come back
     * raw: SecurityException names the missing permission, an unknown
     * path is usually an IllegalArgumentException from the provider's
     * UriMatcher, and a null cursor is the provider declining silently.
     * All three mean different next moves for an exploring agent, so
     * none of them is collapsed.
     */
    fun query(context: Context, args: JSONObject): String {
        val uriStr = args.optString("uri").trim()
        if (uriStr.isEmpty()) return list(context)
        val uri = try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            return "FAILED: could not parse \"$uriStr\" as a URI."
        }
        if (uri.scheme != "content") {
            return "FAILED: provider URIs use the content:// scheme, e.g. content://<authority>/<path>."
        }
        val projection = args.optJSONArray("projection")?.let { a ->
            Array(a.length()) { a.getString(it) }
        }
        val selection = args.optString("selection").takeIf { it.isNotBlank() }
        val selectionArgs = args.optJSONArray("selection_args")?.let { a ->
            Array(a.length()) { a.getString(it) }
        }
        val sortOrder = args.optString("sort_order").takeIf { it.isNotBlank() }
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, 500)

        val cursor = try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
        } catch (e: Exception) {
            // Raw by design: the exception type and message are the finding.
            Log.w(TAG, "provider: query failed for $uri", e)
            return "FAILED: ${e.javaClass.simpleName}: ${e.message}"
        } ?: return "FAILED: the provider returned no cursor for $uri — it does not " +
            "recognise this path (or declines it). Other paths under the same " +
            "authority may still answer."

        cursor.use { c ->
            val cols = c.columnNames
            if (cols.isEmpty()) return "Query answered but the cursor has no columns ($uri)."
            val sb = StringBuilder()
            var shown = 0
            while (shown < limit && c.moveToNext()) {
                sb.append("row ${c.position}:\n")
                for (i in cols.indices) {
                    sb.append("  ${cols[i]} = ${cellValue(c, i)}\n")
                }
                shown++
            }
            val totalKnown = c.count // may force full enumeration; fine for a scan tool
            Log.i(TAG, "provider: $uri → $totalKnown rows, showed $shown")
            val head = "$uri: $totalKnown row(s), columns [${cols.joinToString(", ")}]" +
                (if (totalKnown > shown) ", showing first $shown (raise limit for more)" else "")
            return if (totalKnown == 0) "$head." else "$head\n$sb"
        }
    }

    // ---------------------------------------------------------------- call

    /**
     * ContentResolver.call — the RPC half of the provider interface, and
     * the command channel most modern apps actually use: one method name,
     * one optional string arg, one Bundle each way. Addressed by bare
     * authority rather than a URI because that is the platform's own
     * asymmetry: call() does not route by path, and accepting a URI would
     * imply path semantics that don't exist.
     *
     * Errors come back raw, same doctrine as query, because each names a
     * different next move: SecurityException = locked, and with what
     * permission; IllegalArgumentException = no provider at that
     * authority (the resolver's own message); an exception out of the
     * provider's call() = the method exists enough to crash. A null
     * Bundle is not an error — it is the normal answer of a command with
     * nothing to report, and only the target's documentation can say
     * whether it also means "unknown method ignored".
     */
    fun call(context: Context, args: JSONObject): String {
        val authority = args.optString("authority").trim()
            .removePrefix("content://").trimEnd('/')
        if (authority.isEmpty()) return "FAILED: authority is required, e.g. com.example.app.provider."
        if (authority.contains('/')) {
            return "FAILED: authority must be bare (no path) — call() does not route by path."
        }
        val method = args.optString("method").trim()
        if (method.isEmpty()) return "FAILED: method is required."
        val arg = args.optString("arg").takeIf { it.isNotBlank() }
        val extras = args.optJSONArray("extras")?.let { a ->
            val (bundle, problem) = IntentExplorer.parseExtras(a)
            bundle ?: return "FAILED: $problem"
        }

        val uri = Uri.parse("content://$authority")
        val result = try {
            context.contentResolver.call(uri, method, arg, extras)
        } catch (e: Exception) {
            Log.w(TAG, "provider: call $method on $authority failed", e)
            return "FAILED: ${e.javaClass.simpleName}: ${e.message}"
        }
        Log.i(TAG, "provider: call $method on $authority → ${result?.keySet()?.size ?: "null"} keys")
        if (result == null) {
            return "Called $method on $authority. The provider returned no Bundle — " +
                "normal for a command with nothing to report, but some providers " +
                "also answer unknown methods this way."
        }
        if (result.isEmpty) return "Called $method on $authority. The provider returned an empty Bundle."
        val sb = StringBuilder("Called $method on $authority. Result:\n")
        for (key in result.keySet().sorted()) {
            sb.append("  $key = ${bundleValue(result, key)}\n")
        }
        return sb.toString().trimEnd()
    }

    @Suppress("DEPRECATION") // Bundle.get: the typed accessors need the type known up front.
    private fun bundleValue(b: android.os.Bundle, key: String): String =
        when (val v = b.get(key)) {
            null -> "null"
            is ByteArray -> "<byte[], ${v.size} bytes>"
            is IntArray -> v.joinToString(", ", "[", "]")
            is LongArray -> v.joinToString(", ", "[", "]")
            is Array<*> -> v.joinToString(", ", "[", "]")
            is android.os.Bundle -> {
                val inner = v.keySet().sorted().joinToString("; ") { k -> "$k = ${bundleValue(v, k)}" }
                "{$inner}"
            }
            else -> {
                val s = v.toString()
                if (s.length > MAX_CELL) s.take(MAX_CELL) + "… (${s.length} chars)" else s
            }
        }

    private fun cellValue(c: Cursor, i: Int): String = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> "null"
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i).toString()
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toString()
        Cursor.FIELD_TYPE_BLOB -> "<blob, ${c.getBlob(i)?.size ?: 0} bytes>"
        else -> {
            val s = c.getString(i) ?: "null"
            if (s.length > MAX_CELL) s.take(MAX_CELL) + "… (${s.length} chars)" else s
        }
    }
}
