package com.safar.app.di

import com.safar.app.data.remote.SafarApi
import com.safar.app.data.remote.SupabaseSafarApi
import com.safar.app.data.repository.DriverRepositoryImpl
import com.safar.app.data.repository.DriverLocationRepositoryImpl
import com.safar.app.data.repository.FavoriteRepositoryImpl
import com.safar.app.data.repository.RegistrationRepositoryImpl
import com.safar.app.data.repository.RouteRepositoryImpl
import com.safar.app.data.repository.UserRepositoryImpl
import com.safar.app.data.repository.VehicleRepositoryImpl
import com.safar.app.data.repository.WorkSessionRepositoryImpl
import com.safar.app.data.realtime.RealtimeTransport
import com.safar.app.data.realtime.SupabaseRealtimeTransport
import com.safar.app.domain.repository.DriverLocationRepository
import com.safar.app.domain.repository.DriverRepository
import com.safar.app.domain.repository.FavoriteRepository
import com.safar.app.domain.repository.RegistrationRepository
import com.safar.app.domain.repository.RouteRepository
import com.safar.app.domain.repository.UserRepository
import com.safar.app.domain.repository.VehicleRepository
import com.safar.app.domain.repository.WorkSessionRepository

/**
 * Single app-wide dependency graph. Kept manual to match the existing
 * single-module project and avoid introducing a new DI framework at Stage 03.
 */
object AppContainer {
    val api: SafarApi by lazy { SupabaseSafarApi() }

    val userRepository: UserRepository by lazy { UserRepositoryImpl(api) }
    val routeRepository: RouteRepository by lazy { RouteRepositoryImpl(api) }
    val vehicleRepository: VehicleRepository by lazy { VehicleRepositoryImpl(api) }
    val driverRepository: DriverRepository by lazy { DriverRepositoryImpl(api) }
    val driverLocationRepository: DriverLocationRepository by lazy { DriverLocationRepositoryImpl(api) }
    val registrationRepository: RegistrationRepository by lazy { RegistrationRepositoryImpl(api) }
    val favoriteRepository: FavoriteRepository by lazy { FavoriteRepositoryImpl(api) }
    val workSessionRepository: WorkSessionRepository by lazy { WorkSessionRepositoryImpl(api) }
    val realtimeTransport: RealtimeTransport by lazy { SupabaseRealtimeTransport() }
}
