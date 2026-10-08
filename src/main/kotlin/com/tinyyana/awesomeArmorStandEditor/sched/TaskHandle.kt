package com.tinyyana.awesomeArmorStandEditor.sched

/**
 * Cancellable handle to a scheduled task. It wraps either Bukkit's task handle or Folia's `ScheduledTask`,
 * so callers never touch a scheduler class that Spigot does not have. A repeating task receives its own
 * handle in its body, which is how it cancels itself without capturing a mutable variable.
 */
interface TaskHandle {
    fun cancel()
    val isCancelled: Boolean
}
