package com.safar.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import org.json.JSONObject
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import com.safar.app.domain.model.GeoPoint
import com.safar.app.domain.model.PassengerLocation
import com.safar.app.domain.model.Route
import com.safar.app.domain.model.Vehicle
import com.safar.app.domain.model.VehicleLocation
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
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

private const val OPEN_FREE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val ROUTE_SOURCE_ID = "safar-route-source"
private const val ROUTE_LAYER_ID = "safar-route-layer"
private const val ROUTE_CASING_LAYER_ID = "safar-route-casing-layer"
private const val ROUTE_STATION_SOURCE_ID = "safar-route-station-source"
private const val ROUTE_STATION_CIRCLE_LAYER_ID = "safar-route-station-circle-layer"
private const val ROUTE_STATION_LABEL_LAYER_ID = "safar-route-station-label-layer"
private const val USER_HEADING_SOURCE_ID = "safar-user-heading-source"
private const val USER_HEADING_LAYER_ID = "safar-user-heading-layer"
private const val USER_LOCATION_SOURCE_ID = "safar-user-location-source"
private const val USER_LOCATION_CIRCLE_LAYER_ID = "safar-user-location-circle-layer"
private const val USER_LOCATION_PULSE_LAYER_ID = "safar-user-location-pulse-layer"
private const val VEHICLES_SOURCE_ID = "safar-vehicles-source"
private const val VEHICLES_LAYER_ID = "safar-vehicles-layer"
private const val VEHICLE_ICON_LAYER_ID = "safar-vehicle-icon-layer"
private const val VEHICLE_LABEL_LAYER_ID = "safar-vehicle-label-layer"
private const val VEHICLE_ICON_IMAGE_ID = "safar-vehicle-icon"

private const val DEFAULT_LATITUDE = 40.2829
private const val DEFAULT_LONGITUDE = 69.6228
private const val DEFAULT_ZOOM = 12.0
private const val USER_FOCUS_ZOOM = 15.5

