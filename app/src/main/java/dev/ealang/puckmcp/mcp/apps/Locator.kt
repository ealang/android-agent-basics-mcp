package dev.ealang.puckmcp.mcp.apps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Where the phone is.
 *
 * Uses the framework LocationManager rather than Play Services, so the app
 * keeps its single dependency. Fused location would give better fixes indoors;
 * for answering "what's near me" this is enough.
 */
object Locator {

    private const val TAG = "PuckMcpLocator"
    private const val FRESH_FIX_TIMEOUT_MS = 20_000L

    val PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    /**
     * "Allow all the time" — what lets a fix arrive when the service was
     * started from the background and never got the location foreground type.
     * Without it that state returns no fix at all, silently.
     */
    const val BACKGROUND_PERMISSION = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    sealed interface Result {
        data class Fix(val location: Location, val address: String?) : Result
        /** Permission and radios are fine, but nothing produced a fix in time. */
        data object NoFix : Result
    }

    fun hasPermission(context: Context): Boolean = PERMISSIONS.any {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /** Whether any provider is switched on, so a fix is possible at all. */
    fun servicesEnabled(context: Context): Boolean =
        context.getSystemService(LocationManager::class.java)
            ?.getProviders(true)?.isNotEmpty() == true

    /** Only meaningful once [hasPermission] is true; it is a widening of it. */
    fun hasBackgroundPermission(context: Context): Boolean =
        context.checkSelfPermission(BACKGROUND_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Returns a cached fix if it is younger than [maxAgeSeconds], otherwise
     * waits for a new one. A stale cached fix is still returned if the wait
     * fails — being told roughly where the user was ten minutes ago beats
     * being told nothing, and the caller is given the age to judge with.
     */
    fun locate(context: Context, maxAgeSeconds: Long): Result {
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return Result.NoFix
        val providers = manager.getProviders(true)
        if (providers.isEmpty()) return Result.NoFix

        val cached = bestCached(manager, providers)
        if (cached != null && ageSeconds(cached) <= maxAgeSeconds) {
            return Result.Fix(cached, describe(context, cached))
        }

        val fresh = awaitFix(manager, providers)
        val best = fresh ?: cached ?: return Result.NoFix
        return Result.Fix(best, describe(context, best))
    }

    fun ageSeconds(location: Location): Long =
        ((System.currentTimeMillis() - location.time) / 1000).coerceAtLeast(0)

    private fun bestCached(manager: LocationManager, providers: List<String>): Location? = try {
        providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
    } catch (e: SecurityException) {
        null
    }

    /**
     * Asks every enabled provider at once and takes the first answer. Callbacks
     * land on the main looper; this blocks a server worker thread, never that one.
     */
    private fun awaitFix(manager: LocationManager, providers: List<String>): Location? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Location?>(null)

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (result.compareAndSet(null, location)) latch.countDown()
            }

            // Required on API < 30; absent here the platform can throw on older devices.
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        return try {
            providers.forEach {
                manager.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper())
            }
            latch.await(FRESH_FIX_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            result.get()
        } catch (e: SecurityException) {
            Log.w(TAG, "Lost location permission mid-request", e)
            null
        } finally {
            try {
                manager.removeUpdates(listener)
            } catch (e: SecurityException) {
                // Nothing to do; the listener goes away with the process.
            }
        }
    }

    /** Best effort: needs the network, and the platform geocoder may be absent. */
    private fun describe(context: Context, location: Location): String? = try {
        if (!Geocoder.isPresent()) {
            null
        } else {
            @Suppress("DEPRECATION")
            Geocoder(context)
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.let { address ->
                    (0..address.maxAddressLineIndex)
                        .mapNotNull { address.getAddressLine(it) }
                        .joinToString(", ")
                        .ifBlank { null }
                }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Reverse geocoding failed", e)
        null
    }
}
