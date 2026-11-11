package com.helmet.guard.core.ble

/** FFF1 and FFF2 share sequence numbers but may finish decoding in different orders. */
class PacketSequenceTracker {
    private var newest: Int? = null
    private val missing = mutableSetOf<Int>()
    private var loss = 0L

    @Synchronized fun accept(sequence: Int): Long {
        val value = sequence and 0xff
        val previous = newest
        if (previous == null) {
            newest = value
            return loss
        }
        val forward = (value - previous) and 0xff
        if (forward in 1..127) {
            for (step in 1..forward) {
                val slot = (previous + step) and 0xff
                missing.remove(slot) // Any earlier gap at this slot belongs to a previous wrap.
                if (step < forward) {
                    missing.add(slot)
                    loss++
                }
            }
            newest = value
        } else if (forward != 0 && missing.remove(value)) {
            loss-- // A delayed frame filled a gap; duplicates never count as 255 lost frames.
        }
        return loss
    }

    @Synchronized fun resetConnection() {
        newest = null
        missing.clear()
    }
}
