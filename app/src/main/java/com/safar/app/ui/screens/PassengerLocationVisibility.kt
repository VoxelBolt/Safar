package com.safar.app.ui.screens

import com.safar.app.domain.model.PassengerLocation

/**
 * A passenger marker is visible only for the currently selected route and
 * for at most one minute after the last realtime update was received locally.
 * Using receive time avoids false expiry caused by device clock differences.
 */
internal object PassengerLocationVisibility {
    const val MAX_AGE_MS = 60_000L

    fun isFresh(receivedAtEpochMs: Long, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        nowEpochMs - receivedAtEpochMs in 0L..MAX_AGE_MS

    fun visibleOnRoute(
        locations: List<PassengerLocation>,
        routeId: String?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<PassengerLocation> {
        if (routeId == null) return emptyList()
        return locations.filter { passenger ->
            passenger.routeId == routeId &&
                isFresh(passenger.receivedAtEpochMs, nowEpochMs) &&
                passenger.latitude.isFinite() &&
                passenger.longitude.isFinite()
        }
    }
}
