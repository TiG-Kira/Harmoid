package com.kira.harmoid

import com.kira.harmoid.hap.HapFileLock
import java.io.File

/** Files are shared by the launcher, HDC and all HAP worker processes. */
internal class ProcessSlotStore(
    private val directory: File,
    private val maxSlots: Int,
    private val now: () -> Long = System::currentTimeMillis,
    private val isAlive: (Int) -> Boolean = { it > 0 && File("/proc/$it").exists() }
) {
    fun allocate(contentDir: String?): Int = HapFileLock.withLock(directory) {
        for (slot in 0 until maxSlots) {
            val file = slotFile(slot)
            val content = file.takeIf { it.exists() }?.readText().orEmpty()
            if (content.isEmpty() || isStale(content)) {
                file.writeText("$RESERVED${now()}" + (contentDir?.let { "$SEPARATOR$it" } ?: ""))
                return@withLock slot
            }
        }
        -1
    }

    fun claim(slot: Int, pid: Int, contentDir: String?) = HapFileLock.withLock(directory) {
        val file = slotFile(slot)
        val content = file.takeIf { it.exists() }?.readText().orEmpty()
        val owner = content.substringBefore(SEPARATOR).toIntOrNull()
        check(owner == null || owner == pid || !isAlive(owner)) { "Slot $slot is owned by process $owner" }
        val reservedPath = content.substringAfter(SEPARATOR, "").ifEmpty { null }
        check(reservedPath == null || reservedPath == contentDir) { "Slot $slot belongs to another HAP" }
        file.writeText(pid.toString() + (contentDir?.let { "$SEPARATOR$it" } ?: ""))
    }

    fun release(slot: Int, pid: Int) = HapFileLock.withLock(directory) {
        val file = slotFile(slot)
        val content = file.takeIf { it.exists() }?.readText().orEmpty()
        if (content.startsWith(RESERVED) || content.substringBefore(SEPARATOR).toIntOrNull() == pid) {
            check(!file.exists() || file.delete()) { "Cannot release slot $slot" }
        }
    }

    fun contentDir(slot: Int): String? = HapFileLock.withLock(directory) {
        val content = slotFile(slot).takeIf { it.exists() }?.readText().orEmpty()
        // Application needs the last identity even after its PID dies: Android
        // can recreate an existing task before Activity has reclaimed the slot.
        content.substringAfter(SEPARATOR, "").ifEmpty { null }
    }

    fun activeContentDirs(): List<String> = HapFileLock.withLock(directory) {
        (0 until maxSlots).mapNotNull { slot ->
            val content = slotFile(slot).takeIf { it.exists() }?.readText().orEmpty()
            if (content.isEmpty() || isStale(content)) null
            else content.substringAfter(SEPARATOR, "").ifEmpty { null }
        }
    }

    private fun slotFile(slot: Int): File {
        require(slot in 0 until maxSlots) { "Invalid process slot: $slot" }
        return File(directory, "hap_slot_$slot")
    }

    private fun isStale(content: String): Boolean {
        if (content.startsWith(RESERVED)) {
            val timestamp = content.removePrefix(RESERVED).substringBefore(SEPARATOR).toLongOrNull()
                ?: return true
            val age = now() - timestamp
            return age < 0 || age > RESERVATION_TIMEOUT_MS
        }
        return !isAlive(content.substringBefore(SEPARATOR).toIntOrNull() ?: return true)
    }

    companion object {
        private const val RESERVED = "RESERVED|"
        private const val SEPARATOR = "||"
        private const val RESERVATION_TIMEOUT_MS = 30_000L
    }
}
