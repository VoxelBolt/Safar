package com.safar.app.data.repository

import com.safar.app.data.remote.SafarApi
import com.safar.app.domain.model.Driver
import com.safar.app.domain.model.DriverApplication
import com.safar.app.domain.model.User
import com.safar.app.domain.repository.DriverRepository

class DriverRepositoryImpl(
    private val api: SafarApi,
) : DriverRepository {
    override suspend fun getDriver(): Driver? = api.getDriver()

    override suspend fun getDriverApplication(): DriverApplication? = api.getDriverApplication()

    override suspend fun registerDriver(
        name: String,
        phone: String,
        vehicleIdentifier: String,
        routeId: String,
    ): DriverApplication = api.registerDriver(name, phone, vehicleIdentifier, routeId)

    override suspend fun loginDriver(
        name: String,
        phone: String,
        vehicleIdentifier: String,
    ): User = api.loginDriver(name, phone, vehicleIdentifier)

    override suspend fun assignRoute(routeId: String): Driver = api.assignDriverRoute(routeId)
}
