package com.safar.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.os.Bundle
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
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
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
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
private const val VEHICLES_SOURCE_ID = "safar-vehicles-source"
private const val VEHICLES_LAYER_ID = "safar-vehicles-layer"
private const val VEHICLE_LABEL_LAYER_ID = "safar-vehicle-label-layer"
private const val PASSENGERS_SOURCE_ID = "safar-passengers-source"
private const val PASSENGERS_LAYER_ID = "safar-passengers-layer"
private const val VEHICLE_ICON_IMAGE_ID = "safar-vehicle-icon"
private const val PASSENGER_ICON_IMAGE_ID = "safar-passenger-icon"

private const val DEFAULT_LATITUDE = 40.2829
private const val DEFAULT_LONGITUDE = 69.6228
private const val DEFAULT_ZOOM = 12.0
private const val USER_FOCUS_ZOOM = 15.5

/**
 * Shared MapLibre map used by both passenger and driver screens.
 * The user is rendered as a blue dot, with a translucent blue heading sector.
 * The three map controls (locate, zoom in, zoom out) are intentionally shared
 * so both roles have exactly the same map interaction.
 */
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
    var locationComponent by remember { mutableStateOf<LocationComponent?>(null) }
    var centeredOnUser by remember { mutableStateOf(false) }
    var lastCameraRouteId by remember { mutableStateOf<String?>(null) }

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
            locationComponent?.onDestroy()
            locationComponent = null
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
                        loadedMap.uiSettings.isCompassEnabled = false
                        loadedMap.uiSettings.isScrollGesturesEnabled = true
                        loadedMap.uiSettings.isZoomGesturesEnabled = true
                        loadedMap.uiSettings.isRotateGesturesEnabled = true
                        loadedMap.uiSettings.isTiltGesturesEnabled = true
                        loadedMap.setStyle(Style.Builder().fromUri(OPEN_FREE_MAP_STYLE)) { style ->
                            setupMapLayers(style)
                            locationComponent = loadedMap.locationComponent
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
                    passengerLocation?.let { point ->
                        val mapLocation = Location("safar").apply {
                            latitude = point.latitude
                            longitude = point.longitude
                            accuracy = 5f
                            headingDegrees?.let { bearing = it }
                        }
                        locationComponent?.takeIf { it.isLocationComponentActivated() }?.forceLocationUpdate(mapLocation)
                    }
                    map?.style?.let { style ->
                        updateRoute(style, route, routeOpacity)
                        updateRouteStation(style, route)
                        updateHeadingSector(style, passengerLocation, headingDegrees)
                        updateVehicles(style, vehicleLocations, route?.number)
            updatePassengerLocations(style, passengerLocations)
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
                passengerLocation?.let { point ->
                    map?.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(point.latitude, point.longitude),
                            USER_FOCUS_ZOOM,
                        )
                    )
                }
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

    LaunchedEffect(styleReady, locationPermissionGranted) {
        if (!styleReady) return@LaunchedEffect
        val component = locationComponent ?: return@LaunchedEffect
        if (!locationPermissionGranted) {
            if (component.isLocationComponentActivated()) {
                component.isLocationComponentEnabled = false
            }
            return@LaunchedEffect
        }
        val currentStyle = map?.style ?: return@LaunchedEffect
        if (!component.isLocationComponentActivated()) {
            val locationOptions = LocationComponentOptions.builder(context)
                .foregroundTintColor(SafarBlue.toArgb())
                .backgroundTintColor(Color.White.toArgb())
                .bearingTintColor(SafarBlue.toArgb())
                .pulseEnabled(true)
                .pulseColor(SafarBlue.toArgb())
                .pulseAlpha(0.18f)
                .build()
            component.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, currentStyle)
                    .locationComponentOptions(locationOptions)
                    .useDefaultLocationEngine(false)
                    .build()
            )
        }
        component.isLocationComponentEnabled = true
        component.setRenderMode(RenderMode.COMPASS)
        passengerLocation?.let { point ->
            if (component.isLocationComponentActivated()) {
                component.forceLocationUpdate(
                    Location("safar").apply {
                    latitude = point.latitude
                    longitude = point.longitude
                    accuracy = 5f
                    headingDegrees?.let { bearing = it }
                    }
                )
            }
        }
    }

    LaunchedEffect(styleReady, route, passengerLocation, headingDegrees, vehicleLocations, passengerLocations) {
        if (!styleReady) return@LaunchedEffect
        passengerLocation?.let { point ->
            val mapLocation = Location("safar").apply {
                latitude = point.latitude
                longitude = point.longitude
                accuracy = 5f
                headingDegrees?.let { bearing = it }
            }
            locationComponent?.takeIf { it.isLocationComponentActivated() }?.forceLocationUpdate(mapLocation)
        }
        map?.style?.let { style ->
            updateRoute(style, route, routeOpacity)
            updateRouteStation(style, route)
            updateHeadingSector(style, passengerLocation, headingDegrees)
            updateVehicles(style, vehicleLocations, route?.number)
            updatePassengerLocations(style, passengerLocations)
        }

        // Camera movement is deliberately NOT tied to every state/location update.
        // Otherwise a GPS update would fight the user's pan/zoom gesture.
        if (passengerLocation != null && !centeredOnUser) {
            map?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(passengerLocation.latitude, passengerLocation.longitude),
                    USER_FOCUS_ZOOM,
                )
            )
            centeredOnUser = true
        }
    }

    LaunchedEffect(styleReady, route?.id) {
        if (!styleReady || route == null || route.id == lastCameraRouteId) return@LaunchedEffect
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

    LaunchedEffect(locateRequestId) {
        if (!styleReady || locateRequestId <= 0L || passengerLocation == null) return@LaunchedEffect
        map?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(passengerLocation.latitude, passengerLocation.longitude),
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

private fun setupMapLayers(style: Style) {
    if (style.getSource(ROUTE_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }
    if (style.getSource(USER_HEADING_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(USER_HEADING_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }
    if (style.getSource(VEHICLES_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(VEHICLES_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }
    if (style.getSource(PASSENGERS_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(PASSENGERS_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray<Feature>())))
    }

    if (style.getImage(VEHICLE_ICON_IMAGE_ID) == null) {
        style.addImage(VEHICLE_ICON_IMAGE_ID, createVehicleMarkerBitmap())
    }
    if (style.getImage(PASSENGER_ICON_IMAGE_ID) == null) {
        style.addImage(PASSENGER_ICON_IMAGE_ID, createPassengerMarkerBitmap())
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
    if (style.getLayer(VEHICLES_LAYER_ID) == null) {
        style.addLayer(
            SymbolLayer(VEHICLES_LAYER_ID, VEHICLES_SOURCE_ID).withProperties(
                iconImage(VEHICLE_ICON_IMAGE_ID),
                iconSize(0.75f),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            )
        )
    }
    if (style.getLayer(PASSENGERS_LAYER_ID) == null) {
        style.addLayer(
            SymbolLayer(PASSENGERS_LAYER_ID, PASSENGERS_SOURCE_ID).withProperties(
                iconImage(PASSENGER_ICON_IMAGE_ID),
                iconSize(0.62f),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            )
        )
    }
    if (style.getLayer(VEHICLE_LABEL_LAYER_ID) == null) {
        style.addLayer(
            SymbolLayer(VEHICLE_LABEL_LAYER_ID, VEHICLES_SOURCE_ID).withProperties(
                textField("{route_number} • {identifier}"),
                textSize(10f),
                textColor(SafarBlue.toArgb()),
                textHaloColor(Color.White.toArgb()),
                textHaloWidth(1.5f),
            )
        )
    }
}

private fun updateRoute(style: Style, route: Route?, opacity: Float) {
    val source = style.getSource(ROUTE_SOURCE_ID) as? GeoJsonSource ?: return
    val path = route?.path.orEmpty()
    if (path.size < 2) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }
    val line = LineString.fromLngLats(path.map { Point.fromLngLat(it.longitude, it.latitude) })
    source.setGeoJson(FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(line))))
    style.getLayer(ROUTE_LAYER_ID)?.setProperties(lineOpacity(opacity.coerceIn(0.15f, 1f)))
    style.getLayer(ROUTE_CASING_LAYER_ID)?.setProperties(lineOpacity((opacity * 0.55f).coerceIn(0.12f, 0.8f)))
}

private fun updateRouteStation(style: Style, route: Route?) {
    val source = style.getSource(ROUTE_STATION_SOURCE_ID) as? GeoJsonSource ?: return
    val station = route?.path?.firstOrNull()
    if (station == null) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }
    val point = Point.fromLngLat(station.longitude, station.latitude)
    source.setGeoJson(FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(point))))
}

private fun updatePassengerLocations(style: Style, passengerLocations: List<PassengerLocation>) {
    val source = style.getSource(PASSENGERS_SOURCE_ID) as? GeoJsonSource ?: return
    val features = passengerLocations.map { passenger ->
        Feature.fromGeometry(Point.fromLngLat(passenger.longitude, passenger.latitude)).apply {
            addStringProperty("passenger_id", passenger.passengerId)
        }
    }.toTypedArray()
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}

private fun createVehicleMarkerBitmap(): Bitmap {
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f }

    paint.color = Color.White.toArgb()
    canvas.drawCircle(size / 2f, size / 2f, 42f, paint)
    paint.color = SafarBlue.toArgb()
    canvas.drawRoundRect(18f, 28f, 78f, 68f, 10f, 10f, paint)
    paint.color = Color.White.toArgb()
    canvas.drawRoundRect(25f, 34f, 47f, 51f, 4f, 4f, paint)
    canvas.drawRoundRect(50f, 34f, 71f, 51f, 4f, 4f, paint)
    paint.color = SafarBlue.toArgb()
    canvas.drawCircle(31f, 70f, 7f, paint)
    canvas.drawCircle(65f, 70f, 7f, paint)
    stroke.color = SafarBlue.toArgb()
    canvas.drawCircle(size / 2f, size / 2f, 42f, stroke)
    return bitmap
}

