package io.github.ealang.agentbasicsmcp.mcp.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import io.github.ealang.agentbasicsmcp.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** A folder the user has handed over, under the name agents see it by. */
data class FileFolder(val name: String, val treeUri: String, val writable: Boolean)

/** A file operation that did not happen, and why, in words for the caller. */
class FileProblem(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { BAD_REQUEST, NOT_FOUND, FORBIDDEN, CONFLICT, FAILED }
}

/** The sender of an upload stopped before the length it promised. */
class UploadCut(message: String) : IOException(message)

/** One file or folder as a listing shows it. */
class Entry(
    val name: String,
    val directory: Boolean,
    val size: Long?,
    val modified: Long?,
    /** What send_intent takes as data to open the file in another app. */
    val uri: Uri?,
)

/**
 * The virtual file system behind the file tools and the /files routes.
 *
 * A path is `<folder>/<sub/path>`. The folder is either [TMP], a cache
 * private to this app whose files are deleted a day after they were
 * written, or the name the user gave a folder picked in the app. Nothing
 * outside those is reachable: a path is walked one name at a time from
 * the folder's root, and `.` and `..` are refused.
 */
class FileStore(context: Context) {

    private val context = context.applicationContext
    private val config = Config(context)

    // ------------------------------------------------------------ reading

    /** The named folders for an empty path, else what one folder holds. */
    fun list(path: String, recursive: Boolean, maxEntries: Int = DEFAULT_LISTED): JSONObject = guarded {
        val parts = split(path)
        if (parts.isEmpty()) return@guarded folders()
        val t = target(parts)
        val dir = t.node() ?: throw notFound()
        if (!t.backend.entry(dir).directory) {
            throw FileProblem(FileProblem.Kind.BAD_REQUEST, "That path is a file, not a folder.")
        }
        val out = JSONArray()
        var truncated = false
        fun walk(node: Any, prefix: String) {
            for ((child, e) in t.backend.children(node).sortedBy { it.second.name.lowercase() }) {
                if (out.length() >= maxEntries) {
                    truncated = true
                    return
                }
                val childPath = "$prefix/${e.name}"
                out.put(json(e, childPath))
                if (recursive && e.directory) walk(child, childPath)
            }
        }
        walk(dir, parts.joinToString("/"))
        JSONObject().put("path", parts.joinToString("/")).put("entries", out).also {
            if (truncated) it.put("truncated", true)
        }
    }

    fun info(path: String): JSONObject = guarded {
        val parts = split(path)
        if (parts.isEmpty()) return@guarded folders()
        val t = target(parts)
        val node = t.node() ?: throw notFound()
        json(t.backend.entry(node), parts.joinToString("/"))
    }

    /** The file's listing entry and its bytes; the caller closes the stream. */
    fun open(path: String): Pair<Entry, InputStream> = guarded {
        val t = target(split(path))
        val node = t.node() ?: throw notFound()
        val e = t.backend.entry(node)
        if (e.directory) {
            throw FileProblem(FileProblem.Kind.BAD_REQUEST, "That path is a folder, not a file.")
        }
        e to t.backend.read(node)
    }

    // ------------------------------------------------------------ writing

    /** Writes [input] to [path], making missing folders and replacing a file already there. */
    fun put(path: String, input: InputStream): JSONObject = guarded {
        val parts = split(path)
        val t = target(parts).writable()
        if (t.segments.isEmpty()) {
            throw FileProblem(
                FileProblem.Kind.BAD_REQUEST,
                "The path needs a file name after the folder name.",
            )
        }
        val dir = makeDirectories(t.backend, t.segments.dropLast(1))
        val existing = t.backend.child(dir, t.segments.last())
        if (existing != null && t.backend.entry(existing).directory) {
            throw FileProblem(FileProblem.Kind.CONFLICT, "A folder already has that name.")
        }
        val node = existing ?: t.backend.create(dir, t.segments.last())
        try {
            t.backend.write(node).use { input.copyTo(it, COPY_BUFFER) }
        } catch (e: IOException) {
            // Half a file under the real name is worse than none.
            runCatching { t.backend.delete(node) }
            Log.w(TAG, "upload failed", e)
            throw when {
                e is UploadCut -> FileProblem(
                    FileProblem.Kind.BAD_REQUEST,
                    "The upload stopped before the whole file arrived.",
                )
                outOfSpace(e) -> FileProblem(
                    FileProblem.Kind.FAILED, "The phone is out of storage space.",
                )
                else -> e
            }
        }
        json(t.backend.entry(node), parts.joinToString("/"))
    }

