package com.safar.app.ui.screens

import android.os.SystemClock
import com.safar.app.domain.model.PassengerLocation

/**
 * A passenger marker is visible only for the currently selected route and
 * for at most one minute after the last realtime update was received locally.
 * A monotonic clock avoids false expiry when the device clock changes.
 */
internal object PassengerLocationVisibility {
    const val MAX_AGE_MS = 60_000L

    fun isFresh(receivedAtElapsedMs: Long, nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean =
        nowElapsedMs - receivedAtElapsedMs in 0L..MAX_AGE_MS

    fun visibleOnRoute(
        locations: List<PassengerLocation>,
        routeId: String?,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): List<PassengerLocation> {
        if (routeId == null) return emptyList()
        return locations.filter { passenger ->
            passenger.routeId == routeId &&
                isFresh(passenger.receivedAtElapsedMs, nowElapsedMs) &&
                passenger.latitude.isFinite() &&
                passenger.longitude.isFinite()
        }
    }
}
