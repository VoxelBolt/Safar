package com.safar.app.data.remote

/**
 * Stage 03 uses Supabase directly through supabase-kt.
 *
 * The old Retrofit client was a foundation placeholder and is intentionally
 * removed rather than kept as a parallel backend path.
 */
object ApiClient {
    fun isConfigured(): Boolean = SupabaseClientProvider.isConfigured
}
