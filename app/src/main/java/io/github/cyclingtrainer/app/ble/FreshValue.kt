package io.github.cyclingtrainer.app.ble

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A live reading with a freshness window.
 *
 * BLE fitness sensors stop transmitting when they sleep, when the rider stops
 * pedalling, or when the link dies underneath the stack. Nothing else told the
 * app that, so a frozen power/HR value kept being displayed as if it were live
 * — and worse, it kept being written into the ride CSV as if it were real.
 *
 * [get] returns the stored value only while it is younger than [windowMs];
 * anything older reads as null. Time is passed in rather than read from the
 * clock so the behaviour is directly unit-testable.
 */
class FreshValue<T>(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private var value: T? = null
    private var stamp: Long = Long.MIN_VALUE

    /** Records a reading as of [nowMs] (monotonic milliseconds). */
    @Synchronized
    fun set(newValue: T, nowMs: Long) {
        value = newValue
        stamp = nowMs
    }

    /** The reading if it is still fresh at [nowMs], else null. */
    @Synchronized
    fun get(nowMs: Long): T? {
        val v = value ?: return null
        if (nowMs - stamp > windowMs) {
            // Expire on read so the value cannot flicker back.
            value = null
            return null
        }
        return v
    }

    /** Forgets the reading immediately (used on disconnect). */
    @Synchronized
    fun clear() {
        value = null
        stamp = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 3_000L
    }
}

/**
 * One live sensor value with its own freshness window and an observable
 * version for the UI. [state] bumps on every accepted reading so a combined
 * flow re-evaluates the staleness check; [value] reads the raw latest value.
 */
class Reading<T>(private val fresh: FreshValue<T> = FreshValue()) {
    val state = MutableStateFlow<Long>(0L)

    /** Latest reading if still fresh, else null. */
    fun value(nowMs: Long): T? = fresh.get(nowMs)

    /** Accepts a new reading and publishes it. */
    fun set(v: T, nowMs: Long) {
        fresh.set(v, nowMs)
        state.value = nowMs
    }

    /** Forgets the reading immediately (disconnect). */
    fun clear() {
        fresh.clear()
        state.value = Long.MIN_VALUE
    }
}
