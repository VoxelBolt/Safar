package com.safar.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safar.app.R
import com.safar.app.data.location.AndroidLocationProvider
import com.safar.app.data.location.DriverLocationService
import com.safar.app.domain.model.GeoPoint
import com.safar.app.domain.model.PassengerLocation
import com.safar.app.domain.model.Route
import com.safar.app.domain.model.UserRole
import com.safar.app.domain.model.Vehicle
import com.safar.app.domain.model.VehicleLocation
import com.safar.app.domain.model.WorkSessionStatus
import com.safar.app.domain.model.WorkSessionTimer
import com.safar.app.ui.onboarding.OnboardingState
import com.safar.app.ui.theme.SafarBlue
import com.safar.app.ui.theme.SafarNavy
import com.safar.app.ui.theme.SafarWhite
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

private val SoftBlue = Color(0xFFEAF2FF)
private val PageBackground = Color(0xFFF7F9FD)
private val Border = Color(0xFFE3E9F2)
private val Muted = Color(0xFF71809A)
private val Success = Color(0xFF22A447)
private val SuccessSoft = Color(0xFFEAF8EF)
private val Danger = Color(0xFFE44949)
private val DangerSoft = Color(0xFFFFF0F0)

@Composable
fun PassengerMainScreen(state: OnboardingState, onLanguageSelected: (String) -> Unit, onLogout: () -> Unit) {
    var tab by remember { mutableStateOf(PassengerTab.MAP) }
    val mapViewModel: PassengerMapViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val favoriteViewModel: FavoriteViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        mapViewModel.onLocationPermissionResult(
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        )
    }

    LaunchedEffect(Unit) {
        mapViewModel.loadRoutes()
        mapViewModel.refreshLocationPermissionState()
        favoriteViewModel.load()
    }

    Scaffold(
        containerColor = PageBackground,
        bottomBar = {
            SafarBottomBar(
                selected = tab.ordinal,
                labels = listOf(
                    stringResource(R.string.nav_map),
                    stringResource(R.string.nav_favorites),
                    stringResource(R.string.nav_profile),
                ),
                glyphs = listOf(Glyph.MAP, Glyph.STAR, Glyph.PERSON),
                onSelected = { tab = PassengerTab.entries[it] },
            )
        },
    ) { padding ->
        when (tab) {
            PassengerTab.MAP -> PassengerMapScreen(
                viewModel = mapViewModel,
                isFavorite = { vehicleId -> favoriteViewModel.isFavorite(vehicleId) },
                onToggleFavorite = { vehicle -> favoriteViewModel.toggle(vehicle) },
                onRequestLocationPermission = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                },
                modifier = Modifier.padding(padding),
            )
            PassengerTab.FAVORITES -> PassengerFavoritesRealScreen(viewModel = favoriteViewModel, modifier = Modifier.padding(padding))
            PassengerTab.PROFILE -> PassengerProfileRealScreen(
                isDriver = false,
                currentLanguageTag = state.languageTag,
                onLanguageSelected = onLanguageSelected,
                onLogout = onLogout,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

private enum class PassengerTab { MAP, FAVORITES, PROFILE }

@Composable
private fun PassengerMapScreen(
    viewModel: PassengerMapViewModel,
    isFavorite: (String) -> Boolean,
    onToggleFavorite: (Vehicle) -> Unit,
    onRequestLocationPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.uiState.collectAsState()
    var pickerOpen by remember { mutableStateOf(false) }

    if (pickerOpen) {
        RoutePickerScreen(
            routes = ui.routes,
            recentRoutes = ui.recentRoutes,
            loading = ui.loadingRoutes,
            error = ui.error,
            onBack = { pickerOpen = false },
            onRouteSelected = {
                viewModel.selectRoute(it)
                pickerOpen = false
            },
            onRetry = viewModel::loadRoutes,
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            PassengerMapView(
                route = ui.selectedRoute,
                passengerLocation = ui.passengerLocation,
                headingDegrees = ui.headingDegrees,
                locationPermissionGranted = !ui.locationPermissionRequired,
                locateRequestId = ui.locateRequestId,
                onLocateUser = viewModel::refreshPassengerLocation,
                onMapCenterChanged = viewModel::onMapCenterChanged,
                nearestVehicle = ui.nearestVehicle?.let { vehicle ->
                    vehicle.currentLocation?.let { location -> vehicle to location }
                },
                nearestVehicleRequestId = ui.nearestVehicleRequestId,
                vehicleLocations = ui.activeVehicles.mapNotNull { vehicle ->
                    vehicle.currentLocation?.let { vehicle to it }
                },
                passengerLocations = emptyList(),
                routeOpacity = 0.78f,
                modifier = Modifier.fillMaxSize(),
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clickable { pickerOpen = true },
                shape = RoundedCornerShape(18.dp),
                color = SafarWhite,
                shadowElevation = 5.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SafarGlyph(Glyph.SEARCH, SafarNavy, 22.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = ui.selectedRoute?.let { stringResource(R.string.selected_route_label, it.number) }
                            ?: stringResource(R.string.route_search_hint),
                        color = if (ui.selectedRoute == null) Muted else SafarNavy,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (ui.loadingRoutes) {
                        Text("…", color = Muted)
                    } else {
                        SafarGlyph(Glyph.CHEVRON, SafarBlue, 22.dp)
                    }
                }
            }

            if (ui.locationPermissionRequired) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 16.dp, top = 76.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = SafarWhite,
                    shadowElevation = 3.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { onRequestLocationPermission() }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SafarGlyph(Glyph.PIN, Muted, 20.dp)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            stringResource(R.string.location_permission_needed),
                            style = MaterialTheme.typography.labelLarge,
                            color = SafarNavy,
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(24.dp),
                color = SafarWhite,
                shadowElevation = 9.dp,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(13.dp), color = SoftBlue) {
                            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                                SafarGlyph(Glyph.BUS, SafarBlue, 25.dp)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = ui.selectedRoute?.let { stringResource(R.string.selected_route_label, it.number) }
                                    ?: stringResource(R.string.no_route_selected),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = SafarNavy,
                            )
                            Text(
                                text = ui.selectedRoute?.let { it.number } ?: stringResource(R.string.route_search_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            // Prefer the real passenger GPS position. If Android has no fix,
                            // use the actual map center that the user is looking at.
                            val referencePoint = ui.passengerLocation ?: ui.mapCenterLocation
                            val etaCandidates = (ui.activeVehicles + listOfNotNull(ui.nearestVehicle))
                                .distinctBy { it.id }
                                .filter { it.currentLocation != null }
                            val calculatedNearest = referencePoint?.let { passenger ->
                                NearestVehicleCalculator.findNearest(passenger, etaCandidates)
                            }
                            val etaMinutes = calculatedNearest?.etaMinutes
                                ?: ui.nearestVehicleEtaMinutes
                                ?: ui.nearestVehicleDistanceMeters?.let(NearestVehicleCalculator::etaMinutes)

                            Surface(
                                onClick = { viewModel.showNearestVehicleOnMap() },
                                enabled = !ui.findingNearestVehicle,
                                modifier = Modifier.size(46.dp),
                                shape = CircleShape,
                                color = SoftBlue,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    SafarGlyph(Glyph.LOCATE, SafarBlue, 23.dp)
                                }
                            }

                            val favoriteVehicle = activeVehicleForFavorite(ui.activeVehicles, ui.nearestVehicle)
                            if (favoriteVehicle != null) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Surface(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clickable {
                                                onToggleFavorite(favoriteVehicle)
                                            },
                                        shape = CircleShape,
                                        color = SoftBlue,
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            SafarGlyph(
                                                if (isFavorite(favoriteVehicle.id)) Glyph.STAR_FILLED else Glyph.STAR,
                                                SafarBlue,
                                                23.dp,
                                            )
                                        }
                                    }

                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = Border)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.nearest_transport),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = SafarNavy,
                    )
                    Spacer(Modifier.height(8.dp))

                    // ETA is displayed beside the nearest transport row.
                    // Calculate it from the same passenger point and vehicle list
                    // used by the nearest-vehicle action, with the ViewModel value
                    // as a fallback while the next location update is arriving.
                    val referencePoint = ui.passengerLocation ?: ui.mapCenterLocation
                    val etaCandidates = (ui.activeVehicles + listOfNotNull(ui.nearestVehicle))
                        .distinctBy { it.id }
                        .filter { it.currentLocation != null }
                    val calculatedNearest = referencePoint?.let { passenger ->
                        NearestVehicleCalculator.findNearest(passenger, etaCandidates)
                    }
                    val etaMinutes = calculatedNearest?.etaMinutes
                        ?: ui.nearestVehicleEtaMinutes
                        ?: ui.nearestVehicleDistanceMeters?.let(NearestVehicleCalculator::etaMinutes)

                    val activeVehicle = ui.nearestVehicle ?: ui.activeVehicles.firstOrNull()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (activeVehicle != null) SuccessSoft else Color(0xFFF0F2F5),
                        ) { Box(Modifier.size(10.dp)) }
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeVehicle?.identifier ?: stringResource(R.string.no_active_transport),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = SafarNavy,
                            )
                            if (activeVehicle != null) {
                                Text(
                                    stringResource(R.string.active_vehicles_count, ui.activeVehicles.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted,
                                )
                            } else {
                                Text(
                                    stringResource(R.string.no_active_transport_detail),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted,
                                )
                            }
                        }

                        Text(
                            text = if (ui.findingNearestVehicle) {
                                "…"
                            } else {
                                etaMinutes?.let {
                                    stringResource(R.string.nearest_vehicle_eta, it)
                                } ?: "—"
                            },
                            color = SafarBlue,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutePickerScreen(
    routes: List<Route>,
    recentRoutes: List<Route>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRouteSelected: (Route) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val filtered = routes.filter {
        query.isBlank() || it.number.contains(query, true)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
            .imePadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp)) {
            RoundIconButton(Glyph.BACK, onBack)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.route_picker_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = SafarNavy)
        }

        Surface(shape = RoundedCornerShape(16.dp), color = SafarWhite, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                SafarGlyph(Glyph.SEARCH, Muted, 22.dp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 13.dp),
                    singleLine = true,
                    textStyle = TextStyle(color = SafarNavy, fontSize = 15.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text(stringResource(R.string.route_search_hint), color = Muted)
                        inner()
                    },
                )
            }
        }

        if (loading) {
            Text(stringResource(R.string.routes_loading), modifier = Modifier.padding(top = 20.dp), color = Muted)
        }
        error?.let {
            Text(stringResource(R.string.routes_load_error), modifier = Modifier.padding(top = 20.dp), color = Danger)
            Spacer(Modifier.height(8.dp))
            OutlinedRetryButton(onRetry)
        }

        if (!loading && recentRoutes.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.recent_routes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = SafarNavy)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recentRoutes.take(3).forEach { route ->
                    Surface(
                        modifier = Modifier.clickable { onRouteSelected(route) },
                        shape = RoundedCornerShape(14.dp),
                        color = SoftBlue,
                    ) {
                        Text(route.number, modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp), color = SafarBlue, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(stringResource(R.string.all_routes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = SafarNavy)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filtered, key = { it.id }) { route ->
                RouteRow(route = route, onClick = { onRouteSelected(route) })
            }
        }
    }
}

