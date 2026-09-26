package com.safar.app.data.repository

import com.safar.app.data.remote.SafarApi
import com.safar.app.domain.model.Vehicle
import com.safar.app.domain.repository.VehicleRepository

class VehicleRepositoryImpl(
    private val api: SafarApi,
) : VehicleRepository {
    override suspend fun getVehiclesForRoute(routeId: String): List<Vehicle> =
        api.getVehiclesForRoute(routeId)

    override suspend fun getActiveVehiclesForRoute(routeId: String): List<Vehicle> =
        api.getActiveVehiclesForRoute(routeId)
}
