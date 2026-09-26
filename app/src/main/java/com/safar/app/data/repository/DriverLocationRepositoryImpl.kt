package com.safar.app.data.repository

import com.safar.app.data.remote.SafarApi
import com.safar.app.domain.model.VehicleLocation
import com.safar.app.domain.repository.DriverLocationRepository

class DriverLocationRepositoryImpl(
    private val api: SafarApi,
) : DriverLocationRepository {
    override suspend fun submitLocation(
        latitude: Double,
        longitude: Double,
        recordedAtIso: String?,
    ): VehicleLocation = api.submitVehicleLocation(latitude, longitude, recordedAtIso)
}
