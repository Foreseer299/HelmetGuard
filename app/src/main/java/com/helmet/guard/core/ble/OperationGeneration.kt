package com.helmet.guard.core.ble

/** Caller serializes access with its operation queue. Old timeouts cannot consume a new write. */
internal class OperationGeneration {
    private var serial = 0L
    private var active: Long? = null
    fun begin(): Long = (++serial).also { active = it }
    fun isCurrent(token: Long): Boolean = active == token
    fun finish(token: Long): Boolean = if (active == token) { active = null; true } else false
    fun clear() { active = null }
}
