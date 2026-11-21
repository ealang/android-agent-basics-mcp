package io.github.ealang.agentbasicsmcp.mcp.files

import android.content.Context
import io.github.ealang.agentbasicsmcp.mcp.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * File management over the folders the user has named, plus tmp. These
 * tools carry names and listings only; file content travels over the
 * /files routes, as raw bytes, because a tool argument would have to be
 * text written out by the calling model.
 */
object FileTools {

    const val FEATURE = "files"

    private const val PATH_HELP =
        "A folder name then the path inside it, e.g. tmp/report.pdf."

    fun specs(context: Context): List<ToolSpec> {
        val store = FileStore(context)

        fun path(description: String = PATH_HELP) =
            JSONObject().put("type", "string").put("description", description)

        fun spec(
            name: String,
            description: String,
            properties: JSONObject,
            required: List<String>,
            handler: (JSONObject) -> String,
        ) = ToolSpec(
            name = name,
            featureKey = FEATURE,
            definition = JSONObject()
                .put("name", name)
                .put("description", description)
                .put(
                    "inputSchema",
                    JSONObject().put("type", "object").put("properties", properties)
                        .put("required", JSONArray(required)),
                ),
        ) { args ->
            try {
                handler(args) to false
            } catch (e: FileProblem) {
                "FAILED: ${e.message}" to true
            }
        }

        return listOf(
            spec(
                "file_list",
                "list a folder on the phone as JSON: each entry's path, type, size, modified time, and the content:// uri that send_intent takes as data to open the file in another app. An empty path lists the top-level folders: tmp, a private cache that is always there and whose files are deleted after a day, plus the folders the user has shared, each read-write or read-only. File content does not go through MCP: upload with HTTP PUT /files/<path> on this server (raw bytes as the body, same Authorization header; missing folders are made and an existing file is replaced) and download with GET /files/<path>. In those URLs write a + as %2B.",
                JSONObject()
                    .put("path", path("Folder to list; empty or omitted for the top level."))
                    .put("recursive", JSONObject().put("type", "boolean").put("description", "Also list everything below, default false."))
                    .put("max_entries", JSONObject().put("type", "integer").put("description", "Stop after this many entries, default ${FileStore.DEFAULT_LISTED}. A listing cut short says truncated.")),
                emptyList(),
            ) { args ->
                store.list(
                    args.optString("path"),
                    args.optBoolean("recursive", false),
                    args.optInt("max_entries", FileStore.DEFAULT_LISTED).coerceAtLeast(1),
                ).toString()
            },
            spec(
                "file_info",
                "one file or folder as JSON: path, type, size, modified time and its content:// uri.",
                JSONObject().put("path", path()),
                listOf("path"),
            ) { args -> store.info(args.optString("path")).toString() },
            spec(
                "file_move",
                "rename or move a file or folder, within one top-level folder or between two. Missing destination folders are made.",
                JSONObject()
                    .put("from", path())
                    .put("to", path("The full new path, name included."))
                    .put("overwrite", JSONObject().put("type", "boolean").put("description", "Replace a file already at the destination, default false.")),
                listOf("from", "to"),
            ) { args ->
                store.move(args.optString("from"), args.optString("to"), args.optBoolean("overwrite", false))
                "Moved."
            },
            spec(
                "file_delete",
                "delete a file or an empty folder; a folder with anything in it needs recursive.",
                JSONObject()
                    .put("path", path())
                    .put("recursive", JSONObject().put("type", "boolean").put("description", "Delete a folder together with everything in it, default false.")),
                listOf("path"),
            ) { args ->
                store.delete(args.optString("path"), args.optBoolean("recursive", false))
                "Deleted."
            },
            spec(
                "file_make_directory",
                "create a folder, and any missing folders above it. Does nothing if it is already there.",
                JSONObject().put("path", path()),
                listOf("path"),
            ) { args ->
                store.makeDirectory(args.optString("path"))
                "The folder is there."
            },
        )
    }
}
