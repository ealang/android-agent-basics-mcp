package io.github.ealang.agentbasicsmcp.mcp

import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The connection the current thread is answering, for a tool that holds its
 * request open and needs to know whether anyone is still there to answer.
 *
 * The server runs each connection on its own thread, start to finish, so
 * the thread is the handle: [McpServer] records the socket here before the
 * request is read and clears it after the response is written.
 */
object ClientConnection {

    private val socket = ThreadLocal<Socket>()

    internal fun <T> serving(client: Socket, block: () -> T): T {
        socket.set(client)
        try {
            return block()
        } finally {
            socket.remove()
        }
    }

    /**
     * False once the client has closed its end or the connection has failed.
     *
     * A client that has sent its request has nothing more to say, so a read
     * either finds the end of the stream — it hung up — or finds nothing,
     * which is what a client still waiting looks like. True proves less
     * than it sounds: a client that vanished without closing, behind a dead
     * network, is indistinguishable from one waiting quietly.
     *
     * True on a thread that is not answering a connection: nothing to lose.
     */
    fun alive(): Boolean {
        val client = socket.get() ?: return true
        return try {
            val readTimeout = client.soTimeout
            client.soTimeout = PROBE_MS
            try {
                client.getInputStream().read() != -1
            } catch (e: SocketTimeoutException) {
                true
            } finally {
                client.soTimeout = readTimeout
            }
        } catch (e: IOException) {
            false
        }
    }

    /** Long enough to be a real read, short enough not to be a wait. */
    private const val PROBE_MS = 1
}
