package dev.ayaya.dailyobsi.storage

import dev.ayaya.dailyobsi.model.SaveStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SaveCoordinatorTest {
    @Test
    fun `debounce writes only newest revision`() = runTest {
        val writes = mutableListOf<String>()
        val coordinator = SaveCoordinator<String>(this, 750) { writes += it.text }

        coordinator.submit(SaveRevision("note", "one", 1))
        advanceTimeBy(500)
        coordinator.submit(SaveRevision("note", "two", 2))
        advanceUntilIdle()

        assertEquals(listOf("two"), writes)
        assertEquals(SaveStatus.Saved, coordinator.status.value)
    }

    @Test
    fun `manual flush writes immediately`() = runTest {
        val writes = mutableListOf<String>()
        val coordinator = SaveCoordinator<String>(this) { writes += it.text }
        coordinator.submit(SaveRevision("note", "now", 1))

        coordinator.flush()

        assertEquals(listOf("now"), writes)
    }

    @Test
    fun `revision arriving during slow write is saved afterward`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val coordinator = SaveCoordinator<String>(this, 0) { value ->
            writes += value.text
            if (value.revision == 1L) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        coordinator.submit(SaveRevision("note", "one", 1))
        val running = async { coordinator.flush() }
        firstStarted.await()

        coordinator.submit(SaveRevision("note", "two", 2))
        releaseFirst.complete(Unit)
        running.await()
        advanceUntilIdle()

        assertEquals(listOf("one", "two"), writes.distinct())
    }

    @Test
    fun `failed write keeps draft retryable`() = runTest {
        var fail = true
        val writes = mutableListOf<String>()
        val coordinator = SaveCoordinator<String>(this, 0) { value ->
            if (fail) error("disk unavailable")
            writes += value.text
        }
        coordinator.submit(SaveRevision("note", "draft", 1))
        coordinator.flush()
        assertTrue(coordinator.status.value is SaveStatus.Error)

        fail = false
        coordinator.retry()

        assertEquals(listOf("draft"), writes)
        assertEquals(SaveStatus.Saved, coordinator.status.value)
    }
}
