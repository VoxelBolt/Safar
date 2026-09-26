package com.safar.app.data.repository

import com.safar.app.data.remote.SafarApi
import com.safar.app.domain.model.Driver
import com.safar.app.domain.repository.DriverRepository

class DriverRepositoryImpl(
    private val api: SafarApi,
) : DriverRepository {
    override suspend fun getDriver(): Driver? = api.getDriver()

    override suspend fun registerDriver(
        name: String,
        phone: String,
        vehicleIdentifier: String,
    ): Driver = api.registerDriver(name, phone, vehicleIdentifier)

    override suspend fun assignRoute(routeId: String): Driver = api.assignDriverRoute(routeId)
}
