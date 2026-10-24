package com.leslie.tvhome

/** Rebind only our service; delayed work must use current settings and current user intent. */
internal class ServiceRecovery(
    private val ownService: String,
    private val read: () -> List<String>,
    private val write: (List<String>) -> Unit,
    private val allowed: () -> Boolean,
    private val bound: () -> Boolean,
    private val later: (() -> Unit) -> Unit,
) {
    private var generation = 0
    private var pending = false

    fun cancel() { generation++; pending = false }

    fun repair() {
        if (pending || !allowed() || bound()) return
        val current = read()
        if (ownService !in current) return
        val ticket = ++generation
        pending = true
        try {
            write(current.filterNot { it == ownService })
            later {
                if (ticket == generation) {
                    pending = false
                    if (allowed() && !bound()) write((read() + ownService).distinct())
                }
            }
        } catch (e: RuntimeException) {
            pending = false
            throw e
        }
    }
}
