package dev.ealang.agentbasicsmcp

import android.content.Context
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * One IPv4 address the phone holds right now, labelled by what the network
 * behind it is. [kind] is one of the KIND_ constants; [iface] is the kernel
 * name (wlan0, tun0, rmnet_data1), shown for the user who knows what it means.
 */
data class Endpoint(val kind: String, val address: String, val iface: String)

/**
 * The addresses a server on this phone can bind, and what each one is.
 *
 * Two sources, merged. ConnectivityManager knows what a network *is* — Wi-Fi,
 * mobile data, a VPN — because every app-usable network registers with it
 * along with its transport. That is how a VPN gets labelled without the app
 * knowing any vendor: Tailscale, WireGuard and the rest all show up as
 * TRANSPORT_VPN with their address. Which app runs the tunnel is deliberately
 * hidden from other apps by the platform, so the label stops at "VPN".
 *
 * The kernel's interface table fills in whatever ConnectivityManager does not
 * explain. The one that matters is the phone's own hotspot: clients on it
 * reach the phone through an interface that is not a network from the
 * phone's point of view, so it is absent from the first list.
 *
 * IPv4 only. The server binds the IPv4 wildcard in "everything" mode, and a
 * list with every link-local IPv6 address in it would be twice as long and
 * no more useful. A typed IPv6 literal is still accepted by Save.
 */
object Networks {

    private const val TAG = "AgentBasicsMcpNetworks"

    /** The explicit spelling of "every IPv4 interface". */
    const val ALL_IPV4 = "0.0.0.0"

    const val KIND_VPN = "VPN"
    const val KIND_WIFI = "Wi-Fi"
    const val KIND_ETHERNET = "Ethernet"
    const val KIND_MOBILE = "Mobile data"
    const val KIND_OTHER = "Other"

    /** Display order: the ones a user wants to bind first. */
    private val ORDER = listOf(KIND_VPN, KIND_WIFI, KIND_ETHERNET, KIND_OTHER, KIND_MOBILE)

    /** Every bindable IPv4 address right now, most likely first. */
    fun live(context: Context): List<Endpoint> {
        val found = LinkedHashMap<String, Endpoint>()
        fromConnectivity(context).forEach { found.putIfAbsent(it.address, it) }
        fromKernel().forEach { found.putIfAbsent(it.address, it) }
        return found.values.sortedBy { ORDER.indexOf(it.kind) }
    }

    /** The live endpoint holding [address], or null if the phone has no such address now. */
    fun describe(context: Context, address: String): Endpoint? =
        live(context).firstOrNull { it.address == address }

    /**
     * What a fresh install binds: the VPN if there is exactly one (a tailnet
     * address is the whole point of the app), else Wi-Fi, else every IPv4
     * interface. Written into the field where the user can see and change it,
     * not applied as a hidden rule.
     */
    fun defaultBind(context: Context): String {
        val live = live(context)
        val vpns = live.filter { it.kind == KIND_VPN }
        if (vpns.size == 1) return vpns.single().address
        return live.firstOrNull { it.kind == KIND_WIFI }?.address ?: ALL_IPV4
    }

    /** A literal IPv4 or IPv6 address; names are refused because they would resolve, not bind. */
    fun isLiteral(address: String): Boolean = InetAddresses.isNumericAddress(address)

    /** The host part of a URL: IPv6 literals go in brackets. */
    fun hostForUrl(address: String): String =
        if (address.contains(':')) "[$address]" else address

    private fun fromConnectivity(context: Context): List<Endpoint> = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION") // The callback API gives the same networks, one at a time.
        cm.allNetworks.flatMap { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@flatMap emptyList()
            val link = cm.getLinkProperties(network) ?: return@flatMap emptyList()
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> KIND_VPN
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> KIND_WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> KIND_ETHERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> KIND_MOBILE
                else -> KIND_OTHER
            }
            link.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .map { Endpoint(kind, it.hostAddress ?: return@map null, link.interfaceName ?: "") }
                .filterNotNull()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not list networks", e)
        emptyList()
    }

    private fun fromKernel(): List<Endpoint> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { addr -> addr.hostAddress?.let { Endpoint(KIND_OTHER, it, iface.name) } }
            }
    } catch (e: Exception) {
        Log.w(TAG, "Could not enumerate network interfaces", e)
        emptyList()
    }
}
