# Safar — Stage 06 | Real-Time Driver Location

## Implementation

The live tracking path is:

Driver FusedLocationProviderClient
→ DriverLocationService (location foreground service)
→ authenticated Supabase RPC `submit_vehicle_location`
→ one-row-per-vehicle `vehicle_current_state`
→ database trigger `broadcast_vehicle_location`
→ private route channel `safar:vehicle-location:route:<route_id>`
→ PassengerMapViewModel
→ existing MapLibre vehicle marker.

Passenger initial state is loaded from `vehicle_current_locations`, a security-invoker view over `vehicle_current_state`. The view marks a vehicle active only when the current row is no older than 1 minute.

No live code writes to `vehicle_locations`. That table remains as a legacy compatibility table and currently contains zero rows. It is not part of the Stage 06 live path.

## GPS

`AndroidLocationProvider` uses Android Fused Location Provider with high accuracy, a 10-second requested interval and a 5-second minimum interval. Android's `Location.time` is sent as `recorded_at`.

`DriverLocationService` is independent of Work Session. Start/Pause/Resume/Finish do not control the location service. The service starts from the visible Driver UI only after location permission exists and a driver route is assigned, then continues as a foreground location service when the app goes to the background.

No `DemoTransportData` source remains in the Android application. Route 3/8/15 geometry is stored in Supabase `routes.path`.

## Realtime

Supabase Database Broadcast is used instead of Postgres Changes on a high-frequency history table. The database trigger sends only:

- vehicle_id
- route_id
- latitude
- longitude
- recorded_at

No driver personal information is broadcast.

Passenger subscribes only to the currently selected route's private channel. Route mismatches are also rejected in the ViewModel.

## Stale/offline

The freshness threshold is 1 minute. The Passenger ViewModel sweeps every 5 seconds and removes stale markers. The database view uses the same 25-second threshold for initial loading.

When internet is unavailable on Driver, no fake coordinates are generated and no old coordinate is resent as a new coordinate. The foreground service keeps GPS collection alive and resumes publication when a validated network is available.

## Work Session timer

The Driver timer now always renders `HH:MM:SS`, for example `00:03:27`, `01:15:42`, and `05:42:18`. The existing wall-clock/pause accounting remains unchanged.

## Verification performed in this environment

- Supabase migrations applied successfully.
- `vehicle_current_state` exists as a base table.
- `vehicle_current_locations` remains a view.
- Current-state row count is 0.
- Legacy `vehicle_locations` row count is 0.
- No route-specific Broadcast messages are present in the database from testing.
- Realtime authorization policy for the Safar route topics exists.
- Routes 3, 8 and 15 contain database-backed geometry.
- Supabase functions were inspected after migration.
- No `DemoTransportData` references remain in Android source.
- No Android source writes to `vehicle_locations`.
- `WorkSessionTimer` was compiled and tested with active, paused and completed-session cases.

## Verification not possible here

A full Android Gradle BUILD could not be executed because this execution environment has no Gradle executable/wrapper JAR and no Android SDK/ADB/emulator.

A physical two-emulator Driver → Supabase Broadcast → Passenger test was therefore NOT performed here.

The required runtime evidence still missing is:
1. Driver emulator receives real GPS.
2. Driver route 3 publishes.
3. Passenger route 3 receives the Broadcast.
4. Passenger marker moves after Driver location changes.
5. Driver internet is disabled.
6. Marker disappears after the 25-second freshness threshold.
7. Internet is restored and the marker reappears.

Do not treat this document or the previous Stage 06 report as proof of that end-to-end runtime behavior.
## 2026-09-20 hotfixes

Two concrete defects found during real-device/emulator review were fixed:

1. `submit_vehicle_location` is defined in PostgreSQL with parameters `p_latitude`, `p_longitude`, and `p_recorded_at`. The Android RPC call was incorrectly sending `latitude`, `longitude`, and `recorded_at`, so the driver GPS publish request could fail before `vehicle_current_state` was updated. The Android call now sends the exact RPC parameter names.
2. The driver map reused `PassengerMapView` but passed `passengerLocation = null`, so the MapLibre LocationComponent had no coordinates to render and the locate button had no callback. The driver screen now obtains the driver's real Android location, feeds it to the shared map, and the locate button refreshes/focuses that position.

The service network check was also relaxed to require an internet-capable active network rather than `NET_CAPABILITY_VALIDATED`; HTTP/RPC failures are still caught and retried by the service.

The Supabase RPC was exercised inside a transaction using an existing verified driver identity; the current-state write path completed without an exception and the transaction was rolled back, leaving `vehicle_current_state` at zero rows.

Full Android Gradle build, APK install, and two-emulator WebSocket E2E remain unavailable in this environment because Android SDK/ADB/emulator and the Gradle distribution are not installed.

## Bidirectional passenger realtime extension

The route-scoped private Broadcast channel now carries both directions of transient location state:

- `vehicle_location` / `vehicle_location_removed`: database-triggered driver vehicle events.
- `passenger_location` / `passenger_location_removed`: client Broadcast events from a passenger while a route is selected.

Passenger coordinates are not written to a location-history table. The passenger publishes the Android GPS sample with `passenger_id`, `route_id`, `latitude`, `longitude`, and `recorded_at`. The driver subscribes to the same route channel and keeps an in-memory marker per passenger.

The stale threshold for both vehicle markers and passenger markers is now 1 minute. The passenger and driver ViewModels sweep their in-memory marker collections every 5 seconds. The `vehicle_current_locations` view was updated by migration 009 so the initial vehicle snapshot uses the same 1-minute threshold.

A Realtime `INSERT` RLS policy was added for authenticated clients on route-scoped Broadcast topics so passenger client broadcasts can be sent. The existing authenticated `SELECT` policy remains route-scoped.

Passenger route changes stop the old publisher and switch the realtime subscription to the new route. Driver route changes similarly replace the passenger subscription with the new route. GPS collection remains independent of Work Session state.

Full Android Gradle build and two-device end-to-end execution remain environment-dependent; this container does not provide the Android SDK/ADB/Gradle wrapper JAR required for those checks.


## Stage 07 — Nearest vehicle and ETA

The passenger map now has an explicit nearest-vehicle control. Tapping it reads all current vehicle rows from the authenticated `vehicle_current_locations` view, removes stale coordinates using the existing one-minute freshness guard, calculates the closest vehicle by Haversine GPS distance, centers the map on that vehicle, and shows an approximate ETA.

The displayed ETA is intentionally an approximation: it uses straight-line GPS distance and a configurable urban average vehicle speed of 25 km/h. It is not road-network routing and does not account for traffic, turns, stops, or the remaining route geometry.

Vehicle GPS tracking remains independent from Work Session state. Pause and Finish no longer stop the Driver Location Foreground Service from the Driver UI or ViewModel.
