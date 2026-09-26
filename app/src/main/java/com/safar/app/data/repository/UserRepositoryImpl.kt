package com.safar.app.data.repository

import com.safar.app.data.remote.SafarApi
import com.safar.app.domain.model.User
import com.safar.app.domain.repository.UserRepository

class UserRepositoryImpl(
    private val api: SafarApi,
) : UserRepository {
    override suspend fun getCurrentUser(): User? = api.getCurrentUser()

    override suspend fun updateCurrentUser(name: String, phone: String): User =
        api.updateCurrentUser(name, phone)
}
