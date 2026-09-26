# Safar — Android Foundation

This project is the Safar Android foundation plus the current Stage 2 onboarding flow.

Architecture:

`UI -> ViewModel -> Repository -> Data/Remote`

The project uses one `app` module. Domain models and repository contracts are separated from data implementations. API, location, and realtime are represented only by boundaries; no backend, GPS, map, WebSocket, ETA, authentication, or product UI is implemented here.

## Stack

- Android
- Kotlin via AGP built-in Kotlin support
- Jetpack Compose + Material 3
- Navigation Compose
- ViewModel/Lifecycle
- Retrofit + OkHttp API boundary
- Gradle Kotlin DSL

## Current flow

`APP LAUNCH -> LANGUAGE -> ROLE -> PASSENGER/DRIVER REGISTRATION -> MAIN PLACEHOLDER`

Stage 2 intentionally stops at placeholders; later product functionality is not implemented yet.

## Build

AGP 9.4.0 is paired with Gradle 9.6.0. AGP 9 has built-in Kotlin support, so `org.jetbrains.kotlin.android` is intentionally not applied. The Compose Compiler Gradle plugin remains applied because Compose requires it.

A Gradle wrapper JAR was not included in the supplied archive, so this environment cannot run the wrapper itself. Android Studio can regenerate the wrapper if required.
