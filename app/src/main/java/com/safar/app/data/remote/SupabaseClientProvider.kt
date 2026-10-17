package com.safar.app.data.remote

import com.safar.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SupabaseClientProvider {
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() &&
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()

    private val sessionInitializationMutex = Mutex()

    val client: SupabaseClient by lazy {
        check(isConfigured) {
            "Supabase is not configured. Provide SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY."
        }

        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    }

    /**
     * Shared by REST and Realtime paths. Without one lock, concurrent startup
     * requests could both observe an empty auth state and create different
     * anonymous sessions, leaving the profile query bound to the wrong user.
     */
    suspend fun ensureAnonymousSession(client: SupabaseClient = this.client) {
        sessionInitializationMutex.withLock {
            client.auth.awaitInitialization()

            if (client.auth.currentSessionOrNull() == null) {
                client.auth.signInAnonymously()
            }

            check(client.auth.currentSessionOrNull() != null) {
                "Supabase authentication did not produce an active session."
            }
            check(client.auth.currentUserOrNull() != null) {
                "Supabase authentication did not produce an authenticated user."
            }
        }
    }
}
