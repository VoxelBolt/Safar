> SUPERSEDED: This document describes the earlier Stage 06 implementation and is retained only as historical context. The authoritative implementation for the current archive is `STAGE_06_REALTIME_IMPLEMENTATION.md`, which uses `vehicle_current_state` + route-scoped Supabase Broadcast. Do not use the older Postgres Changes/history-table architecture described below as the current design.

# Safar — Stage 06: GPS & Background Location

## 1. What was discovered in the existing project

The supplied project already contained a partial Stage 06 attempt, but several pieces were not connected end-to-end.

The driver GPS service stopped unless an ACTIVE Work Session existed. `submit_vehicle_location` also required an ACTIVE Work Session in PostgreSQL. This directly coupled GPS to the Work Session timer.

The Driver UI requested foreground location permission, but did not actually start `DriverLocationService` after permission was granted or when an already-granted permission was detected.

The passenger map had real marker rendering support, but `PassengerMapViewModel` populated the vehicle list from `DemoTransportData.vehicleForRoute()` instead of the Supabase backend.

A Realtime boundary existed only as an interface. The Supabase client had Auth and Postgrest installed but not Realtime, and the production `supabase_realtime` publication contained no public tables.

The Android location provider already used Fused Location Provider at a 10-second interval with a 5-second minimum interval, but the GPS publishing path discarded the Android `Location.time` timestamp and sent the server's current time instead.

The existing `vehicle_current_locations` view already had a two-minute freshness rule, which was retained.

The database already contained the three MVP routes 3, 8 and 15. No location rows were present during the Stage 06 database inspection, so the implementation did not leave test GPS data behind.

## 2. Plan selected

Driver GPS is now treated as its own system:

`Fused Location Provider -> DriverLocationService -> DriverLocationRepository -> existing submit_vehicle_location RPC -> vehicle_locations`

Passenger updates use:

`vehicle_locations INSERT -> Supabase Realtime -> PassengerMapViewModel -> existing PassengerMapView marker layer`

The Work Session system remains responsible only for Start/Pause/Resume/Finish and its timer/statistics behavior. GPS is not started, paused, resumed or stopped from those actions.

## 3. Technologies / APIs

Android Fused Location Provider is used for real device/emulator location updates. The location request remains `PRIORITY_HIGH_ACCURACY`, 10 seconds target interval, and 5 seconds minimum interval.

A location Foreground Service is used for background/screen-off tracking. The manifest declares the `location` foreground-service type and `FOREGROUND_SERVICE_LOCATION`. The service calls the typed `startForeground()` form on Android 10+.

Supabase Kotlin `realtime-kt` is installed alongside Auth and Postgrest. The passenger subscribes to `vehicle_locations` INSERT events using `postgresChangeFlow`.

Supabase Postgres Changes is used for this MVP because it matches the existing table-oriented architecture and keeps the integration small.

## 4. Background tracking

The existing single driver location-permission flow is preserved. No second permission UI was introduced.

The Driver screen starts the location Foreground Service while the activity is visible and the foreground location permission is granted. This avoids attempting to create a location Foreground Service from an already-backgrounded activity.

No `ACCESS_BACKGROUND_LOCATION` permission was added to this Stage 06 implementation. The selected architecture keeps the service running as a location Foreground Service after the app is minimized or the screen is locked. Android's current rules require the service to be started from an eligible visible state when relying on while-in-use location permission.

## 5. Location transmission

Each real Android `Location` sample is converted to a `LocationSample` containing latitude, longitude and `Location.time`.

When an internet connection is available, the service sends the sample through the dedicated `DriverLocationRepository`. A network failure does not terminate GPS collection; the current sample is simply not considered successfully transmitted and later samples retry automatically.

On network restoration, the service immediately asks for a fresh current location and attempts transmission again.

## 6. Vehicle and route linkage

The existing `submit_vehicle_location` RPC finds the authenticated driver's `drivers` row and uses its `vehicle_id`. It now also requires that the driver has a route assigned, then marks that vehicle active and inserts the location into the existing `vehicle_locations` table.

The existing `assign_driver_route` RPC already updates both `drivers.route_id` and `vehicles.route_id`, so route selection remains the existing source of truth.

The passenger query continues to filter by the selected route first, then by current active/fresh vehicles.

## 7. Freshness

The existing `vehicle_current_locations` view uses a two-minute freshness window. The passenger ViewModel uses the same two-minute window as a client-side guard.

A small 30-second stale sweep refreshes only the selected route's active-vehicle query. Realtime remains responsible for normal live movement; the periodic query is only a freshness/discovery safety net and is not a page reload loop.

## 8. Realtime

`vehicle_locations` was added to the `supabase_realtime` publication.

The passenger subscribes to `INSERT` events for that table. Known vehicle markers update directly when a new coordinate arrives. When a previously unseen vehicle starts sending, the selected-route active-vehicle query is refreshed to obtain the vehicle identifier, route and latest coordinate.

## 9. Work Session independence

The Stage 06 migration removes all `vehicles.active` mutations from `start_work_session`, `pause_work_session`, `resume_work_session`, and `finish_work_session`.

