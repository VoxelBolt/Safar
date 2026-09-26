# Safar — Stage 03: Backend & API

Stage 03 adds the Supabase/PostgreSQL foundation without redesigning the existing Passenger/Driver UI.

## Backend

`backend/migrations/001_initial.sql` creates:

- `users` — application profile linked to `auth.users`.
- `drivers` — driver-to-vehicle assignment and optional future route assignment.
- `vehicles` — vehicle identifier, optional route assignment, and active state.
- `routes` — route number and JSON path.
- `work_sessions` — active/paused/finished driver work sessions.
- `vehicle_locations` — timestamped vehicle coordinates.
- `favorite_vehicles` — unique Passenger → Vehicle favorites.
- `vehicle_current_locations` — latest location projection without duplicating coordinates in `vehicles`.

Driver registration does not require a route. The route remains nullable and is assigned later.

## Security

All application tables have RLS enabled. Client reads/writes are constrained to the authenticated user or explicitly public-for-authenticated data. Work-session state changes, vehicle location submission, registration, and favorite mutations that need server-side checks use Postgres RPC functions.

The Android app uses only the Supabase publishable key. The service-role key and database password are never part of the Android configuration.

## Android configuration

Provide these as Gradle properties or environment variables; do not put them in source control:

- `SUPABASE_URL`
- `SUPABASE_PUBLISHABLE_KEY`

Example environment setup on the development machine:

```text
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
```

`local.properties` is intentionally not used for these values and was not modified.

Supabase anonymous sign-ins must be enabled in the project because the MVP has no password, SMS, OAuth, or social login. Anonymous sessions provide a stable `auth.uid()` for RLS during the current app installation.

## Android data flow

`UI → ViewModel → Repository → Supabase`

Existing repository contracts were preserved and extended only where Stage 03 needs backend operations. The old placeholder Retrofit path was removed so there is one backend path rather than two parallel API implementations.

## Stage boundary

This stage does not implement real maps, GPS collection, background location, foreground service, realtime transport, nearest-vehicle selection, ETA, or the later statistics/analytics behavior.
