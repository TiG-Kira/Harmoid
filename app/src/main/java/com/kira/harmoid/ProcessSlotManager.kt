package com.kira.harmoid

import android.content.Context
import android.content.Intent
import android.os.Process
import com.kira.harmoid.hap.HapFileLock
import com.kira.harmoid.hap.HapInstallStorage
import com.kira.harmoid.hap.HapPaths
import java.io.File

object ProcessSlotManager {

    const val MAX_SLOTS = 10

    /** Allocate a slot and start HoaAbilityActivity{N}.  Shared by shortcut,
     *  list-click, and DevTest launch paths.  Returns the slot number, or
     *  -1 if all slots are occupied. */
    fun launchHap(context: Context,
                  bundleName: String,
                  moduleName: String,
                  abilityName: String,
                  wantParams: String? = null): Int {
        HapPaths.validateNames(bundleName, moduleName)
        require(abilityName.isNotBlank()) { "Ability name must not be empty" }
        // Do not block the Android main thread behind a large HDC installation.
        return HapFileLock.withLock(File(context.filesDir, "hap"), blocking = false) {
            HapInstallStorage(context.filesDir).recover()
            val fullModuleName = "$bundleName.$moduleName"
            val contentDir = File(context.filesDir, "hap/$fullModuleName").absolutePath
            check(File(contentDir, "module.json").isFile) { "HAP module is not installed: $fullModuleName" }
            val slot = allocateSlot(context, contentDir)
            if (slot < 0) return@withLock -1
            val forward = Intent().apply {
                setClassName(context.packageName,
                    "${context.packageName}.HoaAbilityActivity$slot")
                putExtra("BUNDLE_NAME", bundleName)
                putExtra("MODULE_NAME", moduleName)
                putExtra("ABILITY_NAME", abilityName)
                putExtra("PROCESS_SLOT", slot)
                if (!wantParams.isNullOrEmpty()) putExtra("params", wantParams)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                         Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                         Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            try {
                context.startActivity(forward)
            } catch (e: RuntimeException) {
                try {
                    releaseSlot(context, slot)
                } catch (cleanup: Exception) {
                    e.addSuppressed(cleanup)
                }
                throw e
            }
            slot
        }
    }

    fun allocateSlot(context: Context, contentDir: String? = null): Int =
        store(context).allocate(contentDir)

    fun claimSlot(context: Context, slot: Int, contentDir: String? = null) =
        store(context).claim(slot, Process.myPid(), contentDir)

    fun releaseSlot(context: Context, slot: Int) =
        store(context).release(slot, Process.myPid())

    fun getContentDir(context: Context, slot: Int): String? =
        store(context).contentDir(slot)

    private fun store(context: Context) =
        ProcessSlotStore(File(context.filesDir, "process_slots"), MAX_SLOTS)
}
