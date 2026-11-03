package com.safar.app.data.location

import com.safar.app.domain.model.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class PendingGpsFix(
    val sample: LocationSample,
    val headingDegrees: Float?,
)

/** Short-lived, app-private queue for accepted GPS fixes waiting for connectivity. */
internal class PendingDriverLocationQueue(
    private val file: File,
    private val onCorrupt: (Throwable) -> Unit = {},
    private val maxAgeMs: Long = MAX_QUEUE_AGE_MS,
    private val maxFixes: Int = MAX_QUEUED_FIXES,
) {
    @Serializable
    private data class StoredFix(
        val driverId: String,
        val routeId: String,
        val latitude: Double,
        val longitude: Double,
        val timestampEpochMs: Long,
        val headingDegrees: Float?,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StoredFix.serializer())

    @Synchronized
    fun enqueue(
        driverId: String,
        routeId: String,
        sample: LocationSample,
        headingDegrees: Float?,
        nowEpochMs: Long,
    ): Boolean {
        if (driverId.isBlank() || routeId.isBlank() || !sample.isValidFix(nowEpochMs)) return false
        val existing = read()
        val matchingIdentity = existing.filter { it.driverId == driverId && it.routeId == routeId }
        val retained = matchingIdentity.filter { it.isFresh(nowEpochMs) }
        val newestTimestamp = retained.maxOfOrNull { it.timestampEpochMs } ?: Long.MIN_VALUE
        if (sample.timestampEpochMs <= newestTimestamp) {
            if (retained.size != existing.size) write(retained)
            return false
        }

        val fix = StoredFix(
            driverId = driverId,
            routeId = routeId,
            latitude = sample.point.latitude,
            longitude = sample.point.longitude,
            timestampEpochMs = sample.timestampEpochMs,
            headingDegrees = headingDegrees?.takeIf { it.isFinite() && it >= 0f && it < 360f },
        )
        write((retained + fix).takeLast(maxFixes))
        return true
    }

    @Synchronized
    fun pendingFor(driverId: String, routeId: String, nowEpochMs: Long): List<PendingGpsFix> {
        val existing = read()
        val retained = existing
            .filter { it.driverId == driverId && it.routeId == routeId && it.isFresh(nowEpochMs) }
            .sortedBy { it.timestampEpochMs }
        if (retained.size != existing.size) write(retained)
        return retained.map {
            PendingGpsFix(
                sample = LocationSample(
                    point = GeoPoint(it.latitude, it.longitude),
                    timestampEpochMs = it.timestampEpochMs,
                ),
                headingDegrees = it.headingDegrees,
            )
        }
    }

    @Synchronized
    fun acknowledgeThrough(driverId: String, routeId: String, timestampEpochMs: Long) {
        val remaining = read().filterNot {
            it.driverId == driverId && it.routeId == routeId && it.timestampEpochMs <= timestampEpochMs
        }
        write(remaining)
    }

    @Synchronized
    fun clear() {
        if (file.exists() && !file.delete()) throw IOException("Could not clear pending GPS queue")
    }

    private fun StoredFix.isFresh(nowEpochMs: Long): Boolean =
        timestampEpochMs > 0L &&
            timestampEpochMs >= nowEpochMs - maxAgeMs &&
            timestampEpochMs <= nowEpochMs + MAX_FUTURE_FIX_MS &&
            latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0

    private fun LocationSample.isValidFix(nowEpochMs: Long): Boolean =
        timestampEpochMs > 0L &&
            timestampEpochMs >= nowEpochMs - maxAgeMs &&
            timestampEpochMs <= nowEpochMs + MAX_FUTURE_FIX_MS &&
            point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
            point.longitude.isFinite() && point.longitude in -180.0..180.0

    private fun read(): List<StoredFix> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(serializer, file.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            if (!file.delete() && file.exists()) {
                throw IOException("Corrupt pending GPS queue could not be removed", error)
            }
            onCorrupt(error)
            emptyList()
        }
    }

    private fun write(fixes: List<StoredFix>) {
        val parent = file.parentFile ?: throw IOException("Pending GPS queue has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create pending GPS queue directory")
        if (fixes.isEmpty()) {
            if (file.exists() && !file.delete()) throw IOException("Could not remove empty pending GPS queue")
            return
        }

        val temp = File.createTempFile("${file.nameWithoutExtension}-", ".tmp", parent)
        try {
            FileOutputStream(temp).use { output ->
                output.write(json.encodeToString(serializer, fixes).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists() && !temp.delete()) {
                throw IOException("Could not remove temporary pending GPS queue")
            }
        }
    }

    private companion object {
        const val MAX_QUEUE_AGE_MS = 120_000L
        const val MAX_FUTURE_FIX_MS = 10_000L
        const val MAX_QUEUED_FIXES = 100
    }
}
