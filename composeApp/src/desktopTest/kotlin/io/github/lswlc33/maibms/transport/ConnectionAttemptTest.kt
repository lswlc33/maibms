package io.github.lswlc33.maibms.transport

import io.github.lswlc33.maibms.data.PlatformLock
import io.github.lswlc33.maibms.data.withLock
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Tests exercise the production attempt/signals/ownership helper used by both native transports. */
class ConnectionAttemptTest {
    @Test fun readyThenDropBeforeWaitNeverLosesDropOrRepublishesConnected() = runBlocking {
        val attempts = ConnectionAttempts()
        val owner = attempts.begin("A")
        var connected = false
        assertTrue(attempts.publishReady(owner) { connected = true })
        assertTrue(attempts.finish(owner))
        connected = false

        // Native callback dropped the link before the connection loop resumed from ready.await().
        assertTrue(withTimeout(1_000) { owner.ready.await() })
        withTimeout(1_000) { owner.dropped.await() }
        assertFalse(attempts.publishReady(owner) { connected = true })
        assertFalse(connected)
    }

    @Test fun dropBeforeReadyCompletesAllWaitersAndRejectsReady() = runBlocking {
        val attempts = ConnectionAttempts()
        val owner = attempts.begin("A")
        assertTrue(attempts.finish(owner))
        withTimeout(1_000) { owner.services.await() }
        assertFalse(withTimeout(1_000) { owner.ready.await() })
        withTimeout(1_000) { owner.dropped.await() }
        assertFalse(attempts.publishReady(owner) { error("Dropped attempt published Connected") })
    }

    @Test fun serviceWaiterSeesAllThreeCandidatesNotAnEmptyTenSecondBudget() = runBlocking {
        val attempts = ConnectionAttempts()
        val owner = attempts.begin("A")
        assertFalse(owner.services.isCompleted)
        attempts.servicesDiscovered(owner, 3)
        owner.services.await()
        assertEquals(3, owner.candidateCount)
        assertEquals(26_000L, 8_000L * owner.candidateCount.coerceAtLeast(1) + 2_000L)
    }

    @Test fun oldAttemptCleanupAndServicesCannotTouchNewAttempt() {
        val attempts = ConnectionAttempts()
        val a = attempts.begin("A")
        val b = attempts.begin("B")
        assertFalse(attempts.finish(a))
        attempts.servicesDiscovered(a, 3)
        assertSame(b, attempts.current)
        assertTrue(attempts.owns(b))
        assertFalse(b.services.isCompleted)
        assertFalse(b.ready.isCompleted)
        assertFalse(b.dropped.isCompleted)
    }

    @Test fun sameIdentifierDifferentNativeWrapperIsAccepted() {
        class Wrapper(val identifier: String)
        val stored = Wrapper("DEVICE-A")
        val callback = Wrapper("device-a")
        val attempts = ConnectionAttempts()
        attempts.begin(stored.identifier)
        assertFalse(stored === callback)
        assertTrue(attempts.acceptsCallback(callback.identifier))
        assertFalse(attempts.acceptsCallback("DEVICE-B"))
    }

    @Test fun previousGenerationAndPreviousCandidateAreRejectedIncludingMeterSettle() {
        val attempts = ConnectionAttempts()
        val a = attempts.begin("same-device")
        val first = Any()
        val next = Any()
        attempts.selectCandidate(a, first)
        assertTrue(attempts.acceptsCandidate(a, first))
        attempts.selectCandidate(a, next)
        assertFalse(attempts.acceptsCandidate(a, first)) // old candidate subscription fallback/meter settle
        assertTrue(attempts.acceptsCandidate(a, next))
        val b = attempts.begin("same-device")
        attempts.selectCandidate(b, first)
        assertTrue(b.generation > a.generation)
        assertFalse(attempts.acceptsCandidate(a, first)) // even if the characteristic/candidate is reused
        assertFalse(attempts.acceptsCandidate(a, next))
        assertTrue(attempts.acceptsCandidate(b, first))
        assertFalse(attempts.publishReady(a) { error("Old generation published Connected") })
    }

