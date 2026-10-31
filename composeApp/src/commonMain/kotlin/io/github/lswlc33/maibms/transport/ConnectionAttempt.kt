package io.github.lswlc33.maibms.transport

import kotlinx.coroutines.CompletableDeferred

/** One native connection attempt. All signals exist before the platform can call back. */
internal class ConnectionAttempt internal constructor(val identifier: String, val generation: Int) {
    val services = CompletableDeferred<Unit>()
    val ready = CompletableDeferred<Boolean>()
    val dropped = CompletableDeferred<Unit>()
    internal var valid = true
    internal var candidate: Any? = null
    var candidateCount: Int = 0
        internal set

    internal fun finish() {
        valid = false
        candidate = null
        services.complete(Unit)
        ready.complete(false)
        dropped.complete(Unit)
    }
}

/**
 * Small ownership/signals helper shared by native transports. The caller serializes every access
 * with its platform lock (including publishing Connected); this is not a new connection loop.
 * Identifiers recognize native wrappers; captured attempts/candidates recognize async work.
 */
internal class ConnectionAttempts {
    var current: ConnectionAttempt? = null
        private set
    private var generation = 0

    fun begin(identifier: String): ConnectionAttempt {
        current?.finish()
        return ConnectionAttempt(identifier, ++generation).also { current = it }
    }

    fun owns(attempt: ConnectionAttempt): Boolean = current === attempt && attempt.valid

    fun acceptsCallback(identifier: String): Boolean =
        current?.let { owns(it) && it.identifier.equals(identifier, ignoreCase = true) } == true

    /** Publish candidates/count before waking the connection-budget waiter. */
    fun servicesDiscovered(attempt: ConnectionAttempt, candidateCount: Int) {
        if (!owns(attempt)) return
        attempt.candidateCount = candidateCount
        attempt.services.complete(Unit)
    }

    fun selectCandidate(attempt: ConnectionAttempt, candidate: Any) {
        if (owns(attempt)) attempt.candidate = candidate
    }

    fun acceptsCandidate(attempt: ConnectionAttempt, candidate: Any): Boolean =
        owns(attempt) && attempt.candidate === candidate

    fun publishReady(attempt: ConnectionAttempt, publish: () -> Unit): Boolean {
        if (!owns(attempt) || attempt.dropped.isCompleted || attempt.ready.isCompleted) return false
        publish()
        attempt.ready.complete(true)
        return true
    }

    /** An old loop/callback must never clear the attempt that replaced it. */
    fun finish(attempt: ConnectionAttempt): Boolean {
        if (!owns(attempt)) return false
        current = null
        attempt.finish()
        return true
    }
}

/**
 * CoreBluetooth cancellation is asynchronous and its callbacks carry no connection generation.
 * Do not reconnect the same identifier until a terminal callback has drained the old connection.
 * A delay or the current generation cannot substitute for this barrier. Caller serializes access.
 */
internal class ConnectionTerminationBarrier {
    private data class Key(val epoch: Int, val identifier: String)
    private val pending = mutableMapOf<Key, CompletableDeferred<Unit>>()

    fun begin(identifier: String, epoch: Int = 0): CompletableDeferred<Unit> =
        pending.getOrPut(Key(epoch, identifier.uppercase())) { CompletableDeferred() }

    fun waiting(identifier: String, epoch: Int = 0): CompletableDeferred<Unit>? =
        pending[Key(epoch, identifier.uppercase())]

    fun complete(identifier: String, epoch: Int = 0): Boolean {
        val signal = pending.remove(Key(epoch, identifier.uppercase())) ?: return false
        signal.complete(Unit)
        return true
    }

    /** Only a retired manager epoch can release cancellations without terminal callbacks. */
    internal fun retireEpoch(epoch: Int) {
        val keys = pending.keys.filter { it.epoch == epoch }
        keys.forEach { pending.remove(it)?.complete(Unit) }
    }
}

/**
 * CoreBluetooth manager/delegate lifetime guard. Caller serializes access with its platform lock.
 * An unavailable manager is retained only to observe recovery, not to process device callbacks.
 * Recovery replaces it once; the replacement must reach PoweredOn before it can itself retire.
 * Thus repeated initial PoweredOff/Resetting events cannot cause a manager-recreation spin.
 */
internal class ConnectionManagerEpochs(private val terminations: ConnectionTerminationBarrier) {
    var current: Int = 0
        private set
    private var poweredOn = false
    private var armed = false
    private var invalidated = false

    fun isCurrent(epoch: Int): Boolean = epoch == current

    fun accepts(epoch: Int): Boolean = isCurrent(epoch) && poweredOn && !invalidated

    /** Returns true once per previously operational manager outage, to invalidate its attempt. */
    fun unavailable(epoch: Int): Boolean {
        if (!isCurrent(epoch)) return false
        poweredOn = false
        if (!armed || invalidated) return false
        invalidated = true
        return true
    }

    /** Returns a new epoch only when an invalidated manager reports real PoweredOn recovery. */
    fun poweredOn(epoch: Int): Int? {
        if (!isCurrent(epoch)) return null
        if (!invalidated) {
            poweredOn = true
            armed = true
            return null
        }
        current++
        poweredOn = false
        armed = false
        invalidated = false
        terminations.retireEpoch(epoch)
        return current
    }
}
