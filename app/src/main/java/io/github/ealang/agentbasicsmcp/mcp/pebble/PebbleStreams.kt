package io.github.ealang.agentbasicsmcp.mcp.pebble

import android.content.Context
import io.github.ealang.agentbasicsmcp.mcp.Preflight
import org.json.JSONArray
import org.json.JSONObject

/**
 * Streams to watchapps over PebbleKit 2: the send and receive tools' own
 * machinery, held open for one caller instead of set up per call.
 *
 * A stream is both the reader of [WatchInbox] and the sender, and the
 * latest caller has each: connecting ends a read that is waiting and a send
 * under way, and a later receive call, send call or stream ends this one.
 */
class PebbleStreams(private val context: Context) : WatchStreams {

    override fun open(app: String, maxAgeMs: Long): WatchStreams.Opening {
        val uuid = Watch.resolve(app)
            ?: return WatchStreams.Opening.Refused("\"$app\" is not a UUID.")
        Preflight.watch(context)?.let { return WatchStreams.Opening.Refused(it) }

        // Claimed before the launch, so the app's opening is among the
        // events, and whatever is too old is gone before it.
        val claim = WatchInbox.claim()
        val backlog = WatchInbox.take(uuid, maxAgeMs, 0, claim)
        // Losing the sender's place ends the stream as losing the
        // reader's does: giving up the claim ends its read.
        val session = PebbleKit2.session(context, uuid) { WatchInbox.release(claim) }
        return when (val launched = session.open()) {
            PebbleKit2.Launch.Running -> WatchStreams.Opening.Ready(Stream(uuid, claim, backlog, session))
            is PebbleKit2.Launch.Failed -> {
                session.close()
                WatchInbox.release(claim)
                WatchStreams.Opening.Refused(launched.why)
            }
        }
    }

    private class Stream(
        private val uuid: String,
        private val claim: Long,
        /** What was waiting at the start; handed out first. Only [next] touches it. */
        private var backlog: JSONObject?,
        private val session: PebbleKit2.Session,
    ) : WatchStream {

        override fun send(tuples: JSONArray): String? {
            val wire = when (val checked = Watch.toWire(tuples)) {
                is Watch.Wire.Ready -> checked.tuples
                is Watch.Wire.Unsendable ->
                    return "not sent: ${checked.why}. Every tuple needs key, type and value."
            }
            return when (val result = session.send(wire)) {
                PebbleKit2.Send.Acknowledged -> null
                is PebbleKit2.Send.Failed -> result.why
                PebbleKit2.Send.NotOpen ->
                    "the app is not open on the watch, or it does not name this app as " +
                        "its Android companion. The message was not delivered."
            }
        }

        override fun next(): JSONObject? {
            backlog?.let {
                backlog = null
                if (worthSending(it)) return it
            }
            while (true) {
                val batch = WatchInbox.take(uuid, EVERYTHING_MS, WAIT_MS, claim) ?: return null
                if (worthSending(batch)) return batch
            }
        }

        private fun worthSending(batch: JSONObject) =
            batch.getJSONArray("events").length() > 0 || batch.getInt("dropped") > 0

        override fun close() {
            WatchInbox.release(claim)
            session.close()
        }
    }

    private companion object {
        /** Nothing that arrives while the stream is open is too old for it. */
        const val EVERYTHING_MS = PebbleTools.MAX_AGE_CEILING_S * 1000

        /** How long one wait on the inbox lasts before the next begins. */
        const val WAIT_MS = 600_000L
    }
}