@Composable
internal fun PassengerMapView(
    route: Route?,
    passengerLocation: GeoPoint?,
    headingDegrees: Float?,
    locationPermissionGranted: Boolean,
    locateRequestId: Long = 0L,
    vehicleLocations: List<Pair<Vehicle, VehicleLocation>>,
    passengerLocations: List<PassengerLocation> = emptyList(),
    onLocateUser: () -> Unit = {},
    routeOpacity: Float = 0.78f,
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
    var centeredOnUser by remember { mutableStateOf(false) }
    var locateTarget by remember { mutableStateOf<GeoPoint?>(null) }
    var lastCameraRouteId by remember { mutableStateOf<String?>(null) }
    var lastFocusedVehicleId by remember { mutableStateOf<String?>(null) }
    val vehicleMarkers = remember { mutableMapOf<String, Marker>() }
    val vehicleLocationStabilizer = remember(route?.id) { VehicleLocationStabilizer() }
    val stabilizedVehicleLocations = vehicleLocationStabilizer.stabilize(vehicleLocations)
    var snappedVehicleLocations by remember { mutableStateOf(stabilizedVehicleLocations) }
    val vehicleMarkerIcon = remember(context) {
        IconFactory.getInstance(context).fromBitmap(createVehicleMarkerBitmap())
    }

    rememberMapLibre(context)

    val mapView = remember(context) {
        MapView(context).apply { onCreate(Bundle()) }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = {
                mapView.apply {
                    getMapAsync { loadedMap ->
                        map = loadedMap
                        syncVehicleMarkers(loadedMap, stabilizedVehicleLocations, vehicleMarkerIcon, vehicleMarkers)
                        loadedMap.uiSettings.isCompassEnabled = false
                        loadedMap.uiSettings.isScrollGesturesEnabled = true
                        loadedMap.uiSettings.isZoomGesturesEnabled = true
                        loadedMap.uiSettings.isRotateGesturesEnabled = true
                        loadedMap.uiSettings.isTiltGesturesEnabled = true
                        loadedMap.setStyle(Style.Builder().fromUri(OPEN_FREE_MAP_STYLE)) { style ->
                            setupMapLayers(style, context)
                            styleReady = true
                            loadedMap.cameraPosition = CameraPosition.Builder()
                                .target(LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE))
                                .zoom(DEFAULT_ZOOM)
                                .build()
                        }
                    }
                }
            },
            update = {
                if (styleReady) {
                    map?.style?.let { style ->
                        updateUserLocation(style, passengerLocation)
                        updateRoute(style, route, routeOpacity)
                        updateRouteStation(style, route)
                        updateHeadingSector(style, passengerLocation, headingDegrees)
                    }
                    map?.let { loadedMap ->
                        loadedMap.style?.let { style ->
                            updateVehicles(style, snappedVehicleLocations)
                        }
                        syncVehicleMarkers(
                            loadedMap,
                            snappedVehicleLocations,
                            vehicleMarkerIcon,
                            vehicleMarkers,
                        )
                    }
                }
            },
            modifier = Modifier.matchParentSize(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MapControlButton("⌖") {
                onLocateUser()
            }
            Spacer(Modifier.size(8.dp))
            MapControlButton("+") {
                map?.animateCamera(CameraUpdateFactory.zoomIn())
            }
            Spacer(Modifier.size(8.dp))
            MapControlButton("−") {
                map?.animateCamera(CameraUpdateFactory.zoomOut())
            }
        }
    }

    LaunchedEffect(styleReady, route, passengerLocation, headingDegrees, snappedVehicleLocations) {
        if (!styleReady) return@LaunchedEffect
        map?.style?.let { style ->
            updateRoute(style, route, routeOpacity)
            updateRouteStation(style, route)
            updateHeadingSector(style, passengerLocation, headingDegrees)
            updateUserLocation(style, passengerLocation)
        }

        map?.style?.let { updateVehicles(it, snappedVehicleLocations) }
        map?.let {
            syncVehicleMarkers(
                it,
                snappedVehicleLocations,
                vehicleMarkerIcon,
                vehicleMarkers,
            )
        }

        // Always bring the first active vehicle into view when one appears.
        // This is route-agnostic: route 3, 8, 15, etc. all use the same live
        // vehicle list. Passenger location is used only when there is no active
        // vehicle to focus.
        val vehicle = snappedVehicleLocations.firstOrNull()
        if (vehicle != null && vehicle.first.id != lastFocusedVehicleId) {
            map?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(vehicle.second.latitude, vehicle.second.longitude),
                    USER_FOCUS_ZOOM,
                )
            )
            lastFocusedVehicleId = vehicle.first.id
            centeredOnUser = false
        } else if (vehicle == null && passengerLocation != null && !centeredOnUser) {
            map?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(passengerLocation.latitude, passengerLocation.longitude),
                    USER_FOCUS_ZOOM,
                )
            )
            centeredOnUser = true
        }
    }

    LaunchedEffect(styleReady, route?.id, snappedVehicleLocations) {
        if (!styleReady || route == null || route.id == lastCameraRouteId) return@LaunchedEffect

        // If a live vehicle exists, the vehicle-camera effect above owns the
        // camera. Never move the map back to the route midpoint afterwards.
        // Otherwise the active vehicle can be rendered outside the viewport,
        // which makes the marker look like it disappeared.
        if (snappedVehicleLocations.isNotEmpty()) {
            lastCameraRouteId = route.id
            return@LaunchedEffect
        }

        if (passengerLocation != null && !centeredOnUser) return@LaunchedEffect
        val path = route.path
        if (path.size >= 2) {
            val middle = path[path.size / 2]
            map?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(middle.latitude, middle.longitude),
                    13.5,
                )
            )
        }
        lastCameraRouteId = route.id
    }

    // Road snapping is asynchronous because the reliable fallback uses the
    // OpenStreetMap road network. Do not block the MapLibre UI thread.
    LaunchedEffect(styleReady, stabilizedVehicleLocations) {
        if (!styleReady || stabilizedVehicleLocations.isEmpty()) {
            snappedVehicleLocations = stabilizedVehicleLocations
            return@LaunchedEffect
        }

        delay(500L)

        snappedVehicleLocations = snapVehiclesToRoadNetwork(
            map = map,
            locations = stabilizedVehicleLocations,
        )
    }

    LaunchedEffect(locateRequestId) {
        if (!styleReady || locateRequestId <= 0L) return@LaunchedEffect

        val target = passengerLocation ?: return@LaunchedEffect
        locateTarget = target
        map?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(target.latitude, target.longitude),
                USER_FOCUS_ZOOM,
            )
        )
        centeredOnUser = true
    }
}

