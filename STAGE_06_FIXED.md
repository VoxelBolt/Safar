> SUPERSEDED: This document describes the earlier Stage 06 implementation and is retained only as historical context. The authoritative implementation for the current archive is `STAGE_06_REALTIME_IMPLEMENTATION.md`, which uses `vehicle_current_state` + route-scoped Supabase Broadcast. Do not use the older Postgres Changes/history-table architecture described below as the current design.

# Safar Stage 06 — fixed source

This archive contains a clean Stage 06 source tree with the location-provider compile issue fixed.

Important: the IDE screenshots showed cascading errors caused by accidental edits inside `AndroidLocationProvider.kt` and related location code. Do not merge the broken lines from the current IDE copy back into this archive.

Main fix:
- `AndroidLocationProvider.locations()` now uses `locationSamples().map { it.point }`.
- `kotlinx.coroutines.flow.map` is imported.
- `FlowPreview` is explicitly opted into for the `sample(200L)` heading flow.

The clean `DriverLocationService.kt` and `PassengerMapViewModel.kt` from the Stage 06 archive are also included, so the cascading `currentLocationSample`, `timestampEpochMs`, `point`, `lastKnownLocation`, and `headingDegrees` errors are not carried over.

Open this project in Android Studio and run Gradle Sync, then Build > Make Project. Runtime GPS testing still needs to be performed on the Pixel 7 emulator/device.