    @Test fun everyOldACallbackIsRejectedAfterSwitchingToB() {
        // Delegate callbacks lack generations: identifier filtering must happen before any mutation.
        enumValues<Callback>().forEach { callback ->
            val attempts = ConnectionAttempts()
            attempts.begin("A")
            val b = attempts.begin("B")
            var discoveries = 0
            var candidateIndex = 0
            var frames = 0
            var connected = false
            fun deliver(identifier: String) {
                if (!attempts.acceptsCallback(identifier)) return
                when (callback) {
                    Callback.Connect -> discoveries++
                    Callback.Fail, Callback.Disconnect -> attempts.finish(b)
                    Callback.Services, Callback.Characteristics -> attempts.servicesDiscovered(b, 3)
                    Callback.NotifySuccess -> attempts.publishReady(b) { connected = true }
                    Callback.NotifyFailure -> candidateIndex++
                    Callback.Data -> frames++
                }
            }
            deliver("A")
            assertSame(b, attempts.current, callback.name)
            assertEquals(0, discoveries, callback.name)
            assertEquals(0, candidateIndex, callback.name)
            assertEquals(0, frames, callback.name)
            assertFalse(connected, callback.name)
            assertFalse(b.services.isCompleted, callback.name)
            assertFalse(b.ready.isCompleted, callback.name)
            assertFalse(b.dropped.isCompleted, callback.name)
        }
    }

    @Test fun readyAndDropSerializedWithPublicationCannotResurrectConnection() {
        repeat(100) {
            val lock = PlatformLock()
            val attempts = ConnectionAttempts()
            val owner = withLock(lock) { attempts.begin("A") }
            var connected = false
            val start = CountDownLatch(1)
            val ready = thread {
                start.await()
                withLock(lock) { attempts.publishReady(owner) { connected = true } }
            }
            val drop = thread {
                start.await()
                withLock(lock) {
                    attempts.finish(owner)
                    connected = false
                }
            }
            start.countDown()
            ready.join()
            drop.join()
            assertFalse(connected)
            assertTrue(owner.dropped.isCompleted)
        }
    }

    @Test fun sameIdentifierCannotReopenUntilCancellationTerminalCallback() = runBlocking {
        val barrier = ConnectionTerminationBarrier()
        val oldCancellation = barrier.begin("DEVICE-A")
        assertSame(oldCancellation, barrier.begin("device-a"))
        assertSame(oldCancellation, barrier.waiting("device-a"))
        assertFalse(oldCancellation.isCompleted)
        // Another device's terminal callback cannot release A's cancellation barrier.
        assertFalse(barrier.complete("DEVICE-B"))
        assertFalse(oldCancellation.isCompleted)
        assertTrue(barrier.complete("device-a"))
        withTimeout(1_000) { oldCancellation.await() }
        assertNull(barrier.waiting("DEVICE-A"))
        assertFalse(barrier.complete("DEVICE-A"))
    }

    @Test fun oldATerminationOnlyReleasesABarrierNeverFinishesB() {
        val attempts = ConnectionAttempts()
        val barrier = ConnectionTerminationBarrier()
        val a = attempts.begin("A")
        barrier.begin(a.identifier)
        attempts.finish(a)
        val b = attempts.begin("B")
        // Mirrors central terminal handlers: cancellation barrier first, then current identifier.
        if (!barrier.complete("A") && attempts.acceptsCallback("A")) attempts.finish(b)
        assertSame(b, attempts.current)
        assertFalse(b.dropped.isCompleted)
    }

    @Test fun managerOutageRecoversMissingTerminalBarrierOnlyByRetiringEpoch() = runBlocking {
        val barriers = ConnectionTerminationBarrier()
        val epochs = ConnectionManagerEpochs(barriers)
        val attempts = ConnectionAttempts()
        val oldEpoch = epochs.current
        assertNull(epochs.poweredOn(oldEpoch))
        val owner = attempts.begin("same-device")
        val cancellation = barriers.begin(owner.identifier, oldEpoch)

        assertTrue(epochs.unavailable(oldEpoch)) // PoweredOff / Resetting manager state entry
        attempts.finish(owner)
        assertFalse(epochs.accepts(oldEpoch))
        assertFalse(cancellation.isCompleted) // neither outage nor a delay releases cancellation
        withTimeout(1_000) { owner.dropped.await() }
        assertFalse(withTimeout(1_000) { owner.ready.await() })

        val nextEpoch = epochs.poweredOn(oldEpoch)
        assertEquals(oldEpoch + 1, nextEpoch)
        withTimeout(1_000) { cancellation.await() } // no terminal callback was needed from dead manager
        assertNull(barriers.waiting(owner.identifier, oldEpoch))
        assertFalse(epochs.accepts(oldEpoch))
        assertFalse(epochs.accepts(epochs.current)) // replacement awaits its own PoweredOn
        assertNull(epochs.poweredOn(epochs.current))
        assertTrue(epochs.accepts(epochs.current))
    }