@Composable
private fun MapControlButton(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .background(SafarWhite, androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = symbol,
            color = SafarBlue,
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun rememberMapLibre(context: Context) {
    remember(context.applicationContext) {
        if (!MapLibre.hasInstance()) MapLibre.getInstance(context.applicationContext)
        true
    }
}

private fun setupMapLayers(style: Style, context: Context) {
    if (style.getSource(ROUTE_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }
    if (style.getSource(USER_HEADING_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(USER_HEADING_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }
    if (style.getSource(USER_LOCATION_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(USER_LOCATION_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }
    if (style.getSource(VEHICLES_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(VEHICLES_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }
    if (style.getSource(ROUTE_STATION_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(ROUTE_STATION_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
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
    if (style.getLayer(ROUTE_STATION_LABEL_LAYER_ID) == null) {
        style.addLayer(
            SymbolLayer(ROUTE_STATION_LABEL_LAYER_ID, ROUTE_STATION_SOURCE_ID).withProperties(
                textField("СТ"),
                textSize(9f),
                textColor(Color.White.toArgb()),
                textHaloColor(SafarNavy.toArgb()),
                textHaloWidth(0.8f),
                textAllowOverlap(true),
                textIgnorePlacement(true),
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
    // Keep the vehicle marker layer above every route/POI/user layer.
    // The marker must remain visible regardless of which route is selected.
    style.getLayer(VEHICLES_LAYER_ID)?.let { style.removeLayer(VEHICLES_LAYER_ID) }
    style.addLayer(
        CircleLayer(VEHICLES_LAYER_ID, VEHICLES_SOURCE_ID).withProperties(
            circleColor(SafarBlue.toArgb()),
            circleRadius(11f),
            circleStrokeColor(Color.White.toArgb()),
            circleStrokeWidth(3f),
            circleOpacity(1f),
        )
    )
    if (style.getImage(VEHICLE_ICON_IMAGE_ID) == null) {
        style.addImage(VEHICLE_ICON_IMAGE_ID, createVehicleMarkerBitmap())
    }
    style.getLayer(VEHICLE_ICON_LAYER_ID)?.let { style.removeLayer(VEHICLE_ICON_LAYER_ID) }
    style.addLayer(
        SymbolLayer(VEHICLE_ICON_LAYER_ID, VEHICLES_SOURCE_ID).withProperties(
            iconImage(VEHICLE_ICON_IMAGE_ID),
            iconSize(0.95f),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
        )
    )
    if (style.getLayer(VEHICLE_LABEL_LAYER_ID) == null) {
        style.addLayer(
            SymbolLayer(VEHICLE_LABEL_LAYER_ID, VEHICLES_SOURCE_ID).withProperties(
                textField("{identifier}"),
                textSize(12f),
                textColor(Color.White.toArgb()),
                textHaloColor(Color.Black.toArgb()),
                textHaloWidth(1.2f),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            )
        )
    }

    if (style.getLayer(USER_LOCATION_PULSE_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(USER_LOCATION_PULSE_LAYER_ID, USER_LOCATION_SOURCE_ID).withProperties(
                circleColor(SafarBlue.toArgb()),
                circleRadius(16f),
                circleOpacity(0.16f),
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
}


private fun createVehicleMarkerBitmap(): Bitmap {
    // Bright version of the exact visual language of the supplied reference:
    // blue rounded square + white front-facing bus, no circular badge.
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(0, 112, 255)
        style = Paint.Style.FILL
    }
    canvas.drawRoundRect(
        RectF(8f, 8f, 88f, 88f),
        7f,
        7f,
        bluePaint,
    )

    val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }

    // Bus roof/body.
    canvas.drawRoundRect(
        RectF(28f, 20f, 68f, 73f),
        7f,
        7f,
        whitePaint,
    )

    // Windshield.
    canvas.drawRoundRect(
        RectF(32f, 26f, 64f, 46f),
        4f,
        4f,
        bluePaint,
    )
    canvas.drawRect(47f, 26f, 49f, 46f, whitePaint)

    // Front panel.
    canvas.drawRect(33f, 51f, 63f, 61f, bluePaint)

    // Headlights / lower details.
    canvas.drawCircle(36f, 67f, 4.5f, bluePaint)
    canvas.drawCircle(60f, 67f, 4.5f, bluePaint)
    canvas.drawCircle(36f, 67f, 1.8f, whitePaint)
    canvas.drawCircle(60f, 67f, 1.8f, whitePaint)

    // Side mirrors.
    canvas.drawRoundRect(RectF(23f, 37f, 30f, 47f), 3f, 3f, whitePaint)
    canvas.drawRoundRect(RectF(66f, 37f, 73f, 47f), 3f, 3f, whitePaint)

    return bitmap
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
    if (center == null || headingDegrees == null) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }
    val radius = 0.0009
    val halfAngle = 30.0
    val points = mutableListOf<Point>()
    points += Point.fromLngLat(center.longitude, center.latitude)
    for (step in 0..12) {
        val angle = Math.toRadians((headingDegrees - halfAngle) + (2 * halfAngle * step / 12.0))
        val lat = center.latitude + radius * Math.cos(angle)
        val lon = center.longitude + radius * Math.sin(angle) / Math.cos(Math.toRadians(center.latitude))
        points += Point.fromLngLat(lon, lat)
    }
    points += Point.fromLngLat(center.longitude, center.latitude)
    val polygon = Polygon.fromLngLats(listOf(points))
    source.setGeoJson(FeatureCollection.fromFeature(Feature.fromGeometry(polygon)))
}


private suspend fun snapVehiclesToRoadNetwork(
    map: MapLibreMap?,
    locations: List<Pair<Vehicle, VehicleLocation>>,
): List<Pair<Vehicle, VehicleLocation>> {
    if (locations.isEmpty()) return emptyList()

    return locations.map { (vehicle, location) ->
        vehicle to snapOneVehicleToRoad(map, location)
    }
}

private suspend fun snapOneVehicleToRoad(
    map: MapLibreMap?,
    location: VehicleLocation,
): VehicleLocation {
    // First try the loaded Liberty vector tiles. The official Liberty style
    // uses source "openmaptiles" and source-layer "transportation".
    val vectorResult = runCatching {
        snapFromLoadedVectorRoads(map, location)
    }.getOrNull()

    if (vectorResult != null) {
        return vectorResult
    }

    // Reliable fallback: OSRM's Nearest service returns the closest real road
    // coordinate and its distance from the original GPS point.
    return snapFromOsrm(location) ?: location
}

private fun snapFromLoadedVectorRoads(
    map: MapLibreMap?,
    location: VehicleLocation,
): VehicleLocation? {
    val style = map?.style ?: return null
    val source = style.getSourceAs<VectorSource>("openmaptiles") ?: return null

    val features = source.querySourceFeatures(
        arrayOf("transportation"),
        null,
    )

    if (features.isEmpty()) return null

    var bestPoint: GeoPoint? = null
    var bestDistance = Double.MAX_VALUE

    features.forEach { feature ->
        val geometry = feature.geometry() ?: return@forEach

        val lines: List<List<Point>> = when (geometry) {
            is LineString -> listOf(geometry.coordinates())
            is MultiLineString -> geometry.coordinates()
            else -> emptyList()
        }

        lines.forEach { points ->
            for (index in 0 until points.lastIndex) {
                val candidate = projectGpsPointToSegment(
                    location,
                    points[index],
                    points[index + 1],
                )

                if (candidate.distanceMeters < bestDistance) {
                    bestDistance = candidate.distanceMeters
                    bestPoint = candidate.point
                }
            }
        }
    }

    val snapped = bestPoint?.takeIf { bestDistance <= 20.0 } ?: return location

    return location.copy(
        latitude = snapped.latitude,
        longitude = snapped.longitude,
    )
}

private suspend fun snapFromOsrm(
    location: VehicleLocation,
): VehicleLocation? = withContext(Dispatchers.IO) {
    runCatching {
        val url = URL(
            "https://router.project-osrm.org/nearest/v1/driving/" +
                "${location.longitude},${location.latitude}.json?number=1"
        )

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2500
            readTimeout = 2500
            useCaches = false
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return@runCatching null

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            if (json.optString("code") != "Ok") return@runCatching null

            val waypoint = json.optJSONArray("waypoints")
                ?.optJSONObject(0)
                ?: return@runCatching null

            val distance = waypoint.optDouble("distance", Double.NaN)
            val coordinates = waypoint.optJSONArray("location")
                ?: return@runCatching null

            val longitude = coordinates.optDouble(0, Double.NaN)
            val latitude = coordinates.optDouble(1, Double.NaN)

            if (!distance.isFinite() ||
                !longitude.isFinite() ||
                !latitude.isFinite()
            ) {
                return@runCatching null
            }

            // This is the hard rule: OSRM may find a road farther away, but
            // Safar never moves the vehicle unless it is <= 20 meters away.
            if (distance > 20.0) return@runCatching null

            location.copy(
                latitude = latitude,
                longitude = longitude,
            )
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

private data class RoadProjection(
    val point: GeoPoint,
    val distanceMeters: Double,
)

private fun projectGpsPointToSegment(
    location: VehicleLocation,
    start: Point,
    end: Point,
): RoadProjection {
    val point = GeoPoint(location.latitude, location.longitude)
    val startPoint = GeoPoint(start.latitude(), start.longitude())
    val endPoint = GeoPoint(end.latitude(), end.longitude())

    val referenceLatRad = Math.toRadians(
        (point.latitude + startPoint.latitude + endPoint.latitude) / 3.0
    )
    val metersPerLat = 111_320.0
    val metersPerLon = 111_320.0 * kotlin.math.cos(referenceLatRad)

    val px = (point.longitude - startPoint.longitude) * metersPerLon
    val py = (point.latitude - startPoint.latitude) * metersPerLat
    val bx = (endPoint.longitude - startPoint.longitude) * metersPerLon
    val by = (endPoint.latitude - startPoint.latitude) * metersPerLat

    val lengthSquared = bx * bx + by * by
    val t = if (lengthSquared <= 1e-9) {
        0.0
    } else {
        ((px * bx + py * by) / lengthSquared).coerceIn(0.0, 1.0)
    }

    val projected = GeoPoint(
        latitude = startPoint.latitude +
            (endPoint.latitude - startPoint.latitude) * t,
        longitude = startPoint.longitude +
            (endPoint.longitude - startPoint.longitude) * t,
    )

    return RoadProjection(
        point = projected,
        distanceMeters = haversineDistanceMeters(point, projected),
    )
}

private fun haversineDistanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)

    val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)

    return 2.0 * earthRadius * kotlin.math.asin(
        kotlin.math.sqrt(h.coerceIn(0.0, 1.0))
    )
}

private fun syncVehicleMarkers(
    map: MapLibreMap,
    locations: List<Pair<Vehicle, VehicleLocation>>,
    icon: org.maplibre.android.annotations.Icon,
    markers: MutableMap<String, Marker>,
) {
    val activeIds = locations.mapTo(mutableSetOf()) { it.first.id }

    markers.keys.toList()
        .filter { it !in activeIds }
        .forEach { vehicleId ->
            markers.remove(vehicleId)?.remove()
        }

    locations.forEach { (vehicle, location) ->
        val position = LatLng(location.latitude, location.longitude)
        val marker = markers[vehicle.id]
        if (marker == null) {
            markers[vehicle.id] = map.addMarker(
                MarkerOptions()
                    .position(position)
                    .icon(icon)
                    .title(vehicle.identifier)
            )
        } else {
            marker.position = position
            marker.title = vehicle.identifier
        }
    }
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
        }
    }
    source.setGeoJson(FeatureCollection.fromFeatures(features.toTypedArray()))
}
