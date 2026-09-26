package com.safar.app.ui.screens

import android.content.Context
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
import androidx.compose.ui.graphics.Color
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
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
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
private const val USER_LOCATION_SOURCE_ID = "safar-user-location-source"
private const val USER_LOCATION_CIRCLE_LAYER_ID = "safar-user-location-circle-layer"
private const val USER_LOCATION_PULSE_LAYER_ID = "safar-user-location-pulse-layer"
private const val VEHICLES_SOURCE_ID = "safar-vehicles-source"
private const val VEHICLES_LAYER_ID = "safar-vehicles-layer"
private const val VEHICLE_LABEL_LAYER_ID = "safar-vehicle-label-layer"

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
                        loadedMap.uiSettings.isCompassEnabled = false
                        loadedMap.uiSettings.isScrollGesturesEnabled = true
                        loadedMap.uiSettings.isZoomGesturesEnabled = true
                        loadedMap.uiSettings.isRotateGesturesEnabled = true
                        loadedMap.uiSettings.isTiltGesturesEnabled = true
                        loadedMap.setStyle(Style.Builder().fromUri(OPEN_FREE_MAP_STYLE)) { style ->
                            setupMapLayers(style)
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
                        updateVehicles(style, vehicleLocations, route?.number)
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

    LaunchedEffect(styleReady, route, passengerLocation, headingDegrees, vehicleLocations) {
        if (!styleReady) return@LaunchedEffect
        map?.style?.let { style ->
            updateUserLocation(style, passengerLocation)
            updateRoute(style, route, routeOpacity)
            updateRouteStation(style, route)
            updateHeadingSector(style, passengerLocation, headingDegrees)
            updateVehicles(style, vehicleLocations, route?.number)
        }

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

    LaunchedEffect(locateRequestId, passengerLocation) {
        if (!styleReady || locateRequestId <= 0L || passengerLocation == null) return@LaunchedEffect
        locateTarget = passengerLocation
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
    if (style.getLayer(VEHICLES_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(VEHICLES_LAYER_ID, VEHICLES_SOURCE_ID).withProperties(
                circleColor(SafarBlue.toArgb()),
                circleRadius(8f),
                circleStrokeColor(Color.White.toArgb()),
                circleStrokeWidth(2f),
            )
        )
    }
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
    // Keep the user layer explicitly above every other custom layer.
    style.removeLayer(USER_LOCATION_PULSE_LAYER_ID)
    style.removeLayer(USER_LOCATION_CIRCLE_LAYER_ID)
    style.addLayer(
        CircleLayer(USER_LOCATION_PULSE_LAYER_ID, USER_LOCATION_SOURCE_ID).withProperties(
            circleColor(SafarBlue.toArgb()),
            circleRadius(18f),
            circleOpacity(0.20f),
        )
    )
    style.addLayer(
        CircleLayer(USER_LOCATION_CIRCLE_LAYER_ID, USER_LOCATION_SOURCE_ID).withProperties(
            circleColor(SafarBlue.toArgb()),
            circleRadius(8f),
            circleStrokeColor(Color.White.toArgb()),
            circleStrokeWidth(2.5f),
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

private fun updateVehicles(
    style: Style,
    locations: List<Pair<Vehicle, VehicleLocation>>,
    routeNumber: String?,
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