    fun makeDirectory(path: String): Unit = guarded {
        val t = target(split(path)).writable()
        makeDirectories(t.backend, t.segments)
    }

    /** A file or a folder; a folder with anything in it only when [recursive]. */
    fun delete(path: String, recursive: Boolean): Unit = guarded {
        val t = target(split(path)).writable()
        if (t.segments.isEmpty()) throw topLevel()
        val node = t.node() ?: throw notFound()
        if (!recursive && t.backend.entry(node).directory &&
            t.backend.children(node).isNotEmpty()
        ) {
            throw FileProblem(
                FileProblem.Kind.CONFLICT,
                "The folder is not empty. Set recursive to delete it and everything in it.",
            )
        }
        t.backend.delete(node)
    }

    /** Rename, move, or both, within one named folder or across two. */
    fun move(from: String, to: String, overwrite: Boolean): Unit = guarded {
        val src = target(split(from)).writable()
        val dst = target(split(to)).writable()
        if (src.segments.isEmpty() || dst.segments.isEmpty()) throw topLevel()
        val node = src.node() ?: throw notFound()
        val sameFolder = src.name == dst.name
        if (sameFolder && src.segments == dst.segments) return@guarded
        if (sameFolder && dst.segments.size > src.segments.size &&
            dst.segments.subList(0, src.segments.size) == src.segments
        ) {
            throw FileProblem(
                FileProblem.Kind.BAD_REQUEST, "A folder cannot be moved into itself.",
            )
        }
        val srcDir = src.backend.walk(src.segments.dropLast(1)) ?: throw notFound()
        val dstDir = makeDirectories(dst.backend, dst.segments.dropLast(1))
        val newName = dst.segments.last()
        dst.backend.child(dstDir, newName)?.let { taken ->
            if (!overwrite) {
                throw FileProblem(
                    FileProblem.Kind.CONFLICT,
                    "Something already has the destination name. Set overwrite to replace a file.",
                )
            }
            if (dst.backend.entry(taken).directory) {
                throw FileProblem(
                    FileProblem.Kind.CONFLICT,
                    "A folder already has the destination name; overwrite replaces files only.",
                )
            }
            dst.backend.delete(taken)
        }
        if (sameFolder && src.backend.move(node, srcDir, dstDir, newName)) return@guarded
        copy(src.backend, node, dst.backend, dstDir, newName)
        src.backend.delete(node)
    }

    // ------------------------------------------------------------ helpers

    private class Target(val name: String, val backend: Backend, val segments: List<String>) {
        fun node(): Any? = backend.walk(segments)

        fun writable(): Target {
            if (!backend.writable) {
                throw FileProblem(
                    FileProblem.Kind.FORBIDDEN,
                    "That folder is read-only. The user sets this in the Agent Basics MCP app.",
                )
            }
            return this
        }
    }

    private fun target(parts: List<String>): Target {
        if (parts.isEmpty()) {
            throw FileProblem(
                FileProblem.Kind.BAD_REQUEST,
                "The path must start with a folder name. List the empty path to see them.",
            )
        }
        val name = parts.first()
        val backend = if (name == TMP) {
            TmpBackend(context)
        } else {
            val folder = config.fileFolders.firstOrNull { it.name == name }
                ?: throw FileProblem(
                    FileProblem.Kind.NOT_FOUND,
                    "No folder has that name. List the empty path to see the ones there are.",
                )
            TreeBackend(context, Uri.parse(folder.treeUri), folder.writable)
        }
        return Target(name, backend, parts.drop(1))
    }

