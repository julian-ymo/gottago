package com.gottago.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Uses Android's own LocationManager (GPS + network), so no Google Play services are needed. */
object LocationHelper {

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasFine(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** GPS needs precise location; with "approximate" only, use the network provider. */
    private fun providers(ctx: Context, lm: LocationManager): List<String> {
        val all = if (hasFine(ctx)) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        else listOf(LocationManager.NETWORK_PROVIDER)
        return all.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    }

    /** Instant: the phone's last known position (no waiting), if not older than maxAgeMs. */
    @SuppressLint("MissingPermission")
    fun lastKnown(ctx: Context, maxAgeMs: Long = 30 * 60_000L): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val names = mutableListOf(LocationManager.NETWORK_PROVIDER)
        if (hasFine(ctx)) names += listOf(LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val best = names.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        return best?.takeIf { System.currentTimeMillis() - it.time <= maxAgeMs }
    }

    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context, timeoutMs: Long = 10_000): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val ps = providers(ctx, lm)
        val last = ps.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 60_000) return last
        if (ps.isEmpty()) return last

        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location> { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("Deprecated in Android")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                ps.forEach {
                    runCatching { lm.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) }
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
            }
        }
        return fresh ?: last
    }

    /** Live updates while walking. Call stop() with the returned listener. */
    @SuppressLint("MissingPermission")
    fun start(ctx: Context, onUpdate: (Location) -> Unit): LocationListener? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = onUpdate(location)
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        providers(ctx, lm).forEach {
            runCatching { lm.requestLocationUpdates(it, 3000L, 3f, listener, Looper.getMainLooper()) }
        }
        return listener
    }

    fun stop(ctx: Context, listener: LocationListener?) {
        if (listener == null) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.removeUpdates(listener)
    }
}
