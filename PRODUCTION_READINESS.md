# Safar — Production Readiness Notes

## Current target

The MVP is being hardened for roughly 10–20 concurrent users and 100–200 monthly users. The live GPS path remains current-state only: Driver GPS -> submit_vehicle_location -> vehicle_current_state -> database Broadcast -> route-scoped passenger subscribers.

## Nearest vehicle

Nearest-vehicle selection is scoped to the passenger's selected route. The button takes a fresh passenger GPS sample, refreshes that route's current vehicle snapshot once, chooses the closest fresh coordinate with the Haversine formula, and estimates ETA using a conservative 25 km/h average vehicle speed. Subsequent vehicle and passenger updates recompute distance/ETA in memory instead of starting another database polling loop.

Invalid coordinates are ignored. Vehicles more than 50 km from the passenger are ignored for ETA so a corrupted emulator/GPS coordinate cannot produce absurd values such as thousands of minutes.

## Runtime load protections

- Realtime Broadcast is the primary movement path.
- Passenger database polling is only a fallback and runs every 10 seconds.
- The nearest button does not create a permanent database polling loop.
- Driver GPS safety publishing is reduced to a 10-second fallback interval.
- Driver GPS no longer sends a second client-side vehicle Broadcast after the database write; the database trigger is the single authoritative vehicle event source.
- Current-state tables keep one row per vehicle/passenger instead of growing with every GPS sample.
- A scheduled cleanup job runs every minute to remove expired transient location state.
- Work Session state never stops DriverLocationService.

## Database hardening

`work_sessions.vehicle_id` now has a covering index.
`public.set_updated_at()` now has a pinned `search_path`.

Existing SECURITY DEFINER RPCs intentionally remain callable by authenticated clients because they are the controlled mutation API. They already pin their search_path. The Supabase security advisor still reports these functions because they are SECURITY DEFINER; that warning is an item to review, not proof of an exploit.

## Important runtime verification

Static/code changes cannot prove Android GPS and two-device Realtime behavior. Before release, verify with at least one Driver device/emulator and two Passenger sessions:

1. Driver location continues after Work Session Finish.
2. Passenger sees the driver move without reopening the map.
3. Nearest button focuses the closest fresh vehicle.
4. ETA is a small realistic value for local coordinates and disappears for invalid/stale data.
5. A stale vehicle disappears after the freshness threshold.
6. Temporary internet loss does not crash either app; GPS resumes publishing after reconnection.
7. Switching routes does not leave the old route's vehicle marker behind.
8. Repeated route changes and app background/foreground transitions do not create duplicate Realtime channels.
9. 10–20 simultaneous clients can reconnect without crashing or permanently losing their route subscription.
