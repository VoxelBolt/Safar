package com.safar.app.data.location

import com.safar.app.domain.model.GeoPoint
import kotlinx.coroutines.flow.Flow

/** Platform-neutral location boundary. */
interface LocationProvider {
    val locations: Flow<GeoPoint>
    suspend fun start()
    suspend fun stop()
}

/** A location sample with the timestamp supplied by Android's location provider. */
data class LocationSample(
    val point: GeoPoint,
    val timestampEpochMs: Long,
)
