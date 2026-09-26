package com.safar.app.data.repository

import com.safar.app.data.remote.SafarApi
import com.safar.app.domain.model.Route
import com.safar.app.domain.repository.RouteRepository

class RouteRepositoryImpl(
    private val api: SafarApi,
) : RouteRepository {
    override suspend fun getRoutes(): List<Route> = api.getRoutes()

    override suspend fun getRoute(routeId: String): Route? = api.getRoute(routeId)
}
