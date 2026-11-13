package dev.nova.zerotier.vpn

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class PacketWorkBudget(
    private val maxItems: Int,
    private val maxBytes: Long,
) {
    private val itemCount = AtomicInteger()
    private val byteCount = AtomicLong()

    val inFlightItems: Int get() = itemCount.get()
    val inFlightBytes: Long get() = byteCount.get()

    fun tryAcquire(sizeBytes: Int): PacketWorkLease? {
        if (sizeBytes < 0 || sizeBytes.toLong() > maxBytes) return null
        while (true) {
            val current = itemCount.get()
            if (current >= maxItems) return null
            if (itemCount.compareAndSet(current, current + 1)) break
        }
        while (true) {
            val current = byteCount.get()
            if (current + sizeBytes > maxBytes) {
                itemCount.decrementAndGet()
                return null
            }
            if (byteCount.compareAndSet(current, current + sizeBytes)) break
        }
        return PacketWorkLease {
            byteCount.addAndGet(-sizeBytes.toLong())
            itemCount.decrementAndGet()
        }
    }
}

internal class PacketWorkLease internal constructor(
    private val releaseWork: () -> Unit,
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) releaseWork()
    }
}
