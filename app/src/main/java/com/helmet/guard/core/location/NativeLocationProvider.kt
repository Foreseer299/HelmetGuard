package com.helmet.guard.core.location

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
import com.helmet.guard.domain.LocationFix
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

sealed interface LocationOutcome {
    data class Success(val fix: LocationFix) : LocationOutcome
    data class Unavailable(val reason: String, val lastKnown: LocationFix? = null) : LocationOutcome
    data object PermissionMissing : LocationOutcome
    data object ProviderDisabled : LocationOutcome
}

/** Google Play Services is deliberately not required, making this work on HarmonyOS 4 devices. */
class NativeLocationProvider(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)

    fun hasForegroundPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isLocationEnabled(): Boolean {
        val locationManager = manager ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            runCatching {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun locate(timeoutMs: Long = 10_000): LocationOutcome {
        if (!hasForegroundPermission()) return LocationOutcome.PermissionMissing
        val locationManager = manager ?: return LocationOutcome.Unavailable("系统定位服务不可用")
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return LocationOutcome.ProviderDisabled

        val lastKnown = providers.mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .filter { it.isRecentAndValid() }
            .maxWithOrNull(compareBy<Location> { quality(it) }.thenBy { it.time })
        val fresh = lastKnown?.takeIf { System.currentTimeMillis() - it.time <= 30_000 && it.hasAccuracy() && it.accuracy <= 80f }
        if (fresh != null) return LocationOutcome.Success(fresh.toDomain(historical = true))

        val bestObserved = java.util.concurrent.atomic.AtomicReference<Location?>(null)
        val current = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val finished = AtomicBoolean(false)
                var best: Location? = null
                lateinit var listener: LocationListener

                fun finish(value: Location?) {
                    if (finished.compareAndSet(false, true)) {
                        runCatching { locationManager.removeUpdates(listener) }
                        if (continuation.isActive) continuation.resume(value)
                    }
                }

                listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (!location.isRecentAndValid()) return
                        if (best == null || quality(location) > quality(best!!)) {
                            best = location
                            bestObserved.set(location)
                        }
                        if (location.hasAccuracy() && location.accuracy <= 35f) finish(location)
                    }
                    override fun onProviderDisabled(provider: String) {
                        if (providers.none { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }) finish(best)
                    }
                    @Deprecated("Deprecated by Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                continuation.invokeOnCancellation {
                    if (finished.compareAndSet(false, true)) runCatching { locationManager.removeUpdates(listener) }
                }
                providers.forEach { provider ->
                    runCatching { locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
                }
            }
        }
        return when {
            current != null -> LocationOutcome.Success(current.toDomain(historical = false))
            bestObserved.get() != null -> LocationOutcome.Success(bestObserved.get()!!.toDomain(historical = false))
            lastKnown != null -> LocationOutcome.Success(lastKnown.toDomain(historical = true))
            else -> LocationOutcome.Unavailable("定位超时，短信将发送无坐标版本")
        }
    }

    private fun Location.isRecentAndValid(): Boolean =
        System.currentTimeMillis() - time in 0L..300_000L &&
            latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

    private fun quality(location: Location): Double {
        val agePenalty = ((System.currentTimeMillis() - location.time).coerceAtLeast(0) / 1_000.0).coerceAtMost(3_600.0)
        val accuracyPenalty = if (location.hasAccuracy()) location.accuracy.toDouble() else 1_000.0
        return -accuracyPenalty - agePenalty * 0.4
    }

    private fun Location.toDomain(historical: Boolean) = LocationFix(
        latitude, longitude,
        if (hasAccuracy()) accuracy else 9999f,
        time, provider ?: "unknown", historical
    )
}