@Composable
private fun RouteRow(route: Route, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = SafarWhite,
        shadowElevation = 1.dp,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(13.dp), color = SoftBlue) {
                Text(route.number, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = SafarBlue, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(route.number, color = SafarNavy, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (route.path.size >= 2) stringResource(R.string.route_ready)
                    else stringResource(R.string.route_path_unavailable),
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SafarGlyph(Glyph.CHEVRON, Muted, 21.dp)
        }
    }
}

@Composable
private fun OutlinedRetryButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = SafarWhite,
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        Text(stringResource(R.string.retry), modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = SafarBlue, fontWeight = FontWeight.SemiBold)
    }
}

private fun activeVehicleForFavorite(active: List<Vehicle>, nearest: Vehicle?): Vehicle? =
    nearest ?: active.firstOrNull()

private data class VehicleMock(val identifier: String, val route: String, val direction: String, val eta: String, val distance: String, val active: Boolean)

@Composable
private fun PassengerFavoritesScreen(modifier: Modifier = Modifier) {
    var favorites by remember { mutableStateOf(emptyList<VehicleMock>()) }

    Column(modifier = modifier.fillMaxSize().background(PageBackground).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.favorites_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = SafarNavy, modifier = Modifier.weight(1f))
            RoundIconButton(Glyph.PLUS, onClick = {}, tint = SafarBlue)
        }
        Text(stringResource(R.string.favorites_subtitle), style = MaterialTheme.typography.bodyMedium, color = Muted)
        Spacer(Modifier.height(18.dp))

        if (favorites.isEmpty()) {
            EmptyState(
                glyph = Glyph.STAR,
                title = stringResource(R.string.favorites_empty_title),
                subtitle = stringResource(R.string.favorites_empty_subtitle),
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(favorites, key = { it.identifier }) { vehicle ->
                    FavoriteVehicleCard(
                        vehicle = vehicle,
                        onRemove = { favorites = favorites.filterNot { it.identifier == vehicle.identifier } },
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteVehicleCard(vehicle: VehicleMock, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = SafarWhite, shadowElevation = 2.dp) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFF0F4FA)) {
                    Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                        SafarGlyph(Glyph.BUS, SafarNavy, 38.dp)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(vehicle.route, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = SafarNavy)
                    Text(vehicle.identifier, style = MaterialTheme.typography.bodyMedium, color = Muted)
                    Spacer(Modifier.height(4.dp))
                    Text(vehicle.direction, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                RoundIconButton(Glyph.STAR_FILLED, onRemove, tint = SafarBlue)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = if (vehicle.active) Success else Color(0xFFB8C0CC)) { Box(Modifier.size(9.dp)) }
                Spacer(Modifier.width(7.dp))
                Text(
                    if (vehicle.active) stringResource(R.string.transport_on_route) else stringResource(R.string.transport_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (vehicle.active) Success else Muted,
                )
                Spacer(Modifier.weight(1f))
                if (vehicle.active) {
                    Text(vehicle.eta, color = SafarBlue, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PassengerProfileScreen(state: OnboardingState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().background(PageBackground).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = SafarNavy)
                Text(stringResource(R.string.profile_subtitle), color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Surface(shape = RoundedCornerShape(14.dp), color = SoftBlue) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) { SafarGlyph(Glyph.SETTINGS, SafarBlue, 23.dp) }
            }
        }
        Spacer(Modifier.height(18.dp))
        ProfileHeader(name = state.name.ifBlank { stringResource(R.string.user_default_name) }, phone = state.phone)
        Spacer(Modifier.height(12.dp))
        ProfileMenuCard(
            rows = listOf(
                ProfileRow(Glyph.PERSON, stringResource(R.string.edit_profile)),
                ProfileRow(Glyph.BELL, stringResource(R.string.notifications)),
                ProfileRow(Glyph.LANGUAGE, stringResource(R.string.language_label), state.languageTag.uppercase()),
                ProfileRow(Glyph.HELP, stringResource(R.string.help)),
                ProfileRow(Glyph.INFO, stringResource(R.string.about_app), "v0.1.0"),
            ),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DangerSoft, contentColor = Danger),
        ) {
            SafarGlyph(Glyph.LOGOUT, Danger, 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.logout), fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProfileHeader(name: String, phone: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = SafarWhite, shadowElevation = 1.dp) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = SoftBlue) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { SafarGlyph(Glyph.PERSON, SafarBlue, 34.dp) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = SafarNavy)
                Text(phone.ifBlank { "—" }, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            SafarGlyph(Glyph.CHEVRON, Muted, 22.dp)
        }
    }
}

private data class ProfileRow(val glyph: Glyph, val title: String, val trailing: String? = null)

@Composable
private fun ProfileMenuCard(rows: List<ProfileRow>) {
    Surface(shape = RoundedCornerShape(20.dp), color = SafarWhite, shadowElevation = 1.dp) {
        Column {
            rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SafarGlyph(row.glyph, SafarBlue, 22.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(row.title, Modifier.weight(1f), color = SafarNavy, style = MaterialTheme.typography.bodyMedium)
                    row.trailing?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.width(8.dp))
                    SafarGlyph(Glyph.CHEVRON, Muted, 20.dp)
                }
                if (index != rows.lastIndex) HorizontalDivider(color = Border)
            }
        }
    }
}

