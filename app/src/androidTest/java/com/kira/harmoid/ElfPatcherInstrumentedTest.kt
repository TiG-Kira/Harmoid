package com.kira.harmoid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kira.harmoid.hap.ElfPatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exercises the shipped JNI library on an actual arm64 Android device. */
@RunWith(AndroidJUnit4::class)
class ElfPatcherInstrumentedTest {
    @Test fun malformedElfCannotCrashTheInstallerOrLoader() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("invalid-elf-", ".so", context.cacheDir)
        try {
            for (bytes in listOf(ByteArray(0), ByteArray(8), ByteArray(64).apply {
                byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1).copyInto(this)
                ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putLong(32, Long.MAX_VALUE)
            })) {
                file.writeBytes(bytes)
                assertFalse(ElfPatcher.patchSo(file.path))
                assertFalse(ElfPatcher.patchRunpath(file.path))
                assertEquals(-1, ElfPatcher.nativeLoad(file.path))
            }
        } finally {
            file.delete()
        }
    }

    @Test fun patchesAnActualPackagedLibraryThroughTheNewJniBinding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.applicationInfo.nativeLibraryDir, "libharmoid_elf.so")
        assertTrue(source.isFile)
        val copy = File.createTempFile("valid-elf-", ".so", context.cacheDir)
        try {
            source.copyTo(copy, overwrite = true)
            assertTrue(ElfPatcher.patchSo(copy.path))
            assertTrue(ElfPatcher.patchSo(copy.path))
        } finally {
            copy.delete()
        }
    }
}
