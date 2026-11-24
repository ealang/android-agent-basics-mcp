package io.github.ealang.agentbasicsmcp.mcp.pebble

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable

/** Where the server's /pebblekit2/send_receive route gets its streams. */
interface WatchStreams {
    /**
     * Opens a stream to the watchapp [app], starting the app on the watch
     * unless it is already running. Events already waiting are kept only if
     * no older than [maxAgeMs]. Blocks for as long as the launch takes.
     */
    fun open(app: String, maxAgeMs: Long): Opening

    sealed interface Opening {
        class Ready(val stream: WatchStream) : Opening

        /** [why] is one sentence for the caller. */
        class Refused(val why: String) : Opening
    }
}

/**
 * One caller's two-way link to one watchapp, held until closed. [send] and
 * [next] are called from different threads; both block.
 */
interface WatchStream : Closeable {
    /** Sends one AppMessage, once. Null when the watch acknowledged it, else why not. */
    fun send(tuples: JSONArray): String?

    /**
     * Waits for the watchapp's next events and returns them in the form
     * pebblekit2_receive_messages does. Null when a newer caller has taken
     * the stream's place, after which there is nothing more to read.
     */
    fun next(): JSONObject?
}