private fun createPassengerMarkerBitmap(): Bitmap {
    val size = 72
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND }

    paint.color = Color.White.toArgb()
    canvas.drawCircle(size / 2f, size / 2f, 31f, paint)
    paint.color = SafarNavy.toArgb()
    canvas.drawCircle(36f, 25f, 8f, paint)
    stroke.color = SafarNavy.toArgb()
    val body = Path().apply {
        moveTo(20f, 50f)
        cubicTo(24f, 39f, 30f, 35f, 36f, 35f)
        cubicTo(42f, 35f, 48f, 39f, 52f, 50f)
    }
    canvas.drawPath(body, stroke)
    stroke.color = SafarBlue.toArgb()
    canvas.drawCircle(size / 2f, size / 2f, 31f, stroke)
    return bitmap
}

private fun updateHeadingSector(style: Style, location: GeoPoint?, headingDegrees: Float?) {
    val headingSource = style.getSource(USER_HEADING_SOURCE_ID) as? GeoJsonSource ?: return
    if (location == null || headingDegrees == null) {
        headingSource.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }

    val sector = buildHeadingSector(location, headingDegrees)
    headingSource.setGeoJson(
        FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(sector)))
    )
}

private fun updateVehicles(style: Style, vehicleLocations: List<Pair<Vehicle, VehicleLocation>>, routeNumber: String?) {
    val source = style.getSource(VEHICLES_SOURCE_ID) as? GeoJsonSource ?: return
    val features = vehicleLocations.map { (vehicle, location) ->
        Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude)).apply {
            addStringProperty("route_number", routeNumber ?: "")
            addStringProperty("identifier", vehicle.identifier)
        }
    }.toTypedArray()
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}