GPS owns the live activity signal by setting `vehicles.active = true` when a location is successfully submitted. The two-minute freshness rule is what makes the vehicle stop appearing current after location updates stop.

Therefore:

- Pause stops only the Work Session timer/state.
- Resume affects only the Work Session timer/state.
- Finish affects only the Work Session timer/state.
- GPS publishing continues independently as long as the service still has location permission and can obtain/send location data.

## 10. Files changed

- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/safar/app/data/location/LocationProvider.kt`
- `app/src/main/java/com/safar/app/data/location/AndroidLocationProvider.kt`
- `app/src/main/java/com/safar/app/data/location/DriverLocationService.kt`
- `app/src/main/java/com/safar/app/data/realtime/RealtimeTransport.kt`
- `app/src/main/java/com/safar/app/data/remote/SupabaseClientProvider.kt`
- `app/src/main/java/com/safar/app/di/AppContainer.kt`
- `app/src/main/java/com/safar/app/data/repository/WorkSessionRepositoryImpl.kt`
- `app/src/main/java/com/safar/app/data/repository/DriverLocationRepositoryImpl.kt`
- `app/src/main/java/com/safar/app/domain/repository/Repositories.kt`
- `app/src/main/java/com/safar/app/ui/screens/MainInterfaces.kt`
- `app/src/main/java/com/safar/app/ui/screens/PassengerMapViewModel.kt`
- `backend/migrations/005_gps_tracking_independent_from_work_session.sql`
- `STAGE_06_IMPLEMENTATION.md`

## 11. Dependencies added

Added:

`io.github.jan-tennert.supabase:realtime-kt`

No new location SDK was added because the project already had `play-services-location:21.3.0`.

## 12. Backend migration

Applied once to Supabase project `MVPProject`:

`20260919112033_gps_tracking_independent_from_work_session`

The migration was verified in Supabase after application. The Realtime publication contains `public.vehicle_locations`, and the updated RPC definitions no longer require an ACTIVE Work Session for GPS submission.

## 13. Problems fixed

The main Stage 06 defects fixed were:

1. GPS stopped when there was no ACTIVE Work Session.
2. GPS submission failed when the Work Session was PAUSED/FINISHED.
3. Driver UI did not start the GPS Foreground Service.
4. Passenger vehicle markers used fake/demo vehicle data.
5. Realtime was not installed or connected.
6. `vehicle_locations` was not in the Realtime publication.
7. Location timestamps were not preserved from Android.
8. Network loss did not have an explicit transmission state/retry path.
9. GPS submission lived under `WorkSessionRepository`, which blurred the separation between the two systems.
10. Work Session RPCs were toggling `vehicles.active`, creating the exact GPS/session coupling that Stage 06 prohibits.

## 14. Verification actually performed

Database verification performed against the connected Supabase project:

- Project is ACTIVE_HEALTHY.
- Routes 3, 8 and 15 exist.
- `vehicle_locations` is still empty after implementation, so no persistent test GPS rows were created.
- The Realtime publication contains `vehicle_locations`.
- The updated `submit_vehicle_location` no longer checks for an ACTIVE Work Session.
- Work Session RPCs no longer toggle `vehicles.active`.
- Existing two-minute freshness logic remains in `vehicle_current_locations`.
- Security advisor inspection completed; existing project warnings remain unrelated to this migration (mainly pre-existing SECURITY DEFINER and anonymous-policy advisories).

Local/static verification performed:

- Domain Kotlin sources compiled successfully with the available standalone Kotlin compiler.
- XML resources and `AndroidManifest.xml` parsed successfully.
- All referenced default `R.string` resources were checked and are present.
- Modified Kotlin source files passed delimiter/balance checks.
- Routes 3/8/15 remain in the original seed migration.
- `local.properties` SHA-256 remained unchanged.

## 15. Build / emulator / Logcat status

A full Android Gradle build was attempted, but the supplied project has no `gradlew` script and the execution environment has no Gradle installation or Android SDK. The environment also has no network access to download them.

Therefore the following were NOT honestly claimable from this environment:

- successful `:app:assembleDebug`
- installation on an Android emulator/device
- real GPS movement test
- background tracking test with the app minimized
- screen-lock tracking test
- two-device Driver -> Supabase -> Passenger end-to-end test
- actual Supabase Realtime event delivery into the Android app
- Logcat inspection during those runtime tests

The implementation was therefore not marked as fully runtime-verified.

## 16. BUILD -> RUN -> TEST -> FIX cycles

Completed runtime BUILD -> RUN -> TEST -> FIX cycles: **0**.

One Android build invocation was attempted and blocked before Gradle execution because `./gradlew` is missing. Source-level/static checks were performed, and the code was iteratively reviewed and corrected, but this does not substitute for the required emulator/device verification.

## 17. Final status

The Stage 06 code and Supabase schema changes for REAL GPS -> SERVER -> PASSENGER are implemented, but the complete Stage 06 acceptance criteria are **not runtime-verified in this environment**.

## 18. Intentionally left for Stage 07

- ETA
- nearest-vehicle algorithm
- complex route intelligence
- geofencing / route-zone enforcement
- automatic route-adherence validation
- payments
- ratings
- chat
- notifications beyond the required foreground-service notification
- complex offline queueing
- complex analytics
