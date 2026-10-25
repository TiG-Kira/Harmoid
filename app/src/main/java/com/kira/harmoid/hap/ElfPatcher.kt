package com.kira.harmoid.hap

object ElfPatcher {
    @JvmStatic
    fun patchSo(path: String): Boolean = ElfTools.patchSo(path)

    /**
     * Patch GOT entries for "sigaction" in a loaded .so to point to
     * libb.so's bridge, bypassing libsigchain.so interception.
     * Returns the number of GOT entries patched.
     */
    @JvmStatic
    external fun patchGot(path: String): Int

    /**
     * Load an .so via native dlopen(RTLD_GLOBAL), preloading sibling
     * DT_NEEDED dependencies so their symbols are available to the HAP.
     * Returns 0 on success, -1 on failure.
     */
    @JvmStatic
    fun nativeLoad(path: String): Int = ElfTools.nativeLoad(path)

    /**
     * Patch DT_RUNPATH (and DT_RPATH) of an ELF .so to "$ORIGIN"
     * so that DT_NEEDED dependencies resolve from the same directory.
     * Returns true if a RUNPATH/RPATH was found and patched.
     */
    @JvmStatic
    fun patchRunpath(path: String): Boolean = ElfTools.patchRunpath(path)
}

/** Kept separate from libb.so so ELF fixes always ship with the APK build. */
private object ElfTools {
    init { System.loadLibrary("harmoid_elf") }

    @JvmStatic external fun patchSo(path: String): Boolean
    @JvmStatic external fun nativeLoad(path: String): Int
    @JvmStatic external fun patchRunpath(path: String): Boolean
}
