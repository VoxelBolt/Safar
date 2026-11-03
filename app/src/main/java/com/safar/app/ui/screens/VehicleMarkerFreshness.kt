package com.safar.app.ui.screens

import com.safar.app.domain.model.Vehicle

/** Tracks how long a marker has gone without a successful server snapshot or live update. */
internal class VehicleMarkerFreshness(
    private val maxAgeMs: Long = MAX_AGE_MS,
) {
    private val lastSeenAtMs = mutableMapOf<String, Long>()

    fun observeSnapshot(vehicles: List<Vehicle>, nowElapsedMs: Long) {
        val activeIds = vehicles.asSequence()
            .filter { it.active && it.currentLocation != null }
            .map { it.id }
            .toSet()
        lastSeenAtMs.keys.retainAll(activeIds)
        activeIds.forEach { lastSeenAtMs[it] = nowElapsedMs }
    }

    fun observeRealtime(vehicleId: String, nowElapsedMs: Long) {
        if (vehicleId in lastSeenAtMs) lastSeenAtMs[vehicleId] = nowElapsedMs
    }

    fun remove(vehicleId: String) {
        lastSeenAtMs.remove(vehicleId)
    }

    fun retainFresh(vehicles: List<Vehicle>, nowElapsedMs: Long): List<Vehicle> {
        val fresh = vehicles.filter { vehicle ->
            val seenAt = lastSeenAtMs[vehicle.id]
            vehicle.active && vehicle.currentLocation != null && seenAt != null &&
                (nowElapsedMs - seenAt) in 0L..maxAgeMs
        }
        val activeIds = fresh.mapTo(mutableSetOf()) { it.id }
        lastSeenAtMs.keys.retainAll(activeIds)
        return fresh
    }

    fun clear() = lastSeenAtMs.clear()

    companion object {
        const val MAX_AGE_MS = 65_000L
    }
}
