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
