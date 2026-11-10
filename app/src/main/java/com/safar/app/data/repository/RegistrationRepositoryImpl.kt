package com.safar.app.data.repository

import com.safar.app.data.remote.SafarApi
import com.safar.app.domain.model.User
import com.safar.app.domain.repository.RegistrationRepository

class RegistrationRepositoryImpl(
    private val api: SafarApi,
) : RegistrationRepository {
    override suspend fun ensureAnonymousSession() = api.ensureAnonymousSession()

    override suspend fun registerPassenger(name: String, phone: String): User =
        api.registerPassenger(name, phone)

    override suspend fun loginPassenger(name: String, phone: String): User =
        api.loginPassenger(name, phone)

    override suspend fun loginDriver(name: String, phone: String, vehicleIdentifier: String): User =
        api.loginDriver(name, phone, vehicleIdentifier)
}
