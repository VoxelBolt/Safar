# Safar — Stage 05: Driver & Work Session

Implemented the Stage 05 boundary without adding GPS/realtime behavior to the driver session flow.

## What changed

- Driver registration now asks only for name, phone, and vehicle identifier. Route assignment moved to Driver Home.
- Driver Home loads routes from the existing Supabase backend and lets the driver assign a route after registration.
- Route changes are disabled while an ACTIVE or PAUSED session exists.
- Existing `start_work_session`, `pause_work_session`, `resume_work_session`, `finish_work_session`, and `get_current_work_session` backend operations remain the single work-session API.
- Current session restoration now uses the existing `get_current_work_session` RPC.
- The timer uses server session timestamps plus `total_paused_seconds`/`paused_at`, so it is based on elapsed wall-clock time rather than UI tick counting.
- A paused session older than six hours is finished before a new session is started; the app does not resume an expired pause.
- Driver work actions have loading/disabled behavior and backend errors are surfaced.
- Existing GPS/location service files were left intact for the later GPS stage, but Stage 05 no longer requires location permission to start a work session.

## Verification performed in this environment

- Kotlin domain model + timer logic compiled with the installed Kotlin compiler.
- Executed timer checks for ACTIVE, PAUSED, RESUMED, and COMPLETED states.
- All XML resource files parsed successfully.
- All `R.string` references found in Kotlin have matching default string resources.
- No remaining references to the old driver-registration route picker exist.
- `local.properties` was not modified.

## Not verified

A full Android Gradle build, emulator launch, Logcat inspection, Supabase runtime scenario, and real UI interaction could not be executed in this container because the uploaded project does not contain `gradlew` and this environment has no Gradle/Android SDK installation or network access to obtain one.

## Follow-up corrections requested by user — 2026-09-18

- Driver route selection was moved into registration. The registration form loads the existing backend routes and presents the three preferred MVP routes: 3, 8 and 15 when present.
- The driver must select a route before registration can complete.
- Registration still uses the existing `register_driver` RPC and then the existing `assign_driver_route` RPC; no database schema migration was introduced for this change.
- Driver registration labels were changed to `Номер транспорта` and `Номер маршрута` (with English/Tajik translations).
- The selected route id is persisted locally and the backend remains the source of truth for the driver's current route.
- Driver Home no longer contains the route-loading/assignment card. It now contains a tracking-permission card above the map.
- Foreground location permission is requested through the Android runtime permission dialog. On Android 11+ background location is handled through the app's system settings because Android does not grant foreground and background location together in a single runtime request.
- `ACCESS_BACKGROUND_LOCATION` was added to the manifest.
- Passenger map camera logic was changed so GPS/state updates no longer continuously re-center the map. User pan and zoom are preserved; recentering occurs only for explicit location action or a route change/initial positioning.
- Demo route geometry for 3, 8 and 15 was replaced with three distinct closed-loop polylines instead of the previous straight one-way lines. These are demo geometries, not verified official public-transit itineraries.
- A route casing layer was added so the highlighted route is visually clearer against the map.
- Added unit coverage for the demo route loops and route-geometry distinctness.

### Verification performed in this environment

- XML resources parse successfully.
- Every `R.string` referenced by Kotlin source exists in Russian, English and Tajik resources.
- Domain Kotlin sources (`Models.kt`, `WorkSessionTimer.kt`) compile with the available standalone Kotlin compiler.
- Demo route geometry checks pass: routes 3, 8 and 15 are closed loops and have distinct point sequences.
- The project cannot be fully Android-built in this environment because the Gradle wrapper executable/distribution and Android SDK are not available here. Therefore a full Gradle build, emulator run and Logcat test remain **not verified in this environment**.

## Follow-up fixes — 2026-09-18

- Fixed Driver Home startup error caused by decoding `get_current_work_session()` as a JSON array even though the PostgreSQL function returns one composite row. Current-session lookup now uses the RLS-protected `work_sessions` table and checks ACTIVE/PAUSED state without failing when there is no session.
- Driver registration route selection remains part of registration and is limited to the three MVP routes returned by backend: 3, 8, 15.
- Driver Home/Profile resolve the selected backend route and use the same demo geometry as the passenger map.
- Added three distinct closed-loop demo route geometries and a station/start marker for each route. Passenger and driver maps share the same geometry.
- Driver route rendering uses a lower opacity than passenger route rendering.
- Driver tracking permission UI now requests only foreground location permission from the Android system. The app no longer opens Android Settings from this card and no longer asks for background location permission from this UI. The existing location foreground service can be started while the app is visible when needed.
- Removed the previous route-camera behavior that could fight user interaction; map camera movement is not tied to every GPS update.

Validation performed in the build environment:
- XML resources and AndroidManifest parse successfully.
- Modified Kotlin files have balanced braces/parentheses.
- Route 3, 8 and 15 demo paths are each closed loops with different geometry.
- `local.properties` SHA-256 is unchanged from the source project.
- Final ZIP integrity check passes.
- Full Gradle/Android Studio build and emulator runtime test were not available in this environment and therefore are not claimed as passed.
