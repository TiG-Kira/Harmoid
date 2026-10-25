package com.kira.harmoid.hdc

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File

class HdcTransferTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun chunk(index: Long, bytes: ByteArray, rawSize: Int = bytes.size, compression: Int = 0): ByteArray {
        val header = ByteArrayOutputStream().apply {
            writeUint64Field(1, index, this)
            writeUint32Field(2, compression, this)
            writeUint32Field(3, bytes.size, this)
            writeUint32Field(4, rawSize, this)
        }.toByteArray().copyOf(PAYLOAD_PREFIX_RESERVE)
        return header + bytes
    }

    @Test fun unsignedVarintsRoundTripIncludingHighBitValues() {
        for (value in listOf(0L, 127L, 128L, 16384L, Long.MAX_VALUE, Long.MIN_VALUE, -1L)) {
            val bytes = ByteArrayOutputStream().apply { writeVarint(value, this) }.toByteArray()
            assertEquals(value, readVarint(bytes.inputStream()))
            assertTrue(bytes.size <= 10)
        }
    }

    @Test fun malformedVarintsAndProtobufLengthsFailBeforeAllocation() {
        assertThrows(IllegalArgumentException::class.java) { readVarint(ByteArray(11) { 0x80.toByte() }.inputStream()) }
        assertThrows(IllegalArgumentException::class.java) {
            readVarint((ByteArray(9) { 0x80.toByte() } + byteArrayOf(2)).inputStream())
        }
        assertThrows(EOFException::class.java) { ProtoReader(byteArrayOf(0x80.toByte()).inputStream()).readTag() }
        assertThrows(IllegalArgumentException::class.java) { ProtoReader(byteArrayOf(15).inputStream()).readTag() }
        val length = ByteArrayOutputStream().apply { writeVarint(Long.MAX_VALUE, this) }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { ProtoReader(length.inputStream()).readString() }
    }

    @Test fun packetParsingSupportsOffsetsAndPartialReads() {
        val packet = buildPacket(7, CMD_APP_DATA, "content".toByteArray())
        for (size in 0 until packet.size) assertNull(parsePacket(packet, 0, size))
        val padded = byteArrayOf(0, 0, 0) + packet
        val parsed = checkNotNull(parsePacket(padded, 3, packet.size))
        assertEquals(7, parsed.second.channelId)
        assertArrayEquals("content".toByteArray(), parsed.third)
        assertEquals(packet.size, packetTotalSize(parsed.first))
    }

    @Test fun packetSizesCannotOverflowOrExceedReceiveBuffer() {
        for (size in listOf(-1, Int.MAX_VALUE, MAX_HDC_PACKET_SIZE)) {
            val head = PayloadHead(headSize = 10, dataSize = size)
            assertThrows(IllegalArgumentException::class.java) { parsePacket(head.toBytes(), 0, PAYLOAD_HEAD_SIZE) }
        }
        assertThrows(IllegalArgumentException::class.java) { parsePacket(ByteArray(12), -1, 12) }
    }

    @Test fun retriesAndOutOfOrderChunksDoNotCompleteAFileWithHoles() {
        val file = temporary.newFile()
        IncomingTransfer(file, 15).use { transfer ->
            transfer.accept(chunk(10, "klmno".toByteArray()))
            transfer.accept(chunk(0, "abcde".toByteArray()))
            transfer.accept(chunk(0, "abcde".toByteArray()))
            assertEquals(10L, transfer.received)
            assertFalse(transfer.complete)
            transfer.accept(chunk(3, "defghij".toByteArray()))
            assertTrue(transfer.complete)
            assertEquals(15L, transfer.received)
        }
        assertEquals("abcdefghijklmno", file.readText())
    }

    @Test fun receivesLz4ChunksAndOverlappingMatches() {
        val encoded = byteArrayOf(0x35, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 3, 0, 0x50) +
            "HELLO".toByteArray()
        val file = temporary.newFile()
        IncomingTransfer(file, 17).use { transfer ->
            transfer.accept(chunk(0, encoded, 17, 1))
            assertTrue(transfer.complete)
        }
        assertEquals("abcabcabcabcHELLO", file.readText())
        val literal = ByteArray(20) { it.toByte() }
        assertArrayEquals(literal, decodeLz4(byteArrayOf(0xF0.toByte(), 5) + literal, 20))
    }

    @Test fun rejectsTruncatedOrOversizedTransferChunks() {
        IncomingTransfer(temporary.newFile(), 5).use { transfer ->
            assertThrows(IllegalArgumentException::class.java) { transfer.accept(ByteArray(63)) }
            assertThrows(IllegalArgumentException::class.java) { transfer.accept(chunk(4, "xx".toByteArray())) }
            assertThrows(IllegalArgumentException::class.java) { transfer.accept(chunk(-1, byteArrayOf(1))) }
            assertThrows(IllegalArgumentException::class.java) { transfer.accept(chunk(0, byteArrayOf(1), 2)) }
            assertThrows(IllegalArgumentException::class.java) { transfer.accept(chunk(0, byteArrayOf(1), 1, 3)) }
            assertEquals(0L, transfer.received)
        }
    }

    @Test fun rejectsMalformedLz4WithoutBufferOverruns() {
        for (data in listOf(byteArrayOf(0x50), byteArrayOf(0, 0, 0), byteArrayOf(0xF0.toByte()), byteArrayOf(0, 1))) {
            assertThrows(IllegalArgumentException::class.java) { decodeLz4(data, 10) }
        }
        assertThrows(IllegalArgumentException::class.java) { decodeLz4(byteArrayOf(0), Int.MAX_VALUE) }
    }

    @Test fun abortClosesAndDeletesPartialTransfers() {
        val file = temporary.newFile()
        val transfer = IncomingTransfer(file, 5)
        transfer.accept(chunk(0, "ab".toByteArray()))
        transfer.abort()
        assertFalse(file.exists())
        assertTrue(IncomingTransfer(temporary.newFile(), 0).use { it.complete })
    }

    @Test fun remapsOnlySafeDevelopmentPaths() {
        val cache = temporary.newFolder()
        val root = File(cache, "hdc").canonicalFile
        assertEquals(File(root, "entry.hap"), HdcPaths.resolve(cache, "/data/local/tmp/entry.hap"))
        assertEquals(File(root, "space name.hap"), HdcPaths.resolve(cache, "space name.hap"))
        assertEquals(File(root, "sub/entry.hap"), HdcPaths.child(root, "sub/entry.hap"))
        for (path in listOf("../../private", "/data/local/tmp/../private", "/sdcard/private", "C:/private", "..\\private")) {
            assertThrows(IllegalArgumentException::class.java) { HdcPaths.resolve(cache, path) }
        }
        assertThrows(IllegalArgumentException::class.java) { HdcPaths.child(root, "../private") }
    }
}
