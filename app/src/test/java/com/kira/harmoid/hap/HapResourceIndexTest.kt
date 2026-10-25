package com.kira.harmoid.hap

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HapResourceIndexTest {
    @get:Rule val temporary = TemporaryFolder()
    private val module by lazy { temporary.newFolder() }

    private fun putInt(data: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
    }

    private fun v1(): ByteArray = ByteArray(220).apply {
        "Restool 5".toByteArray().copyInto(this)
        putInt(this, 132, 1)
        "KEYS".toByteArray().copyInto(this, 136)
        putInt(this, 140, 148)
        "IDSS".toByteArray().copyInto(this, 148)
        putInt(this, 152, 1)
        putInt(this, 156, 7)
        putInt(this, 160, 164)
        putInt(this, 164, 30)
        putInt(this, 168, 9)
        this[176] = 6
        "hello\u0000".toByteArray().copyInto(this, 178)
        this[184] = 5
        "name\u0000".toByteArray().copyInto(this, 186)
    }

    private fun v2(): ByteArray = ByteArray(240).apply {
        "RestoolV2".toByteArray().copyInto(this)
        putInt(this, 132, 1)
        putInt(this, 136, 200)
        "KEYS".toByteArray().copyInto(this, 140)
        "IDSS".toByteArray().copyInto(this, 152)
        putInt(this, 160, 1)
        putInt(this, 176, 1)
        putInt(this, 180, 7)
        putInt(this, 184, 200)
        putInt(this, 188, 4)
        "name".toByteArray().copyInto(this, 192)
        putInt(this, 208, 1)
        putInt(this, 216, 220)
        this[220] = 5
        "hello".toByteArray().copyInto(this, 222)
    }

    private fun assertValue(data: ByteArray, expected: String?) {
        File(module, "resources.index").writeBytes(data)
        assertEquals(expected, HapBundleLoader.parseStringFromIndex(module, "name"))
        assertEquals(expected, HapBundleLoader.resolveMediaPathById(module, 7))
    }

    @Test fun readsValidV1AndV2() {
        assertValue(v1(), "hello")
        assertValue(v2(), "hello")
    }

    @Test fun rejectsNegativeAndOverflowingV1Offsets() {
        for (offset in listOf(140, 160)) {
            for (value in listOf(-1, Int.MIN_VALUE, Int.MAX_VALUE)) {
                assertValue(v1().apply { putInt(this, offset, value) }, null)
            }
        }
    }

    @Test fun rejectsNegativeAndOverflowingV2OffsetsAndCounts() {
        for (offset in listOf(148, 160, 176, 184, 216)) {
            for (value in listOf(-1, Int.MIN_VALUE, Int.MAX_VALUE)) {
                assertValue(v2().apply { putInt(this, offset, value) }, null)
            }
        }
    }

    @Test fun truncatedIndexesReturnNull() {
        for (size in 0 until 140) assertValue(v2().copyOf(size), null)
    }

    @Test fun keepsResourceRootWhenPathHasNoModulePrefix() {
        assertEquals("resources/base/media/icon.png", HapBundleLoader.stripModulePrefix("resources/base/media/icon.png"))
        assertEquals("resources/base/media/icon.png", HapBundleLoader.stripModulePrefix("entry/resources/base/media/icon.png"))
    }
}