    @Test fun oldManagerCallbacksWithSameIdentifierCannotAffectNewAttemptOrBarrier() {
        val barriers = ConnectionTerminationBarrier()
        val epochs = ConnectionManagerEpochs(barriers)
        val oldEpoch = epochs.current
        epochs.poweredOn(oldEpoch)
        epochs.unavailable(oldEpoch)
        epochs.poweredOn(oldEpoch)
        val newEpoch = epochs.current
        epochs.poweredOn(newEpoch)
        val attempts = ConnectionAttempts()
        val current = attempts.begin("same-device")
        val pending = barriers.begin(current.identifier, newEpoch)

        (enumValues<Callback>().map { it.name } + "Scan").forEach { callback ->
            if (epochs.accepts(oldEpoch) && attempts.acceptsCallback(current.identifier)) {
                error("Old manager $callback escaped epoch guard")
            }
        }
        assertFalse(epochs.isCurrent(oldEpoch)) // old state callback cannot enter recovery either
        assertFalse(barriers.complete(current.identifier, oldEpoch))
        assertSame(pending, barriers.waiting(current.identifier, newEpoch))
        assertFalse(pending.isCompleted)
        assertSame(current, attempts.current)
        assertFalse(current.dropped.isCompleted)
        assertFalse(epochs.unavailable(oldEpoch)) // old state callbacks cannot retire new manager
        assertNull(epochs.poweredOn(oldEpoch))
        assertEquals(newEpoch, epochs.current)
        assertTrue(epochs.accepts(newEpoch))
    }

    @Test fun repeatedUnavailableStatesAndInitialReplacementStatesCannotSpinManagers() {
        val epochs = ConnectionManagerEpochs(ConnectionTerminationBarrier())
        val initial = epochs.current
        repeat(100) { assertFalse(epochs.unavailable(initial)) } // initial Off/Resetting before first On
        assertEquals(initial, epochs.current)
        epochs.poweredOn(initial)
        assertTrue(epochs.unavailable(initial))
        repeat(100) { assertFalse(epochs.unavailable(initial)) }
        assertEquals(initial, epochs.current) // no manager allocation while still unavailable
        assertEquals(initial + 1, epochs.poweredOn(initial))
        val replacement = epochs.current
        repeat(100) {
            assertFalse(epochs.unavailable(replacement)) // replacement starts Off/Resetting
            assertNull(epochs.poweredOn(initial)) // retired manager's duplicate recovery ignored
        }
        assertEquals(replacement, epochs.current)
        assertNull(epochs.poweredOn(replacement)) // first real On arms it without another allocation
        assertTrue(epochs.accepts(replacement))
        assertTrue(epochs.unavailable(replacement)) // a later real outage can recover once again
        assertEquals(replacement + 1, epochs.poweredOn(replacement))
    }

    @Test fun managerRetirementDoesNotReleaseAnotherEpochsCancellation() {
        val barriers = ConnectionTerminationBarrier()
        val epochs = ConnectionManagerEpochs(barriers)
        val oldEpoch = epochs.current
        epochs.poweredOn(oldEpoch)
        val old = barriers.begin("same-device", oldEpoch)
        val newer = barriers.begin("same-device", oldEpoch + 1)
        epochs.unavailable(oldEpoch)
        epochs.poweredOn(oldEpoch)
        assertTrue(old.isCompleted)
        assertFalse(newer.isCompleted)
        assertSame(newer, barriers.waiting("same-device", epochs.current))
    }

    private enum class Callback {
        Connect, Fail, Disconnect, Services, Characteristics, NotifySuccess, NotifyFailure, Data,
    }
}
