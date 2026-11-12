package com.safar.app.ui.screens

import com.safar.app.R
import android.content.Context
import android.graphics.PointF
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.safar.app.domain.model.GeoPoint
import com.safar.app.domain.model.PassengerLocation
import com.safar.app.domain.model.Route
import com.safar.app.domain.model.Vehicle
import com.safar.app.domain.model.VehicleLocation
import com.safar.app.ui.theme.LocalSafarDarkMode
import androidx.compose.foundation.BorderStroke
import com.safar.app.ui.theme.SafarBlue
import com.safar.app.ui.theme.SafarNavy
import com.safar.app.ui.theme.SafarWhite
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

private const val OPEN_FREE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val OPEN_FREE_MAP_DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val ROUTE_SOURCE_ID = "safar-route-source"
private const val ROUTE_LAYER_ID = "safar-route-layer"
private const val ROUTE_CASING_LAYER_ID = "safar-route-casing-layer"
private const val ROUTE_STATION_SOURCE_ID = "safar-route-station-source"
private const val ROUTE_STATION_CIRCLE_LAYER_ID = "safar-route-station-circle-layer"
private const val ROUTE_STATION_LABEL_LAYER_ID = "safar-route-station-label-layer"
private const val USER_HEADING_SOURCE_ID = "safar-user-heading-source"
private const val USER_HEADING_LAYER_ID = "safar-user-heading-layer"
private const val USER_ACCURACY_SOURCE_ID = "safar-user-accuracy-source"
private const val USER_ACCURACY_FILL_LAYER_ID = "safar-user-accuracy-fill-layer"
private const val USER_ACCURACY_OUTLINE_LAYER_ID = "safar-user-accuracy-outline-layer"
private const val USER_LOCATION_SOURCE_ID = "safar-user-location-source"
private const val VEHICLE_HEADING_SOURCE_ID = "safar-vehicle-heading-source"
private const val VEHICLE_HEADING_LAYER_ID = "safar-vehicle-heading-layer"
private const val USER_LOCATION_CIRCLE_LAYER_ID = "safar-user-location-circle-layer"
private const val USER_LOCATION_PULSE_LAYER_ID = "safar-user-location-pulse-layer"
private const val VEHICLES_SOURCE_ID = "safar-vehicles-source"
private const val PASSENGERS_SOURCE_ID = "safar-passengers-source"
private const val PASSENGERS_LAYER_ID = "safar-passengers-layer"
private const val PASSENGER_MARKER_IMAGE_ID = "safar-passenger-marker-image"
private const val VEHICLES_LAYER_ID = "safar-vehicles-layer"
private const val VEHICLE_MARKER_IMAGE_ID = "safar-bus-marker-image"
private const val VEHICLE_HEADING_PROPERTY = "heading_degrees"
private const val BOARDED_VEHICLE_SOURCE_ID = "safar-boarded-vehicle-source"
private const val BOARDED_VEHICLE_LAYER_ID = "safar-boarded-vehicle-layer"
private const val BOARDED_VEHICLE_IMAGE_ID = "safar-boarded-vehicle-image"
private const val VEHICLE_LABEL_LAYER_ID = "safar-vehicle-label-layer"

private const val DEFAULT_LATITUDE = 40.2829
private const val DEFAULT_LONGITUDE = 69.6228
private const val DEFAULT_ZOOM = 12.0
private const val USER_FOCUS_ZOOM = 16.0
private const val FOLLOW_CAMERA_ANIMATION_MS = 650
private const val USER_ACCURACY_RADIUS_METERS = 27.5
private const val USER_MARKER_OUTER_RADIUS_PIXELS = 8.25f
private const val MAX_MAP_ZOOM = 20.5

