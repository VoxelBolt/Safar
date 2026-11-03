package com.safar.app.data.location

import com.safar.app.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PendingDriverLocationQueueTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private fun sample(latitude: Double, timestamp: Long) =
        LocationSample(GeoPoint(latitude, 69.0), timestamp, accuracyMeters = 5.0)

    @Test
    fun persistsOrderedFixesDeduplicatesThemAndAcknowledgesSuccessfulBatch() {
        val queueFile = File(temporaryFolder.root, "gps-queue.json")
        val firstQueue = PendingDriverLocationQueue(queueFile)
        val now = 1_000_000L

        assertTrue(firstQueue.enqueue("driver-a", "route-8", sample(40.0, now - 4_000L), 90f, now))
        assertFalse(firstQueue.enqueue("driver-a", "route-8", sample(40.00001, now - 4_000L), 90f, now))
        assertTrue(firstQueue.enqueue("driver-a", "route-8", sample(40.00002, now - 2_000L), 95f, now))

        // A new queue instance represents a service/process restart.
        val restoredQueue = PendingDriverLocationQueue(queueFile)
        assertEquals(listOf(now - 4_000L, now - 2_000L), restoredQueue.pendingFor("driver-a", "route-8", now).map { it.sample.timestampEpochMs })

        restoredQueue.acknowledgeThrough("driver-a", "route-8", now - 4_000L)
        assertEquals(listOf(now - 2_000L), restoredQueue.pendingFor("driver-a", "route-8", now).map { it.sample.timestampEpochMs })
    }

    @Test
    fun rejectsExpiredFutureAndInvalidCoordinates() {
        val queue = PendingDriverLocationQueue(File(temporaryFolder.root, "gps-queue.json"))
        val now = 1_000_000L

        assertFalse(queue.enqueue("driver-a", "route-8", sample(40.0, now - 120_001L), null, now))
        assertFalse(queue.enqueue("driver-a", "route-8", sample(40.0, now + 10_001L), null, now))
        assertFalse(queue.enqueue("driver-a", "route-8", sample(91.0, now), null, now))
        assertTrue(queue.pendingFor("driver-a", "route-8", now).isEmpty())
    }

    @Test
    fun doesNotExposeOrReplayAnotherDriverOrRouteQueue() {
        val queue = PendingDriverLocationQueue(File(temporaryFolder.root, "gps-queue.json"))
        val now = 1_000_000L
        queue.enqueue("driver-a", "route-8", sample(40.0, now - 1_000L), null, now)

        assertTrue(queue.pendingFor("driver-b", "route-3", now).isEmpty())
        assertTrue(queue.pendingFor("driver-a", "route-8", now).isEmpty())
    }
}