@Composable
fun DriverMainScreen(state: OnboardingState, onLanguageSelected: (String) -> Unit, onLogout: () -> Unit) {
    var tab by remember { mutableStateOf(DriverTab.HOME) }
    val driverViewModel: DriverViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val ui by driverViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var foregroundLocationGranted by remember { mutableStateOf(hasForegroundLocationPermission(context)) }
    // Driver GPS tracking is independent from the Work Session timer. A driver
    // with location permission and an assigned route stays visible to passengers
    // even after Pause/Finish.
    val trackingReady = foregroundLocationGranted &&
        ui.driver?.routeId != null

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        foregroundLocationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            hasForegroundLocationPermission(context)
        // GPS publishing starts as soon as the driver has permission and an assigned route; it is not tied to the timer.
    }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner, trackingReady) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                foregroundLocationGranted = hasForegroundLocationPermission(context)
                // Re-issue the start command every time the driver screen becomes
                // visible. If Android restarted/killed the FGS while the Compose
                // state stayed unchanged, the old LaunchedEffect would not fire
                // again. This keeps driver GPS publishing self-healing.
                if (trackingReady && hasForegroundLocationPermission(context)) {
                    runCatching { DriverLocationService.start(context) }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun requestDriverTrackingPermission() {
        if (!foregroundLocationGranted) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    LaunchedEffect(trackingReady) {
        if (trackingReady) {
            runCatching { DriverLocationService.start(context) }
        }
    }

    Scaffold(
        containerColor = PageBackground,
        bottomBar = {
            SafarBottomBar(
                selected = tab.ordinal,
                labels = listOf(
                    stringResource(R.string.nav_home),
                    stringResource(R.string.nav_statistics),
                    stringResource(R.string.nav_profile),
                ),
                glyphs = listOf(Glyph.HOME, Glyph.STATS, Glyph.PERSON),
                onSelected = { tab = DriverTab.entries[it] },
            )
        },
    ) { padding ->
        when (tab) {
            DriverTab.HOME -> DriverHomeScreen(
                ui = ui,
                onStartWork = driverViewModel::startWork,
                onPauseWork = driverViewModel::pauseWork,
                onResumeWork = driverViewModel::resumeWork,
                onFinishWork = driverViewModel::finishWork,
                onPublishDriverLocation = driverViewModel::publishDriverLocation,
                foregroundLocationGranted = foregroundLocationGranted,
                onRequestTrackingPermission = ::requestDriverTrackingPermission,
                modifier = Modifier.padding(padding),
            )
            DriverTab.STATS -> DriverStatisticsRealScreen(modifier = Modifier.padding(padding))
            DriverTab.PROFILE -> DriverProfileRealScreen(
                currentLanguageTag = state.languageTag,
                onLanguageSelected = onLanguageSelected,
                onLogout = onLogout,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

private enum class DriverTab { HOME, STATS, PROFILE }

@Composable
private fun DriverHomeScreen(
    ui: DriverUiState,
    onStartWork: () -> Unit,
    onPauseWork: () -> Unit,
    onResumeWork: () -> Unit,
    onFinishWork: () -> Unit,
    onPublishDriverLocation: (GeoPoint) -> Unit,
    foregroundLocationGranted: Boolean,
    onRequestTrackingPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(ui.workSession?.id, ui.workSession?.status) {
        while (ui.workSession?.status == WorkSessionStatus.ACTIVE) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
    }

    val session = ui.workSession
    val status = session?.status ?: WorkSessionStatus.NOT_STARTED
    val elapsed = session?.let { WorkSessionTimer.elapsedWorkSeconds(it, now) } ?: 0L

    val context = LocalContext.current
    val driverLocationProvider = remember(context) { AndroidLocationProvider(context.applicationContext) }
    var driverLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var driverLocateRequestId by remember { mutableLongStateOf(0L) }

    // Fail-safe publisher: while the driver screen is visible and the driver has
    // an assigned route, also send a fresh GPS fix every 5 seconds. The foreground
    // service remains responsible for background tracking.
    LaunchedEffect(ui.driver?.routeId, foregroundLocationGranted) {
        if (ui.driver?.routeId == null || !foregroundLocationGranted) return@LaunchedEffect
        while (true) {
            driverLocationProvider.currentLocationSample { sample ->
                if (sample != null) {
                    driverLocation = sample.point
                    onPublishDriverLocation(sample.point)
                }
            }
            delay(5_000L)
        }
    }

    LaunchedEffect(foregroundLocationGranted) {
        if (!foregroundLocationGranted) {
            driverLocation = null
            return@LaunchedEffect
        }

        driverLocationProvider.lastKnownLocation { point ->
            if (point != null) driverLocation = point
        }
        driverLocationProvider.currentLocation { point ->
            if (point != null) {
                driverLocation = point
                driverLocateRequestId += 1L
            }
        }

        try {
            driverLocationProvider.locations().collect { point ->
                driverLocation = point
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            while (true) {
                driverLocationProvider.currentLocation { point ->
                    if (point != null) {
                        driverLocation = point
                    }
                }
                delay(2_000L)
            }
        }
    }

    val statusText = when (status) {
        WorkSessionStatus.NOT_STARTED -> stringResource(R.string.work_not_started)
        WorkSessionStatus.ACTIVE -> stringResource(R.string.work_active)
        WorkSessionStatus.PAUSED -> stringResource(R.string.work_paused)
        WorkSessionStatus.COMPLETED -> stringResource(R.string.work_finished)
    }
    val statusColor = if (status == WorkSessionStatus.ACTIVE) Success else Muted
    val distanceKm = stringResource(R.string.distance_km_format, (session?.distanceMeters ?: 0.0) / 1000.0)
    val routeNumber = ui.selectedRoute?.number ?: stringResource(R.string.route_not_selected)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground),
    ) {
        // The map is the primary surface of the driver home screen. There is
        // intentionally no parent verticalScroll here, so drag gestures reach
        // MapLibre instead of scrolling the whole page.
        PassengerMapView(
            route = ui.selectedRoute,
            passengerLocation = driverLocation,
            headingDegrees = null,
            locationPermissionGranted = foregroundLocationGranted,
            locateRequestId = driverLocateRequestId,
            onLocateUser = {
                driverLocationProvider.currentLocation { point ->
                    if (point != null) {
                        driverLocation = point
                        driverLocateRequestId += 1L
                    }
                }
            },
            vehicleLocations = ui.activeVehicles
                .filter { it.id != ui.driver?.vehicleId }
                .mapNotNull { vehicle -> vehicle.currentLocation?.let { vehicle to it } },
            passengerLocations = ui.passengerLocations,
            routeOpacity = 0.78f,
            modifier = Modifier.fillMaxSize(),
        )

        if (ui.passengerLocations.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 82.dp),
                shape = RoundedCornerShape(14.dp),
                color = SafarWhite,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = stringResource(R.string.passengers_waiting, routeNumber, ui.passengerLocations.size),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = SafarNavy,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        // Header is an overlay rather than a separate page section. Only the
        // controls inside it consume touches; the rest of the map remains free
        // for pan/zoom gestures.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
                color = SafarWhite,
                shadowElevation = 5.dp,
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
                    Text(
                        stringResource(R.string.driver_home_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = SafarNavy,
                        maxLines = 1,
                    )
                    Text(
                        stringResource(R.string.driver_home_subtitle),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Surface(
                modifier = Modifier.size(50.dp),
                shape = RoundedCornerShape(16.dp),
                color = SoftBlue,
                shadowElevation = 2.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    SafarGlyph(Glyph.SETTINGS, SafarBlue, 23.dp)
                }
            }
        }

        if (!foregroundLocationGranted) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 76.dp),
                shape = RoundedCornerShape(14.dp),
                color = SafarWhite,
                shadowElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier
                        .clickable { onRequestTrackingPermission() }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SafarGlyph(Glyph.PIN, Muted, 20.dp)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        stringResource(R.string.location_permission_needed),
                        style = MaterialTheme.typography.labelLarge,
                        color = SafarNavy,
                    )
                }
            }
        }

        // Compact, responsive work-session panel. The map stays the primary
        // surface and the session controls no longer consume half the screen
        // on phones with smaller heights or larger system fonts.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            shape = RoundedCornerShape(22.dp),
            color = SafarWhite,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = if (status == WorkSessionStatus.ACTIVE) SuccessSoft else SoftBlue,
                    ) {
                        Box(Modifier.size(10.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        statusText,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    SafarGlyph(Glyph.CLOCK, SafarBlue, 19.dp)
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Border)
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1.35f)) {
                        Text(
                            stringResource(R.string.work_session_label),
                            color = Muted,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                        Text(
                            formatDuration(elapsed),
                            color = SafarNavy,
                            fontSize = 30.sp,
                            lineHeight = 32.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                    Column(modifier = Modifier.weight(0.8f)) {
                        Text(
                            stringResource(R.string.work_distance),
                            color = Muted,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                        Text(
                            "$distanceKm км",
                            color = SafarNavy,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                    Column(modifier = Modifier.weight(0.65f)) {
                        Text(
                            stringResource(R.string.work_route),
                            color = Muted,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                        Text(
                            routeNumber,
                            color = SafarNavy,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }

                Spacer(Modifier.height(9.dp))
                when (status) {
                    WorkSessionStatus.NOT_STARTED, WorkSessionStatus.COMPLETED -> {
                        PrimaryActionButton(
                            if (status == WorkSessionStatus.COMPLETED) stringResource(R.string.start_new_work) else stringResource(R.string.start_work),
                            Glyph.PLAY,
                            enabled = !ui.actionInProgress,
                        ) { onStartWork() }
                    }
                    WorkSessionStatus.ACTIVE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryActionButton(
                                stringResource(R.string.pause_work),
                                Glyph.PAUSE,
                                Modifier.weight(1f),
                                enabled = !ui.actionInProgress,
                            ) { onPauseWork() }
                            PrimaryActionButton(
                                stringResource(R.string.finish_work),
                                Glyph.CHECK,
                                Modifier.weight(1f),
                                enabled = !ui.actionInProgress,
                            ) { onFinishWork() }
                        }
                    }
                    WorkSessionStatus.PAUSED -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryActionButton(
                                stringResource(R.string.continue_work),
                                Glyph.PLAY,
                                Modifier.weight(1f),
                                enabled = !ui.actionInProgress,
                            ) { onResumeWork() }
                            SecondaryActionButton(
                                stringResource(R.string.finish_work),
                                Glyph.CHECK,
                                Modifier.weight(1f),
                                enabled = !ui.actionInProgress,
                            ) { onFinishWork() }
                        }
                    }
                }

                ui.error?.let { error ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.generic_load_error),
                        color = Danger,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatMini(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(value, color = SafarNavy, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}


private fun hasForegroundLocationPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun formatDuration(totalSeconds: Long): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0L)
    val hours = safeSeconds / 3600
    val minutes = (safeSeconds % 3600) / 60
    val seconds = safeSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}


@Composable
private fun DriverStatisticsScreen(modifier: Modifier = Modifier) {
    var period by remember { mutableStateOf(0) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.statistics_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = SafarNavy,
            maxLines = 1,
        )
        Spacer(Modifier.height(14.dp))
        SegmentedControl(
            labels = listOf(
                stringResource(R.string.period_7_days),
                stringResource(R.string.period_30_days),
                stringResource(R.string.period_all),
            ),
            selected = period,
            onSelected = { period = it },
        )
        Spacer(Modifier.height(16.dp))

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 380.dp
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DriverSummaryCard(
                        title = "Время работы",
                        value = "28 ч 15 мин",
                        compact = true,
                    )
                    DriverSummaryCard(
                        title = "Пройденное расстояние",
                        value = "412 км",
                        compact = true,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DriverSummaryCard(
                        title = "Время работы",
                        value = "28 ч 15 мин",
                        modifier = Modifier.weight(1f),
                        compact = false,
                    )
                    DriverSummaryCard(
                        title = "Пройденное расстояние",
                        value = "412 км",
                        modifier = Modifier.weight(1f),
                        compact = false,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.by_days),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = SafarNavy,
        )
        Spacer(Modifier.height(8.dp))
        val days = listOf(
            "Сегодня" to ("5 ч 20 мин" to "78 км"),
            "Вчера" to ("4 ч 10 мин" to "62 км"),
            "3 сент." to ("6 ч 45 мин" to "98 км"),
            "2 сент." to ("3 ч 30 мин" to "50 км"),
            "1 сент." to ("4 ч 30 мин" to "68 км"),
            "31 авг." to ("4 ч 00 мин" to "56 км"),
            "30 авг." to ("2 ч 55 мин" to "40 км"),
        )
        Surface(shape = RoundedCornerShape(20.dp), color = SafarWhite, shadowElevation = 1.dp) {
            Column {
                days.forEachIndexed { index, (day, values) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            day,
                            Modifier.weight(1.05f),
                            color = Muted,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                        Row(
                            Modifier.weight(1.15f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SafarGlyph(Glyph.CLOCK, SafarBlue, 18.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                values.first,
                                color = SafarNavy,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                        Row(
                            Modifier.weight(.8f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SafarGlyph(Glyph.ROUTE, SafarBlue, 18.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                values.second,
                                color = SafarNavy,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                    }
                    if (index != days.lastIndex) HorizontalDivider(color = Border)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DriverSummaryCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    compact: Boolean,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFF0ECF2),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = if (compact) 13.dp else 16.dp)) {
            Text(
                title,
                color = SafarNavy,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                maxLines = if (compact) 1 else 2,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                value,
                color = SafarNavy,
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun SegmentedControl(labels: List<String>, selected: Int, onSelected: (Int) -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFEFF3F9)) {
        Row(Modifier.padding(3.dp)) {
            labels.forEachIndexed { index, label ->
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelected(index) },
                    shape = RoundedCornerShape(13.dp),
                    color = if (index == selected) SafarBlue else Color.Transparent,
                ) {
                    Text(
                        label,
                        Modifier.padding(horizontal = 2.dp, vertical = 10.dp),
                        textAlign = TextAlign.Center,
                        color = if (index == selected) SafarWhite else Muted,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun DriverProfileScreen(state: OnboardingState, route: Route?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().background(PageBackground).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = SafarNavy)
                Text(stringResource(R.string.profile_subtitle), color = Muted)
            }
            Surface(shape = RoundedCornerShape(14.dp), color = SoftBlue) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) { SafarGlyph(Glyph.SETTINGS, SafarBlue, 23.dp) }
            }
        }
        Spacer(Modifier.height(18.dp))
        ProfileHeader(name = state.name.ifBlank { stringResource(R.string.user_default_name) }, phone = state.phone)
        Spacer(Modifier.height(12.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = SafarWhite, shadowElevation = 1.dp) {
            Column {
                DriverProfileRow(Glyph.BUS, stringResource(R.string.vehicle_label), state.vehicleIdentifier.ifBlank { "—" })
                HorizontalDivider(color = Border)
                DriverProfileRow(Glyph.ROUTE, stringResource(R.string.route_label), route?.number ?: stringResource(R.string.route_not_selected))
            }
        }
        Spacer(Modifier.height(12.dp))
        ProfileMenuCard(
            listOf(
                ProfileRow(Glyph.PERSON, stringResource(R.string.edit_profile)),
                ProfileRow(Glyph.BELL, stringResource(R.string.notifications)),
                ProfileRow(Glyph.LANGUAGE, stringResource(R.string.language_label), state.languageTag.uppercase()),
                ProfileRow(Glyph.SHIELD, stringResource(R.string.driver_verification), stringResource(R.string.coming_later)),
                ProfileRow(Glyph.HELP, stringResource(R.string.help)),
                ProfileRow(Glyph.INFO, stringResource(R.string.about_app), "v0.1.0"),
            ),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DangerSoft, contentColor = Danger),
        ) {
            SafarGlyph(Glyph.LOGOUT, Danger, 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.logout), fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DriverProfileRow(glyph: Glyph, title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        SafarGlyph(glyph, SafarBlue, 22.dp)
        Spacer(Modifier.width(16.dp))
        Text(title, Modifier.weight(1f), color = SafarNavy, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        SafarGlyph(Glyph.CHEVRON, Muted, 20.dp)
    }
}

@Composable
private fun PrimaryActionButton(
    text: String,
    glyph: Glyph,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SafarBlue, contentColor = SafarWhite),
    ) {
        SafarGlyph(glyph, SafarWhite, 21.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryActionButton(
    text: String,
    glyph: Glyph,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SafarWhite, contentColor = SafarBlue),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        SafarGlyph(glyph, SafarBlue, 21.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun EmptyState(glyph: Glyph, title: String, subtitle: String) {
    Column(
        Modifier.fillMaxSize().padding(bottom = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = SoftBlue) {
            Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) { SafarGlyph(glyph, SafarBlue, 36.dp) }
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = SafarNavy, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 30.dp))
    }
}

internal enum class Glyph { MAP, STAR, STAR_FILLED, PERSON, HOME, STATS, SEARCH, PIN, LOCATE, BUS, CHEVRON, BACK, PLUS, CLOCK, ROUTE, SETTINGS, BELL, LANGUAGE, HELP, INFO, LOGOUT, PLAY, PAUSE, CHECK, SHIELD }

@Composable
private fun SafarBottomBar(
    selected: Int,
    labels: List<String>,
    glyphs: List<Glyph>,
    onSelected: (Int) -> Unit,
) {
    NavigationBar(
        containerColor = SafarWhite,
        tonalElevation = 3.dp,
        windowInsets = WindowInsets.navigationBars,
    ) {
        labels.forEachIndexed { index, label ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelected(index) },
                icon = { SafarGlyph(glyphs[index], if (selected == index) SafarBlue else Color(0xFF8B98AD), 24.dp) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = SafarBlue,
                    selectedTextColor = SafarBlue,
                    indicatorColor = SoftBlue,
                    unselectedIconColor = Color(0xFF8B98AD),
                    unselectedTextColor = Color(0xFF8B98AD),
                ),
            )
        }
    }
}

@Composable
private fun RoundIconButton(glyph: Glyph, onClick: () -> Unit, tint: Color = SafarNavy) {
    Surface(
        modifier = Modifier.size(46.dp).clickable(onClick = onClick),
        shape = CircleShape,
        color = SafarWhite,
        shadowElevation = 1.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            SafarGlyph(glyph, tint, 23.dp)
        }
    }
}

@Composable
private fun SafarGlyph(glyph: Glyph, tint: Color, size: androidx.compose.ui.unit.Dp) {
    val stroke = Stroke(width = with(LocalDensity.current) { 1.9.dp.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2
        val cy = h / 2
        when (glyph) {
            Glyph.STAR, Glyph.STAR_FILLED -> {
                val path = Path()
                for (i in 0..9) {
                    val angle = (-90 + i * 36) * Math.PI / 180
                    val r = if (i % 2 == 0) w * .43f else w * .19f
                    val p = Offset(cx + (r * kotlin.math.cos(angle)).toFloat(), cy + (r * kotlin.math.sin(angle)).toFloat())
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                if (glyph == Glyph.STAR_FILLED) drawPath(path, tint) else drawPath(path, tint, style = stroke)
            }
            Glyph.PERSON -> {
                drawCircle(tint, w * .16f, Offset(cx, h * .30f))
                drawArc(tint, 200f, 140f, false, Offset(w*.20f,h*.48f), Size(w*.60f,h*.42f), style=stroke)
            }
            Glyph.HOME -> {
                val p = Path().apply { moveTo(w*.16f,h*.48f); lineTo(cx,h*.16f); lineTo(w*.84f,h*.48f); lineTo(w*.76f,h*.48f); lineTo(w*.76f,h*.84f); lineTo(w*.56f,h*.84f); lineTo(w*.56f,h*.62f); lineTo(w*.44f,h*.62f); lineTo(w*.44f,h*.84f); lineTo(w*.24f,h*.84f); lineTo(w*.24f,h*.48f); close() }
                drawPath(p, tint, style = stroke)
            }
            Glyph.MAP -> {
                drawRect(tint, Offset(w*.10f,h*.16f), Size(w*.26f,h*.68f), style=stroke)
                drawRect(tint, Offset(w*.37f,h*.16f), Size(w*.26f,h*.68f), style=stroke)
                drawRect(tint, Offset(w*.64f,h*.16f), Size(w*.26f,h*.68f), style=stroke)
            }
            Glyph.STATS -> {
                drawLine(tint, Offset(w*.18f,h*.82f), Offset(w*.82f,h*.82f), stroke.width)
                drawLine(tint, Offset(w*.25f,h*.68f), Offset(w*.25f,h*.40f), stroke.width)
                drawLine(tint, Offset(w*.48f,h*.68f), Offset(w*.48f,h*.26f), stroke.width)
                drawLine(tint, Offset(w*.71f,h*.68f), Offset(w*.71f,h*.50f), stroke.width)
            }
            Glyph.SEARCH -> {
                drawCircle(tint, w*.31f, Offset(w*.43f,h*.43f), style=stroke)
                drawLine(tint, Offset(w*.66f,h*.66f), Offset(w*.86f,h*.86f), stroke.width)
            }
            Glyph.PIN -> {
                val p = Path().apply { moveTo(cx,h*.88f); cubicTo(w*.12f,h*.55f,w*.24f,h*.18f,cx,h*.18f); cubicTo(w*.76f,h*.18f,w*.88f,h*.55f,cx,h*.88f); close() }
                drawPath(p, tint, style=stroke)
                drawCircle(tint,w*.10f,Offset(cx,h*.42f),style=stroke)
            }
            Glyph.LOCATE -> {
                drawCircle(tint,w*.25f,Offset(cx,cy),style=stroke)
                drawLine(tint,Offset(cx,0f),Offset(cx,h*.20f),stroke.width)
                drawLine(tint,Offset(cx,h*.80f),Offset(cx,h),stroke.width)
                drawLine(tint,Offset(0f,cy),Offset(w*.20f,cy),stroke.width)
                drawLine(tint,Offset(w*.80f,cy),Offset(w,cy),stroke.width)
            }
            Glyph.BUS -> {
                drawRoundRect(tint, Offset(w*.20f,h*.18f), Size(w*.60f,h*.62f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w*.10f, w*.10f), style=stroke)
                drawLine(tint,Offset(w*.24f,h*.52f),Offset(w*.76f,h*.52f),stroke.width)
                drawCircle(tint,w*.07f,Offset(w*.34f,h*.84f))
                drawCircle(tint,w*.07f,Offset(w*.66f,h*.84f))
                drawLine(tint,Offset(w*.34f,h*.28f),Offset(w*.34f,h*.42f),stroke.width)
                drawLine(tint,Offset(w*.66f,h*.28f),Offset(w*.66f,h*.42f),stroke.width)
            }
            Glyph.CHEVRON -> {
                drawLine(tint,Offset(w*.38f,h*.25f),Offset(w*.65f,cy),stroke.width)
                drawLine(tint,Offset(w*.65f,cy),Offset(w*.38f,h*.75f),stroke.width)
            }
            Glyph.BACK -> {
                drawLine(tint,Offset(w*.70f,cy),Offset(w*.30f,cy),stroke.width)
                drawLine(tint,Offset(w*.30f,cy),Offset(w*.50f,h*.30f),stroke.width)
                drawLine(tint,Offset(w*.30f,cy),Offset(w*.50f,h*.70f),stroke.width)
            }
            Glyph.PLUS -> {
                drawLine(tint, Offset(cx, h*.24f), Offset(cx, h*.76f), stroke.width)
                drawLine(tint, Offset(w*.24f, cy), Offset(w*.76f, cy), stroke.width)
            }
            Glyph.CLOCK -> {
                drawCircle(tint,w*.34f,Offset(cx,cy),style=stroke)
                drawLine(tint,Offset(cx,cy),Offset(cx,h*.30f),stroke.width)
                drawLine(tint,Offset(cx,cy),Offset(w*.67f,h*.58f),stroke.width)
            }
            Glyph.ROUTE -> {
                drawCircle(tint,w*.10f,Offset(w*.24f,h*.32f),style=stroke)
                drawCircle(tint,w*.10f,Offset(w*.74f,h*.68f),style=stroke)
                val p=Path().apply{moveTo(w*.31f,h*.35f);cubicTo(w*.45f,h*.05f,w*.55f,h*.95f,w*.67f,h*.66f)}
                drawPath(p,tint,style=stroke)
            }
            Glyph.SETTINGS -> {
                drawCircle(tint,w*.27f,Offset(cx,cy),style=stroke)
                for (i in 0 until 8) {
                    val a=i*Math.PI/4
                    val x1=cx+(w*.35f*kotlin.math.cos(a)).toFloat()
                    val y1=cy+(w*.35f*kotlin.math.sin(a)).toFloat()
                    val x2=cx+(w*.46f*kotlin.math.cos(a)).toFloat()
                    val y2=cy+(w*.46f*kotlin.math.sin(a)).toFloat()
                    drawLine(tint,Offset(x1,y1),Offset(x2,y2),stroke.width)
                }
            }
            Glyph.BELL -> {
                drawArc(tint,180f,180f,false,Offset(w*.24f,h*.15f),Size(w*.52f,h*.62f),style=stroke)
                drawLine(tint,Offset(w*.24f,h*.47f),Offset(w*.24f,h*.68f),stroke.width)
                drawLine(tint,Offset(w*.76f,h*.47f),Offset(w*.76f,h*.68f),stroke.width)
                drawLine(tint,Offset(w*.20f,h*.68f),Offset(w*.80f,h*.68f),stroke.width)
                drawCircle(tint,w*.05f,Offset(cx,h*.80f))
            }
            Glyph.LANGUAGE -> {
                drawCircle(tint,w*.35f,Offset(cx,cy),style=stroke)
                drawOval(tint,Offset(w*.32f,h*.15f),Size(w*.36f,h*.70f),style=stroke)
                drawLine(tint,Offset(w*.15f,cy),Offset(w*.85f,cy),stroke.width)
            }
            Glyph.HELP -> {
                drawCircle(tint,w*.37f,Offset(cx,cy),style=stroke)
                drawArc(tint,205f,125f,false,Offset(w*.35f,h*.28f),Size(w*.30f,h*.28f),style=stroke)
                drawCircle(tint,w*.04f,Offset(cx,h*.75f))
            }
            Glyph.INFO -> {
                drawCircle(tint,w*.37f,Offset(cx,cy),style=stroke)
                drawCircle(tint,w*.05f,Offset(cx,h*.32f))
                drawLine(tint,Offset(cx,h*.45f),Offset(cx,h*.72f),stroke.width)
            }
            Glyph.LOGOUT -> {
                drawLine(tint,Offset(w*.44f,h*.20f),Offset(w*.44f,h*.80f),stroke.width)
                drawLine(tint,Offset(w*.44f,h*.50f),Offset(w*.78f,h*.50f),stroke.width)
                drawLine(tint,Offset(w*.78f,h*.50f),Offset(w*.62f,h*.34f),stroke.width)
                drawLine(tint,Offset(w*.78f,h*.50f),Offset(w*.62f,h*.66f),stroke.width)
                drawArc(tint,90f,180f,false,Offset(w*.14f,h*.20f),Size(w*.48f,h*.60f),style=stroke)
            }
            Glyph.PLAY -> {
                val p=Path().apply{moveTo(w*.34f,h*.22f);lineTo(w*.74f,cy);lineTo(w*.34f,h*.78f);close()}
                drawPath(p,tint)
            }
            Glyph.PAUSE -> {
                drawRoundRect(tint,Offset(w*.28f,h*.22f),Size(w*.14f,h*.56f),cornerRadius = androidx.compose.ui.geometry.CornerRadius(w*.03f, w*.03f))
                drawRoundRect(tint,Offset(w*.58f,h*.22f),Size(w*.14f,h*.56f),cornerRadius = androidx.compose.ui.geometry.CornerRadius(w*.03f, w*.03f))
            }
            Glyph.CHECK -> {
                drawLine(tint,Offset(w*.22f,h*.52f),Offset(w*.42f,h*.72f),stroke.width)
                drawLine(tint,Offset(w*.42f,h*.72f),Offset(w*.78f,h*.30f),stroke.width)
            }
            Glyph.SHIELD -> {
                val p=Path().apply{moveTo(cx,h*.12f);lineTo(w*.78f,h*.25f);lineTo(w*.74f,h*.62f);cubicTo(w*.65f,h*.78f,w*.52f,h*.88f,cx,h*.90f);cubicTo(w*.48f,h*.88f,w*.35f,h*.78f,w*.26f,h*.62f);lineTo(w*.22f,h*.25f);close()}
                drawPath(p,tint,style=stroke)
            }
        }
    }
}
