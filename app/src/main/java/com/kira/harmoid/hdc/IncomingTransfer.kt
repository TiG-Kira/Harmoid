package com.kira.harmoid.hdc

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.TreeMap

/** Receives HDC byte-offset chunks, including retries and out-of-order reads. */
internal class IncomingTransfer(val file: File, val expectedSize: Long) : Closeable {
    private val output: RandomAccessFile
    private val ranges = TreeMap<Long, Long>()
    var received: Long = 0
        private set
    val complete: Boolean get() = received == expectedSize

    init {
        require(expectedSize >= 0) { "Negative transfer size" }
        output = RandomAccessFile(file, "rw")
        try { output.setLength(0) } catch (e: Exception) { output.close(); throw e }
    }

    @Synchronized fun accept(packet: ByteArray) {
        require(packet.size >= PAYLOAD_PREFIX_RESERVE) { "Truncated HDC transfer header" }
        val header = TransferPayload.fromBytes(packet.copyOfRange(0, PAYLOAD_PREFIX_RESERVE))
        require(header.index >= 0 && header.index <= expectedSize) { "Invalid transfer offset" }
        val body = packet.copyOfRange(PAYLOAD_PREFIX_RESERVE, packet.size)
        require(header.compressSize == body.size) { "Compressed chunk length mismatch" }
        require(header.uncompressSize in 0..MAX_HDC_PACKET_SIZE) { "Invalid decompressed chunk size" }
        require(header.uncompressSize <= expectedSize - header.index) { "Chunk exceeds expected file size" }
        val bytes = when (header.compressType) {
            0 -> body.also { require(it.size == header.uncompressSize) { "Chunk length mismatch" } }
            1 -> decodeLz4(body, header.uncompressSize)
            else -> throw IllegalArgumentException("Unsupported HDC compression: ${header.compressType}")
        }
        output.seek(header.index)
        output.write(bytes)
        if (bytes.isEmpty()) return
        var start = header.index
        var end = start + bytes.size
        ranges.floorEntry(start)?.takeIf { it.value >= start }?.let {
            start = it.key
            end = maxOf(end, it.value)
            received -= it.value - it.key
            ranges.remove(it.key)
        }
        while (true) {
            val next = ranges.ceilingEntry(start)?.takeIf { it.key <= end } ?: break
            end = maxOf(end, next.value)
            received -= next.value - next.key
            ranges.remove(next.key)
        }
        ranges[start] = end
        received += end - start
    }

    @Synchronized override fun close() = output.close()

    fun abort() {
        try { close() } finally { file.delete() }
    }
}

/** Raw LZ4 block format used by OpenHarmony HDC's LZ4_compress_default. */
internal fun decodeLz4(input: ByteArray, size: Int): ByteArray {
    require(size in 0..MAX_HDC_PACKET_SIZE) { "Invalid LZ4 output size" }
    val output = ByteArray(size)
    var read = 0
    var written = 0
    fun length(base: Int): Int {
        var result = base
        if (base == 15) {
            do {
                require(read < input.size) { "Truncated LZ4 length" }
                val extra = input[read++].toInt() and 255
                require(result <= size - extra) { "LZ4 length exceeds output" }
                result += extra
            } while (extra == 255)
        }
        return result
    }
    while (read < input.size) {
        val token = input[read++].toInt() and 255
        val literals = length(token ushr 4)
        require(literals <= input.size - read && literals <= size - written) { "Invalid LZ4 literals" }
        input.copyInto(output, written, read, read + literals)
        read += literals
        written += literals
        if (read == input.size) break
        require(input.size - read >= 2) { "Truncated LZ4 match" }
        val distance = (input[read].toInt() and 255) or ((input[read + 1].toInt() and 255) shl 8)
        read += 2
        require(distance in 1..written) { "Invalid LZ4 match offset" }
        val match = length(token and 15) + 4
        require(match <= size - written) { "LZ4 match exceeds output" }
        // Matches may overlap (e.g. distance=1 is run-length encoding).
        repeat(match) { output[written] = output[written - distance]; written++ }
    }
    require(written == size) { "LZ4 output length mismatch" }
    return output
}