    private fun split(path: String): List<String> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." || it.contains('\u0000') }) {
            throw FileProblem(
                FileProblem.Kind.BAD_REQUEST, "The path may not contain \".\" or \"..\" parts.",
            )
        }
        return parts
    }

    private fun makeDirectories(backend: Backend, segments: List<String>): Any {
        var node = backend.root()
        for (s in segments) {
            val child = backend.child(node, s)
            node = when {
                child == null -> backend.mkdir(node, s)
                backend.entry(child).directory -> child
                else -> throw FileProblem(
                    FileProblem.Kind.CONFLICT, "A file is in the way of that path.",
                )
            }
        }
        return node
    }

    private fun copy(src: Backend, node: Any, dst: Backend, dstDir: Any, name: String) {
        if (src.entry(node).directory) {
            val made = dst.mkdir(dstDir, name)
            for ((child, e) in src.children(node)) copy(src, child, dst, made, e.name)
        } else {
            val made = dst.create(dstDir, name)
            src.read(node).use { i -> dst.write(made).use { o -> i.copyTo(o, COPY_BUFFER) } }
        }
    }

    private fun folders(): JSONObject {
        val out = JSONArray().put(
            JSONObject().put("path", TMP).put("type", "directory").put("access", "read-write")
                .put("note", "Private to this app. Files are deleted a day after they are written."),
        )
        for (f in config.fileFolders) {
            out.put(
                JSONObject().put("path", f.name).put("type", "directory")
                    .put("access", if (f.writable) "read-write" else "read-only"),
            )
        }
        return JSONObject().put("path", "").put("entries", out)
    }

    private fun json(e: Entry, path: String): JSONObject {
        val o = JSONObject().put("path", path).put("type", if (e.directory) "directory" else "file")
        if (!e.directory) e.size?.let { o.put("size", it) }
        e.modified?.takeIf { it > 0 }?.let { o.put("modified", Instant.ofEpochMilli(it).toString()) }
        e.uri?.let { o.put("uri", it.toString()) }
        return o
    }

    private fun outOfSpace(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(8).any {
            (it is ErrnoException && it.errno == OsConstants.ENOSPC) ||
                it.message?.contains("ENOSPC") == true
        }

    private fun notFound() = FileProblem(FileProblem.Kind.NOT_FOUND, "Nothing is at that path.")

    private fun topLevel() = FileProblem(
        FileProblem.Kind.FORBIDDEN,
        "The named folders themselves cannot be moved or deleted, only what is in them.",
    )

    /**
     * Every operation runs through here: the cache is swept first, and what
     * a storage provider throws is turned into one of the fixed sentences.
     */
    private inline fun <T> guarded(block: () -> T): T {
        runCatching { TmpBackend(context).sweep() }
        try {
            return block()
        } catch (e: FileProblem) {
            throw e
        } catch (e: SecurityException) {
            Log.w(TAG, "access refused", e)
            throw FileProblem(
                FileProblem.Kind.FORBIDDEN,
                "Android refused access to that folder.",
            )
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "not found", e)
            throw notFound()
        } catch (e: Exception) {
            Log.w(TAG, "file operation failed", e)
            throw FileProblem(
                FileProblem.Kind.FAILED, "The phone could not complete the file operation.",
            )
        }
    }

    companion object {
        private const val TAG = "AgentBasicsMcpFiles"

        /** The folder that is always there. */
        const val TMP = "tmp"

        /** How many entries a listing returns unless the caller asks for more. */
        const val DEFAULT_LISTED = 1000
        private const val COPY_BUFFER = 64 * 1024

        fun authority(context: Context): String = "${context.packageName}.files"

        fun mimeFor(name: String): String =
            name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
                ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                ?: "application/octet-stream"

        /**
         * Whether [uri] is one of ours to lend: a file in the cache, or
         * anything under a folder the user picked. send_intent adds a read
         * grant for these, because without one the receiving app is refused
         * and a URI we returned would be useless.
         */
        fun lendable(context: Context, uri: Uri): Boolean {
            if (uri.scheme != "content") return false
            if (uri.authority == authority(context)) return true
            val tree = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?: return false
            return context.contentResolver.persistedUriPermissions.any {
                it.isReadPermission && it.uri.authority == uri.authority &&
                    runCatching { DocumentsContract.getTreeDocumentId(it.uri) }.getOrNull() == tree
            }
        }
    }
}

/**
 * One storage behind a named folder. A node is whatever the storage uses
 * to point at a file; only the backend that made it looks inside.
 */
private interface Backend {
    val writable: Boolean
    fun root(): Any
    fun child(dir: Any, name: String): Any?
    fun entry(node: Any): Entry
    fun children(dir: Any): List<Pair<Any, Entry>>
    fun mkdir(dir: Any, name: String): Any
    fun create(dir: Any, name: String): Any
    fun write(node: Any): OutputStream
    fun read(node: Any): InputStream
    /** A folder goes with everything in it. */
    fun delete(node: Any)
    /** False when this storage cannot move in place and the caller should copy. */
    fun move(node: Any, fromDir: Any, toDir: Any, newName: String): Boolean
}

private fun Backend.walk(segments: List<String>): Any? {
    var node = root()
    for (s in segments) node = child(node, s) ?: return null
    return node
}

/** The app's own cache directory, lent out through the FileProvider. */
private class TmpBackend(private val context: Context) : Backend {

    private val dir = File(context.cacheDir, DIR).apply { mkdirs() }

    override val writable = true
    override fun root(): Any = dir
    override fun child(dir: Any, name: String): Any? = File(dir as File, name).takeIf { it.exists() }