@Composable
internal fun PassengerMapView(
    route: Route?,
    passengerLocation: GeoPoint?,
    locationAccuracyMeters: Double? = null,
    headingDegrees: Float?,
    locationPermissionGranted: Boolean,
    locateRequestId: Long = 0L,
    onMapCenterChanged: (GeoPoint) -> Unit = {},
    vehicleLocations: List<Pair<Vehicle, VehicleLocation>>,
    passengerLocations: List<PassengerLocation> = emptyList(),
    onLocateUser: () -> Unit = {},
    nearestVehicle: Pair<Vehicle, VehicleLocation>? = null,
    nearestVehicleRequestId: Long = 0L,
    focusPoint: GeoPoint? = null,
    focusPointRequestId: Long = 0L,
    routeOpacity: Float = 0.78f,
    // When set, this vehicle and the user's blue dot are replaced by a single
    // "you are riding in this vehicle" marker.
    boardedVehicleId: String? = null,
    // Called with the vehicle id when the user taps a vehicle icon.
    onVehicleClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var mapStarted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(250L)
        mapStarted = true
    }

    if (!mapStarted) {
        Box(modifier = modifier)
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var initialCameraSet by remember { mutableStateOf(false) }
    var cameraIdleRevision by remember { mutableLongStateOf(0L) }
    var accuracyCircleNeeded by remember { mutableStateOf(false) }
    val latestPassengerLocation by rememberUpdatedState(passengerLocation)
    val latestLocationAccuracy by rememberUpdatedState(locationAccuracyMeters)
    val latestOnVehicleClick by rememberUpdatedState(onVehicleClick)
    // The blue user marker is hidden while the passenger rides in a vehicle.
    val markerLocation = if (boardedVehicleId != null) null else passengerLocation
    val accuracyCircleProgress by animateFloatAsState(
        targetValue = if (accuracyCircleNeeded) 1f else 0f,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "user-accuracy-circle-growth",
    )
    val darkMode = LocalSafarDarkMode.current
    var centeredOnUser by remember { mutableStateOf(false) }
    var followingUser by remember { mutableStateOf(false) }
    var lastCameraRouteId by remember { mutableStateOf<String?>(null) }
    val vehicleLocationStabilizer = remember(route?.id) { VehicleLocationStabilizer() }
    val stabilizedVehicleLocations = vehicleLocationStabilizer.stabilize(vehicleLocations)
    val mapReady = rememberMapLibre(context)
    if (!mapReady) {
        Box(
            modifier = modifier.background(SafarWhite),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Text(
                text = "Карта временно недоступна",
                color = SafarNavy,
            )
        }
        return
    }

    val mapView = remember(context) {
        // Use the OpenGL-only MapLibre artifact on physical devices.
        // The multi-backend artifact defaults to Vulkan in MapLibre 13.x, and
        // Vulkan has known device-specific native crashes. The OpenGL artifact
        // keeps the map on the stable renderer path.
        MapLibre.getInstance(context.applicationContext)
        MapView(context)
    }

    LaunchedEffect(map, darkMode) {
        val loadedMap = map ?: return@LaunchedEffect
        styleReady = false
        val styleUri = if (darkMode) OPEN_FREE_MAP_DARK_STYLE else OPEN_FREE_MAP_STYLE
        loadedMap.setStyle(Style.Builder().fromUri(styleUri)) { style ->
            setupMapLayers(style, context)
            styleReady = true
            if (!initialCameraSet) {
                loadedMap.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE))
                    .zoom(DEFAULT_ZOOM)
                    .build()
                onMapCenterChanged(GeoPoint(DEFAULT_LATITUDE, DEFAULT_LONGITUDE))
                initialCameraSet = true
            }
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        var started = false
        var resumed = false

        fun syncLifecycle() {
            // MapLibre's native renderer must not be started before the MapView
            // is attached to a window. Starting it too early can produce a
            // device-specific native render-thread crash on some physical phones.
            if (!mapView.isAttachedToWindow) return
            val state = lifecycleOwner.lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.STARTED) && !started) {
                mapView.onStart()
                started = true
            }
            if (state.isAtLeast(Lifecycle.State.RESUMED) && !resumed) {
                mapView.onResume()
                resumed = true
            }
        }

        val attachListener = object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                syncLifecycle()
            }

            override fun onViewDetachedFromWindow(v: android.view.View) {
                if (resumed) {
                    mapView.onPause()
                    resumed = false
                }
                if (started) {
                    mapView.onStop()
                    started = false
                }
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            if (mapView.isAttachedToWindow) {
                when (event) {
                Lifecycle.Event.ON_START -> {
                    if (!started) {
                        mapView.onStart()
                        started = true
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (!resumed) {
                        mapView.onResume()
                        resumed = true
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    if (resumed) {
                        mapView.onPause()
                        resumed = false
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    if (started) {
                        mapView.onStop()
                        started = false
                    }
                }
                    else -> Unit
                }
            }
        }

        mapView.addOnAttachStateChangeListener(attachListener)
        lifecycleOwner.lifecycle.addObserver(observer)
        syncLifecycle()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.removeOnAttachStateChangeListener(attachListener)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = {
                mapView.apply {
                    // Create the MapView lifecycle only after Compose has created
                    // the view instance. Lifecycle start/resume is handled below
                    // after the view is actually attached to the window.
                    onCreate(Bundle())
                    getMapAsync { loadedMap ->
                        map = loadedMap
                        loadedMap.addOnCameraIdleListener {
                            accuracyCircleNeeded = shouldShowAccuracyCircle(
                                map = loadedMap,
                                mapView = this,
                                point = latestPassengerLocation,
                                accuracyMeters = latestLocationAccuracy,
                            )
                            cameraIdleRevision += 1L
                            loadedMap.cameraPosition.target?.let { target ->
                                onMapCenterChanged(GeoPoint(target.latitude, target.longitude))
                            }
                        }
                        loadedMap.addOnCameraMoveStartedListener { reason ->
                            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                                followingUser = false
                            }
                        }
                        loadedMap.addOnMapClickListener { latLng ->
                            val callback = latestOnVehicleClick ?: return@addOnMapClickListener false
                            val screenPoint = loadedMap.projection.toScreenLocation(latLng)
                            val touchRadius = 28f * resources.displayMetrics.density
                            val area = android.graphics.RectF(
                                screenPoint.x - touchRadius,
                                screenPoint.y - touchRadius,
                                screenPoint.x + touchRadius,
                                screenPoint.y + touchRadius,
                            )
                            val vehicleId = runCatching {
                                loadedMap.queryRenderedFeatures(area, VEHICLES_LAYER_ID, BOARDED_VEHICLE_LAYER_ID)
                            }.getOrDefault(emptyList())
                                .firstNotNullOfOrNull { feature ->
                                    if (feature.hasProperty("vehicle_id")) feature.getStringProperty("vehicle_id") else null
                                }
                            if (vehicleId != null) {
                                callback(vehicleId)
                                true
                            } else {
                                false
                            }
                        }
                        loadedMap.setMaxZoomPreference(MAX_MAP_ZOOM)
                        loadedMap.uiSettings.isCompassEnabled = false
                        loadedMap.uiSettings.isScrollGesturesEnabled = true
                        loadedMap.uiSettings.isZoomGesturesEnabled = true
                        loadedMap.uiSettings.isRotateGesturesEnabled = true
                        loadedMap.uiSettings.isTiltGesturesEnabled = true
                        // Style loading is handled by the darkMode-aware effect below.
                    }
                }
            },
            // MapLibre sources are updated by narrowly-scoped effects below.
            // Updating every GeoJSON source in AndroidView.update on every Compose
            // recomposition caused route/vehicle layers to be rebuilt for each
            // compass tick and GPS sample, producing severe UI jank.
            update = {},
            modifier = Modifier.matchParentSize(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MapControlButton("⌖", active = followingUser) {
                if (followingUser) {
                    followingUser = false
                } else {
                    followingUser = true
                    onLocateUser()
                    passengerLocation?.let { point ->
                        val zoom = maxOf(map?.cameraPosition?.zoom ?: DEFAULT_ZOOM, USER_FOCUS_ZOOM)
                        map?.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), zoom),
                            FOLLOW_CAMERA_ANIMATION_MS,
                        )
                    }
                }
            }
            MapControlButton("+") {
                map?.animateCamera(CameraUpdateFactory.zoomIn())
            }
            MapControlButton("−") {
                map?.animateCamera(CameraUpdateFactory.zoomOut())
            }
        }
    }

    LaunchedEffect(styleReady, route, routeOpacity) {
        if (!styleReady) return@LaunchedEffect
        map?.style?.let { style ->
            updateRoute(style, route, routeOpacity)
            updateRouteStation(style, route)
        }
    }

    LaunchedEffect(styleReady, markerLocation) {
        if (!styleReady) return@LaunchedEffect
        map?.style?.let { style -> updateUserLocation(style, markerLocation) }
    }

    LaunchedEffect(map, markerLocation, locationAccuracyMeters) {
        val loadedMap = map ?: return@LaunchedEffect
        accuracyCircleNeeded = shouldShowAccuracyCircle(
            map = loadedMap,
            mapView = mapView,
            point = markerLocation,
            accuracyMeters = locationAccuracyMeters,
        )
    }

    LaunchedEffect(
        styleReady,
        markerLocation,
        locationAccuracyMeters,
        accuracyCircleProgress,
        cameraIdleRevision,
    ) {
        if (!styleReady) return@LaunchedEffect
        map?.style?.let { style ->
            updateUserAccuracy(
                style = style,
                map = map,
                mapView = mapView,
                point = markerLocation,
                accuracyMeters = locationAccuracyMeters,
                expansion = accuracyCircleProgress,
            )
        }
    }

    LaunchedEffect(styleReady, accuracyCircleProgress) {
        if (!styleReady) return@LaunchedEffect
        map?.style?.let { style ->
            style.getLayerAs<FillLayer>(USER_ACCURACY_FILL_LAYER_ID)
                ?.setProperties(fillOpacity(accuracyCircleProgress * 0.18f))
            style.getLayerAs<LineLayer>(USER_ACCURACY_OUTLINE_LAYER_ID)
                ?.setProperties(lineOpacity(accuracyCircleProgress * 0.72f))
        }
    }

    LaunchedEffect(styleReady, markerLocation, headingDegrees) {
        if (!styleReady) return@LaunchedEffect
        map?.style?.let { style -> updateHeadingSector(style, markerLocation, headingDegrees) }
    }

    LaunchedEffect(styleReady, passengerLocations, route?.id) {
        if (!styleReady) return@LaunchedEffect
        map?.style?.let { style -> updatePassengerLocations(style, passengerLocations, route?.id) }
    }

    LaunchedEffect(styleReady, stabilizedVehicleLocations, nearestVehicle, boardedVehicleId) {
        if (!styleReady) return@LaunchedEffect
        map?.style?.let { style ->
            val visibleVehicles = displayVehicleLocations(stabilizedVehicleLocations, nearestVehicle)
            val boarded = boardedVehicleId?.let { id ->
                visibleVehicles.firstOrNull { it.first.id == id }
                    ?: stabilizedVehicleLocations.firstOrNull { it.first.id == id }
            }
            val otherVehicles = if (boardedVehicleId == null) visibleVehicles
            else visibleVehicles.filterNot { it.first.id == boardedVehicleId }
            updateVehicles(style, otherVehicles)
            updateVehicleHeadingSectors(style, otherVehicles)
            updateBoardedVehicle(style, boarded)
        }
    }

    // Initial centering happens once, when the first usable passenger/driver
    // location becomes available. Subsequent GPS updates must never move the
    // camera: the user is free to pan and zoom the map.
    LaunchedEffect(styleReady, passengerLocation) {
        if (!styleReady || centeredOnUser || passengerLocation == null) return@LaunchedEffect
        map?.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(passengerLocation.latitude, passengerLocation.longitude),
                USER_FOCUS_ZOOM,
            )
        )
        centeredOnUser = true
    }

    LaunchedEffect(styleReady, followingUser, passengerLocation) {
        if (!styleReady || !followingUser) return@LaunchedEffect
        val point = passengerLocation ?: return@LaunchedEffect
        val loadedMap = map ?: return@LaunchedEffect
        loadedMap.animateCamera(
            CameraUpdateFactory.newLatLng(LatLng(point.latitude, point.longitude)),
            FOLLOW_CAMERA_ANIMATION_MS,
        )
        centeredOnUser = true
    }

    // Route selection only draws the route overlay. It must never take
    // camera priority over the user's actual location or their manual map view.
    // The map is centered on the user once when a real GPS fix first arrives;
    // the locate button remains the explicit way to recenter later.

    // The nearest-vehicle camera move is a one-shot action. Do NOT key this
    // effect by nearestVehicle itself: its coordinates are refreshed by realtime
    // polling, and doing so would silently re-center the map every few seconds.
    LaunchedEffect(styleReady, nearestVehicleRequestId) {
        if (!styleReady || nearestVehicleRequestId <= 0L || nearestVehicle == null) return@LaunchedEffect

        val target = LatLng(
            nearestVehicle.second.latitude,
            nearestVehicle.second.longitude,
        )
        map?.let { loadedMap ->
            // Explicit nearest-vehicle focus: move immediately so no animation
            // can be interrupted by another camera effect.
            loadedMap.cancelTransitions()
            loadedMap.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    target,
                    USER_FOCUS_ZOOM,
                )
            )
            android.util.Log.d(
                "SafarNearest",
                "CAMERA moved to ${target.latitude},${target.longitude}",
            )
        }
        centeredOnUser = true
    }

    LaunchedEffect(styleReady, focusPointRequestId) {
        if (!styleReady || focusPointRequestId <= 0L) return@LaunchedEffect
        val target = focusPoint ?: return@LaunchedEffect
        map?.cancelTransitions()
        map?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(target.latitude, target.longitude),
                USER_FOCUS_ZOOM,
            )
        )
        // Keep the map in user-controlled mode after focusing a passenger.
        // Otherwise the initial-location effect can run again on the next GPS
        // update and pull the camera back to the driver's own position.
        centeredOnUser = true
    }

    LaunchedEffect(locateRequestId) {
        if (!styleReady || locateRequestId <= 0L) return@LaunchedEffect

        val target = passengerLocation ?: return@LaunchedEffect
        map?.cancelTransitions()
        map?.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(target.latitude, target.longitude),
                USER_FOCUS_ZOOM,
            )
        )
        centeredOnUser = true
    }
}

