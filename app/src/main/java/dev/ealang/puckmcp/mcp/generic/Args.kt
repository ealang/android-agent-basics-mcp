package dev.ealang.puckmcp.mcp.generic

import org.json.JSONArray
import org.json.JSONObject

/**
 * Argument-shape checks shared by the generic tools.
 *
 * The generic tools execute what they are told without second-guessing
 * the target, but an argument of the wrong shape cannot be executed at
 * all, and org.json's opt* readers answer null for one — which turned an
 * extras block sent as an object into a bare send that reported success.
 * So an argument that is present but the wrong shape is a hard error
 * naming the shape wanted. Absent stays absent.
 */
object Args {
    /**
     * The array argument [name]: (array, null) when present, (null, null)
     * when absent, (null, problem) when present but not an array. [shape]
     * finishes the sentence "must be a list of …".
     */
    fun array(args: JSONObject, name: String, shape: String): Pair<JSONArray?, String?> {
        if (!args.has(name) || args.isNull(name)) return null to null
        val a = args.optJSONArray(name) ?: return null to "$name must be a list of $shape."
        return a to null
    }

    /** Short printable form of an extra's value for result strings and logs. */
    fun brief(v: Any?, max: Int = 48): String {
        val s = when (v) {
            null -> "null"
            is IntArray -> v.joinToString(",", "[", "]")
            is LongArray -> v.joinToString(",", "[", "]")
            is Array<*> -> v.joinToString(",", "[", "]")
            is Collection<*> -> v.joinToString(",", "[", "]")
            else -> v.toString()
        }
        return if (s.length <= max) s else s.substring(0, max - 1) + "…"
    }
}