    override fun entry(node: Any): Entry {
        val f = node as File
        if (!f.exists()) throw FileNotFoundException()
        return Entry(
            f.name,
            f.isDirectory,
            f.length(),
            f.lastModified(),
            if (f.isFile) FileProvider.getUriForFile(context, FileStore.authority(context), f) else null,
        )
    }

    override fun children(dir: Any): List<Pair<Any, Entry>> =
        ((dir as File).listFiles() ?: emptyArray()).map { it to entry(it) }

    override fun mkdir(dir: Any, name: String): Any =
        File(dir as File, name).also { if (!it.mkdir()) throw IOException("mkdir") }

    override fun create(dir: Any, name: String): Any =
        File(dir as File, name).also { it.createNewFile() }

    override fun write(node: Any): OutputStream = (node as File).outputStream()
    override fun read(node: Any): InputStream = (node as File).inputStream()

    override fun delete(node: Any) {
        if (!(node as File).deleteRecursively()) throw IOException("delete")
    }

    override fun move(node: Any, fromDir: Any, toDir: Any, newName: String): Boolean =
        (node as File).renameTo(File(toDir as File, newName))

    /** Files a day old go, and then any folder that left empty and is as old. */
    fun sweep() {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        dir.walkBottomUp().forEach { f ->
            if (f == dir || f.lastModified() >= cutoff) return@forEach
            if (f.isFile || f.list().isNullOrEmpty()) f.delete()
        }
    }

    companion object {
        /** Must match the cache-path in res/xml/file_paths.xml. */
        private const val DIR = "files-tmp"
        private const val KEEP_MS = 24L * 60 * 60 * 1000
    }
}

/**
 * A folder the user picked with the system picker, reached through the
 * Storage Access Framework. A node is a document id.
 */
private class TreeBackend(
    context: Context,
    private val tree: Uri,
    override val writable: Boolean,
) : Backend {

    private val resolver = context.contentResolver

    private fun doc(id: Any): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id as String)

    private fun rows(uri: Uri): List<Pair<Any, Entry>> {
        val out = ArrayList<Pair<Any, Entry>>()
        resolver.query(uri, COLUMNS, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                out.add(
                    id to Entry(
                        c.getString(1).orEmpty(),
                        c.getString(2) == Document.MIME_TYPE_DIR,
                        if (c.isNull(3)) null else c.getLong(3),
                        if (c.isNull(4)) null else c.getLong(4),
                        doc(id),
                    ),
                )
            }
        }
        return out
    }

    private fun made(uri: Uri?): Any =
        DocumentsContract.getDocumentId(uri ?: throw IOException("the provider made nothing"))

    override fun root(): Any = DocumentsContract.getTreeDocumentId(tree)

    override fun children(dir: Any): List<Pair<Any, Entry>> =
        rows(DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir as String))

    override fun child(dir: Any, name: String): Any? {
        val all = children(dir)
        // Shared storage ignores case, so a second pass matches the way it would.
        return (all.firstOrNull { it.second.name == name }
            ?: all.firstOrNull { it.second.name.equals(name, ignoreCase = true) })?.first
    }

    override fun entry(node: Any): Entry =
        rows(doc(node)).firstOrNull()?.second ?: throw FileNotFoundException()

    override fun mkdir(dir: Any, name: String): Any =
        made(DocumentsContract.createDocument(resolver, doc(dir), Document.MIME_TYPE_DIR, name))

    override fun create(dir: Any, name: String): Any =
        made(DocumentsContract.createDocument(resolver, doc(dir), FileStore.mimeFor(name), name))

    override fun write(node: Any): OutputStream =
        resolver.openOutputStream(doc(node), "wt") ?: throw IOException("no stream")

    override fun read(node: Any): InputStream =
        resolver.openInputStream(doc(node)) ?: throw IOException("no stream")

    override fun delete(node: Any) {
        if (!DocumentsContract.deleteDocument(resolver, doc(node))) throw IOException("delete")
    }

    override fun move(node: Any, fromDir: Any, toDir: Any, newName: String): Boolean {
        var uri = doc(node)
        if (fromDir != toDir) {
            // Not every provider can move; one that cannot says so by throwing.
            uri = try {
                DocumentsContract.moveDocument(resolver, uri, doc(fromDir), doc(toDir))
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return false
        }
        val current = rows(uri).firstOrNull()?.second?.name
        if (current != newName) {
            DocumentsContract.renameDocument(resolver, uri, newName)
                ?: throw IOException("rename")
        }
        return true
    }

    companion object {
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