/** Builds an ~90m long 50-degree sector in the phone's facing direction. */
private fun buildHeadingSector(center: GeoPoint, headingDegrees: Float): Polygon {
    val points = ArrayList<Point>(14)
    points += Point.fromLngLat(center.longitude, center.latitude)
    val halfAngle = 25.0
    val radiusMeters = 90.0
    for (i in 0..10) {
        val bearing = headingDegrees - halfAngle + (2.0 * halfAngle * i / 10.0)
        points += destinationPoint(center, radiusMeters, bearing)
    }
    points += Point.fromLngLat(center.longitude, center.latitude)
    return Polygon.fromLngLats(listOf(points))
}

private fun destinationPoint(origin: GeoPoint, distanceMeters: Double, bearingDegrees: Double): Point {
    val earthRadius = 6_371_000.0
    val angularDistance = distanceMeters / earthRadius
    val bearing = Math.toRadians(bearingDegrees)
    val lat1 = Math.toRadians(origin.latitude)
    val lon1 = Math.toRadians(origin.longitude)
    val lat2 = Math.asin(
        Math.sin(lat1) * Math.cos(angularDistance) +
            Math.cos(lat1) * Math.sin(angularDistance) * Math.cos(bearing)
    )
    val lon2 = lon1 + Math.atan2(
        Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(lat1),
        Math.cos(angularDistance) - Math.sin(lat1) * Math.sin(lat2)
    )
    return Point.fromLngLat(Math.toDegrees(lon2), Math.toDegrees(lat2))
}
