package com.helmet.guard.core.location

/** Prefer monotonic fix timestamps: changing the wall clock must not revive an old location. */
internal fun locationAgeMs(fixElapsedNanos: Long, nowElapsedNanos: Long, fixWallMs: Long, nowWallMs: Long): Long? {
    val age = if (fixElapsedNanos > 0) {
        if (fixElapsedNanos > nowElapsedNanos) return null
        (nowElapsedNanos - fixElapsedNanos) / 1_000_000
    } else {
        if (fixWallMs > nowWallMs) return null
        nowWallMs - fixWallMs
    }
    return age.takeIf { it >= 0 }
}
