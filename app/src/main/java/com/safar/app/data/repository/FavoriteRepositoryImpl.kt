package com.safar.app.data.repository

import com.safar.app.data.remote.SafarApi
import com.safar.app.domain.model.FavoriteVehicle
import com.safar.app.domain.repository.FavoriteRepository

class FavoriteRepositoryImpl(
    private val api: SafarApi,
) : FavoriteRepository {
    override suspend fun getFavorites(): List<FavoriteVehicle> = api.getFavoriteVehicles()

    override suspend fun addFavorite(vehicleId: String): FavoriteVehicle =
        api.addFavoriteVehicle(vehicleId)

    override suspend fun removeFavorite(vehicleId: String) =
        api.removeFavoriteVehicle(vehicleId)
}