@Composable
private fun MapControlButton(symbol: String, active: Boolean = false, onClick: () -> Unit) {
    val darkMode = LocalSafarDarkMode.current
    androidx.compose.material3.Surface(
        modifier = Modifier.size(46.dp).clickable(onClick = onClick),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = if (active) androidx.compose.material3.MaterialTheme.colorScheme.primary
            else androidx.compose.material3.MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (darkMode) Color(0xFF343B48) else Color(0xFFE3E9F2),
        ),
        shadowElevation = 1.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            androidx.compose.material3.Text(
                text = symbol,
                color = if (active) Color.White else androidx.compose.material3.MaterialTheme.colorScheme.primary,
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun rememberMapLibre(context: Context): Boolean {
    return remember(context.applicationContext) {
        runCatching {
            if (!MapLibre.hasInstance()) {
                MapLibre.getInstance(context.applicationContext)
            }
            true
        }.onFailure { error ->
            Log.e("SafarMap", "MapLibre initialization failed", error)
        }.getOrDefault(false)
    }
}

private fun setupMapLayers(style: Style, context: Context) {
    if (style.getSource(ROUTE_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }
    if (style.getSource(USER_HEADING_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(USER_HEADING_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }
    if (style.getSource(USER_ACCURACY_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(USER_ACCURACY_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }
    if (style.getSource(USER_LOCATION_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(USER_LOCATION_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }
    if (style.getSource(VEHICLES_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(VEHICLES_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }
    if (style.getSource(VEHICLE_HEADING_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(VEHICLE_HEADING_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }
    if (style.getSource(PASSENGERS_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(PASSENGERS_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }
    if (style.getSource(ROUTE_STATION_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(ROUTE_STATION_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }
    if (style.getSource(BOARDED_VEHICLE_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(BOARDED_VEHICLE_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }

    if (style.getLayer(ROUTE_CASING_LAYER_ID) == null) {
        style.addLayer(
            LineLayer(ROUTE_CASING_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                lineColor(Color.White.toArgb()), lineWidth(9f), lineCap("round"), lineJoin("round")
            )
        )
    }
    if (style.getLayer(ROUTE_LAYER_ID) == null) {
        style.addLayer(
            LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                lineColor(SafarBlue.toArgb()), lineWidth(5f), lineCap("round"), lineJoin("round"), lineOpacity(0.78f)
            )
        )
    }
    if (style.getLayer(ROUTE_STATION_CIRCLE_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(ROUTE_STATION_CIRCLE_LAYER_ID, ROUTE_STATION_SOURCE_ID).withProperties(
                circleColor(SafarBlue.toArgb()),
                circleRadius(11f),
                circleStrokeColor(Color.White.toArgb()),
                circleStrokeWidth(2.5f),
            )
        )
    }
    if (style.getLayer(USER_HEADING_LAYER_ID) == null) {
        style.addLayer(
            FillLayer(USER_HEADING_LAYER_ID, USER_HEADING_SOURCE_ID).withProperties(
                fillColor(SafarBlue.toArgb()), fillOpacity(0.20f)
            )
        )
    }
    // Separate live heading sectors for route vehicles. These polygons use the
    // same compass heading as the driver's phone, not the direction of travel.
    if (style.getLayer(VEHICLE_HEADING_LAYER_ID) == null) {
        style.addLayer(
            FillLayer(VEHICLE_HEADING_LAYER_ID, VEHICLE_HEADING_SOURCE_ID).withProperties(
                fillColor(SafarBlue.toArgb()),
                fillOpacity(0f), // Heading is conveyed by vehicle icon rotation, not a visible blue sector.
            )
        )
    }
    // Use the Android PNG resource for the vehicle marker instead of drawing a substitute in code.
    if (style.getImage(VEHICLE_MARKER_IMAGE_ID) == null) {
        val markerBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.safar_vehicle_marker)
            ?: error("Cannot decode vehicle marker resource: safar_vehicle_marker.png")
        // The feature's compass azimuth rotates this transparent PNG on the map.
        style.addImage(VEHICLE_MARKER_IMAGE_ID, markerBitmap)
    }
    // Passenger marker inspired by the generated Safar-style blue pin and white person glyph.
    if (style.getImage(PASSENGER_MARKER_IMAGE_ID) == null) {
        style.addImage(PASSENGER_MARKER_IMAGE_ID, createPassengerMarkerBitmap())
    }
    style.getLayer(VEHICLES_LAYER_ID)?.let { style.removeLayer(VEHICLES_LAYER_ID) }
    style.addLayer(
        SymbolLayer(VEHICLES_LAYER_ID, VEHICLES_SOURCE_ID).withProperties(
            iconImage(VEHICLE_MARKER_IMAGE_ID),
            iconSize(0.42f),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
            // The bus PNG is portrait-shaped and its front points to the top
            // edge. Compass azimuth is clockwise from north. Rotate the icon in
            // screen space so the phone's compass bearing remains visually aligned
            // regardless of the map camera's own rotation.
            iconRotate(Expression.get(VEHICLE_HEADING_PROPERTY)),
            iconRotationAlignment("viewport"),
        )
    )

    if (style.getImage(BOARDED_VEHICLE_IMAGE_ID) == null) {
        style.getImage(VEHICLE_MARKER_IMAGE_ID)?.let { busBitmap ->
            style.addImage(BOARDED_VEHICLE_IMAGE_ID, createBoardedVehicleBitmap(busBitmap))
        }
    }
    style.getLayer(BOARDED_VEHICLE_LAYER_ID)?.let { style.removeLayer(BOARDED_VEHICLE_LAYER_ID) }
    style.addLayer(
        SymbolLayer(BOARDED_VEHICLE_LAYER_ID, BOARDED_VEHICLE_SOURCE_ID).withProperties(
            iconImage(BOARDED_VEHICLE_IMAGE_ID),
            iconSize(0.5f),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
            iconRotationAlignment("viewport"),
        )
    )

    if (style.getLayer(PASSENGERS_LAYER_ID) == null) {
        style.addLayer(
            SymbolLayer(PASSENGERS_LAYER_ID, PASSENGERS_SOURCE_ID).withProperties(
                iconImage(PASSENGER_MARKER_IMAGE_ID),
                iconSize(0.42f),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconRotationAlignment("viewport"),
            )
        )
    }

    if (style.getLayer(USER_LOCATION_PULSE_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(USER_LOCATION_PULSE_LAYER_ID, USER_LOCATION_SOURCE_ID).withProperties(
                circleColor(SafarBlue.toArgb()),
                circleRadius(11f),
                circleOpacity(0.12f),
            )
        )
    }
    if (style.getLayer(USER_LOCATION_CIRCLE_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(USER_LOCATION_CIRCLE_LAYER_ID, USER_LOCATION_SOURCE_ID).withProperties(
                circleColor(SafarBlue.toArgb()),
                circleRadius(7f),
                circleStrokeColor(Color.White.toArgb()),
                circleStrokeWidth(2.5f),
            )
        )
    }

    // Rebuild the user-direction layers in a deterministic order on every
    // style load: pulse -> compass sector -> location dot. Do not rely on relative
    // insertion order left over from the initial style setup.
    style.getLayer(USER_HEADING_LAYER_ID)?.let { style.removeLayer(it) }
    style.getLayer(USER_ACCURACY_OUTLINE_LAYER_ID)?.let { style.removeLayer(it) }
    style.getLayer(USER_ACCURACY_FILL_LAYER_ID)?.let { style.removeLayer(it) }
    style.getLayer(USER_LOCATION_CIRCLE_LAYER_ID)?.let { style.removeLayer(it) }
    style.getLayer(USER_LOCATION_PULSE_LAYER_ID)?.let { style.removeLayer(it) }

    style.addLayer(
        FillLayer(USER_ACCURACY_FILL_LAYER_ID, USER_ACCURACY_SOURCE_ID).withProperties(
            fillColor(SafarBlue.toArgb()),
            fillOpacity(0f),
        )
    )
    style.addLayer(
        LineLayer(USER_ACCURACY_OUTLINE_LAYER_ID, USER_ACCURACY_SOURCE_ID).withProperties(
            lineColor(Color.White.toArgb()),
            lineWidth(1.5f),
            lineOpacity(0f),
        )
    )
    style.addLayer(
        CircleLayer(USER_LOCATION_PULSE_LAYER_ID, USER_LOCATION_SOURCE_ID).withProperties(
            circleColor(SafarBlue.toArgb()),
            circleRadius(11f),
            circleOpacity(0.12f),
        )
    )
    style.addLayer(
        FillLayer(USER_HEADING_LAYER_ID, USER_HEADING_SOURCE_ID).withProperties(
            fillColor(SafarBlue.toArgb()),
            fillOpacity(0.20f),
        )
    )
    style.addLayer(
        CircleLayer(USER_LOCATION_CIRCLE_LAYER_ID, USER_LOCATION_SOURCE_ID).withProperties(
            circleColor(SafarBlue.toArgb()),
            circleRadius(7f),
            circleStrokeColor(Color.White.toArgb()),
            circleStrokeWidth(2.5f),
        )
    )
}


private fun createPassengerMarkerBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(96, 120, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    val pin = android.graphics.Path().apply {
        moveTo(48f, 116f)
        cubicTo(40f, 103f, 8f, 70f, 8f, 44f)
        cubicTo(8f, 21f, 26f, 4f, 48f, 4f)
        cubicTo(70f, 4f, 88f, 21f, 88f, 44f)
        cubicTo(88f, 70f, 56f, 103f, 48f, 116f)
        close()
    }
    paint.color = SafarBlue.toArgb()
    canvas.drawPath(pin, paint)

    // White ring and person silhouette echo the generated marker design.
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = 3.5f
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(48f, 42f, 29f, paint)
    paint.style = android.graphics.Paint.Style.FILL
    canvas.drawCircle(48f, 33f, 9.5f, paint)
    val shoulders = android.graphics.Path().apply {
        moveTo(28f, 63f)
        cubicTo(30f, 51f, 38f, 47f, 48f, 47f)
        cubicTo(58f, 47f, 66f, 51f, 68f, 63f)
        cubicTo(61f, 70f, 35f, 70f, 28f, 63f)
        close()
    }
    canvas.drawPath(shoulders, paint)
    return bitmap
}

private fun updatePassengerLocations(
    style: Style,
    locations: List<PassengerLocation>,
    routeId: String?,
) {
    val source = style.getSourceAs<GeoJsonSource>(PASSENGERS_SOURCE_ID) ?: return
    val features = PassengerLocationVisibility.visibleOnRoute(locations, routeId).map { passenger ->
            Feature.fromGeometry(Point.fromLngLat(passenger.longitude, passenger.latitude)).apply {
                addStringProperty("passenger_id", passenger.passengerId)
                addStringProperty("route_id", passenger.routeId)
            }
        }
        .toList()
    source.setGeoJson(FeatureCollection.fromFeatures(features.toTypedArray()))
}

private fun updateUserLocation(style: Style, point: GeoPoint?) {
    val source = style.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE_ID) ?: return

    if (point == null) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }

    source.setGeoJson(
        FeatureCollection.fromFeature(
            Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))
        )
    )
}

private fun updateUserAccuracy(
    style: Style,
    map: MapLibreMap?,
    mapView: MapView,
    point: GeoPoint?,
    accuracyMeters: Double?,
    expansion: Float,
) {
    val source = style.getSourceAs<GeoJsonSource>(USER_ACCURACY_SOURCE_ID) ?: return
    val loadedMap = map
    if (point == null || loadedMap == null || expansion <= 0f) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }

    val radiusMeters = accuracyRegionRadiusMeters(accuracyMeters)
    val markerGroundRadius = markerGroundRadiusMeters(loadedMap, point, mapView)
        ?.coerceAtMost(radiusMeters)
        ?: radiusMeters
    // Animate outward from the visible blue dot, but stop at the 25–30 m
    // uncertainty region. The geographic radius never grows beyond that area;
    // map projection makes it naturally larger on screen as the user zooms in.
    val animatedRadiusMeters = markerGroundRadius + (radiusMeters - markerGroundRadius) * expansion
    val earthRadiusMeters = 6_371_000.0
    val latitudeRadians = Math.toRadians(point.latitude)
    val angularRadius = animatedRadiusMeters / earthRadiusMeters
    val ring = (0..48).map { step ->
        val bearing = 2.0 * Math.PI * step / 48.0
        val latitude = Math.asin(
            Math.sin(latitudeRadians) * Math.cos(angularRadius) +
                Math.cos(latitudeRadians) * Math.sin(angularRadius) * Math.cos(bearing)
        )
        val longitude = Math.toRadians(point.longitude) + Math.atan2(
            Math.sin(bearing) * Math.sin(angularRadius) * Math.cos(latitudeRadians),
            Math.cos(angularRadius) - Math.sin(latitudeRadians) * Math.sin(latitude),
        )
        Point.fromLngLat(Math.toDegrees(longitude), Math.toDegrees(latitude))
    }
    source.setGeoJson(
        FeatureCollection.fromFeature(
            Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
        )
    )
}

private fun shouldShowAccuracyCircle(
    map: MapLibreMap,
    mapView: MapView,
    point: GeoPoint?,
    accuracyMeters: Double?,
): Boolean {
    if (point == null) return false
    val markerGroundRadius = markerGroundRadiusMeters(map, point, mapView) ?: return false
    return markerGroundRadius < accuracyRegionRadiusMeters(accuracyMeters)
}

private fun markerGroundRadiusMeters(map: MapLibreMap, point: GeoPoint, mapView: MapView): Double? {
    if (mapView.width <= 0 || mapView.height <= 0) return null
    return runCatching {
        val markerCenter = map.projection.toScreenLocation(LatLng(point.latitude, point.longitude))
        val markerEdge = map.projection.fromScreenLocation(
            PointF(
                markerCenter.x + USER_MARKER_OUTER_RADIUS_PIXELS * mapView.resources.displayMetrics.density,
                markerCenter.y,
            )
        )
        distanceMeters(point.latitude, point.longitude, markerEdge.latitude, markerEdge.longitude)
    }.getOrNull()
}

private fun accuracyRegionRadiusMeters(accuracyMeters: Double?): Double =
    accuracyMeters?.takeIf { it.isFinite() && it > 0.0 }
        ?.coerceIn(25.0, 30.0)
        ?: USER_ACCURACY_RADIUS_METERS

private fun distanceMeters(startLatitude: Double, startLongitude: Double, endLatitude: Double, endLongitude: Double): Double {
    val earthRadiusMeters = 6_371_000.0
    val latitudeDelta = Math.toRadians(endLatitude - startLatitude)
    val longitudeDelta = Math.toRadians(endLongitude - startLongitude)
    val startLatitudeRadians = Math.toRadians(startLatitude)
    val endLatitudeRadians = Math.toRadians(endLatitude)
    val haversine = kotlin.math.sin(latitudeDelta / 2.0) * kotlin.math.sin(latitudeDelta / 2.0) +
        kotlin.math.cos(startLatitudeRadians) * kotlin.math.cos(endLatitudeRadians) *
        kotlin.math.sin(longitudeDelta / 2.0) * kotlin.math.sin(longitudeDelta / 2.0)
    return 2.0 * earthRadiusMeters * kotlin.math.asin(kotlin.math.sqrt(haversine.coerceIn(0.0, 1.0)))
}

private fun updateRoute(style: Style, route: Route?, opacity: Float) {
    val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID) ?: return
    val path = route?.path.orEmpty()
    if (path.size < 2) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }

    val coordinates = path.map { Point.fromLngLat(it.longitude, it.latitude) }
    source.setGeoJson(
        FeatureCollection.fromFeature(
            Feature.fromGeometry(LineString.fromLngLats(coordinates))
        )
    )
    style.getLayerAs<LineLayer>(ROUTE_LAYER_ID)?.setProperties(lineOpacity(opacity))
}

private fun updateRouteStation(style: Style, route: Route?) {
    val source = style.getSourceAs<GeoJsonSource>(ROUTE_STATION_SOURCE_ID) ?: return
    val path = route?.path.orEmpty()
    if (path.isEmpty()) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }

    // A small station/start marker placed along the route rather than at the endpoint.
    val point = path[path.size / 3.coerceAtMost(path.lastIndex)]
    source.setGeoJson(
        FeatureCollection.fromFeature(
            Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude))
        )
    )
}

private fun updateHeadingSector(style: Style, center: GeoPoint?, headingDegrees: Float?) {
    val source = style.getSourceAs<GeoJsonSource>(USER_HEADING_SOURCE_ID) ?: return
    if (center == null) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }
    // Keep the passenger's blue direction cone visible even when the compass
    // has not supplied a heading yet; use north as the temporary fallback.
    val visibleHeading = headingDegrees?.takeIf { it.isFinite() } ?: 0f
    val radius = 0.00065
    val halfAngle = 24.0
    val points = mutableListOf<Point>()
    points += Point.fromLngLat(center.longitude, center.latitude)
    for (step in 0..12) {
        val angle = Math.toRadians((visibleHeading - halfAngle) + (2 * halfAngle * step / 12.0))
        val lat = center.latitude + radius * Math.cos(angle)
        val lon = center.longitude + radius * Math.sin(angle) / Math.cos(Math.toRadians(center.latitude))
        points += Point.fromLngLat(lon, lat)
    }
    points += Point.fromLngLat(center.longitude, center.latitude)
    val polygon = Polygon.fromLngLats(listOf(points))
    source.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(polygon)))
}


private fun displayVehicleLocations(
    locations: List<Pair<Vehicle, VehicleLocation>>,
    nearest: Pair<Vehicle, VehicleLocation>?,
): List<Pair<Vehicle, VehicleLocation>> {
    if (nearest == null || locations.any { it.first.id == nearest.first.id }) return locations
    return locations + nearest
}

private fun updateVehicleHeadingSectors(
    style: Style,
    locations: List<Pair<Vehicle, VehicleLocation>>,
) {
    val source = style.getSourceAs<GeoJsonSource>(VEHICLE_HEADING_SOURCE_ID) ?: return
    val features = locations.mapNotNull { (vehicle, location) ->
        val heading = location.headingDegrees?.takeIf { it.isFinite() } ?: return@mapNotNull null
        val normalized = ((heading % 360f) + 360f) % 360f
        val center = GeoPoint(location.latitude, location.longitude)
        val radiusMeters = 42.0
        val halfAngleDegrees = 28.0
        val points = mutableListOf<Point>()
        points += Point.fromLngLat(center.longitude, center.latitude)
        for (step in 0..16) {
            val angle = Math.toRadians(
                normalized.toDouble() - halfAngleDegrees +
                    (2.0 * halfAngleDegrees * step / 16.0)
            )
            val northMeters = radiusMeters * kotlin.math.cos(angle)
            val eastMeters = radiusMeters * kotlin.math.sin(angle)
            val latitude = center.latitude + northMeters / 111_320.0
            val longitude = center.longitude +
                eastMeters / (111_320.0 * kotlin.math.cos(Math.toRadians(center.latitude)).coerceAtLeast(0.01))
            points += Point.fromLngLat(longitude, latitude)
        }
        points += Point.fromLngLat(center.longitude, center.latitude)
        Feature.fromGeometry(Polygon.fromLngLats(listOf(points))).apply {
            addStringProperty("vehicle_id", vehicle.id)
            addNumberProperty(VEHICLE_HEADING_PROPERTY, normalized.toDouble())
        }
    }
    source.setGeoJson(FeatureCollection.fromFeatures(features.toTypedArray()))
}

private fun updateBoardedVehicle(style: Style, boarded: Pair<Vehicle, VehicleLocation>?) {
    val source = style.getSourceAs<GeoJsonSource>(BOARDED_VEHICLE_SOURCE_ID) ?: return
    if (boarded == null) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }
    val (vehicle, location) = boarded
    source.setGeoJson(
        FeatureCollection.fromFeature(
            Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude)).apply {
                addStringProperty("vehicle_id", vehicle.id)
                addStringProperty("identifier", vehicle.identifier)
            }
        )
    )
}

/** Blue round badge with a white ring and the bus marker inside it. */
private fun createBoardedVehicleBitmap(busBitmap: Bitmap): Bitmap {
    val size = 160
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val center = size / 2f
    paint.color = android.graphics.Color.argb(70, 30, 120, 255)
    canvas.drawCircle(center, center, center - 2f, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, center, center - 18f, paint)
    paint.color = SafarBlue.toArgb()
    canvas.drawCircle(center, center, center - 26f, paint)
    val inner = (size - 64).toFloat()
    val scale = minOf(inner / busBitmap.width, inner / busBitmap.height)
    val w = busBitmap.width * scale
    val h = busBitmap.height * scale
    val dst = android.graphics.RectF(center - w / 2f, center - h / 2f, center + w / 2f, center + h / 2f)
    canvas.drawBitmap(busBitmap, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    return bitmap
}

private fun updateVehicles(
    style: Style,
    locations: List<Pair<Vehicle, VehicleLocation>>,
) {
    val source = style.getSourceAs<GeoJsonSource>(VEHICLES_SOURCE_ID) ?: return
    val features = locations.map { (vehicle, location) ->
        Feature.fromGeometry(
            Point.fromLngLat(location.longitude, location.latitude)
        ).apply {
            addStringProperty("vehicle_id", vehicle.id)
            addStringProperty("identifier", vehicle.identifier)
            val heading = location.headingDegrees
                ?.takeIf { it.isFinite() }
                ?.let { ((it % 360f) + 360f) % 360f }
                ?.toDouble() ?: 0.0
            addNumberProperty(VEHICLE_HEADING_PROPERTY, heading)
        }
    }
    source.setGeoJson(FeatureCollection.fromFeatures(features.toTypedArray()))
}
