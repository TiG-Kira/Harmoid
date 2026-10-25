package com.kira.harmoid.compat

import android.app.Application
import com.kira.harmoid.hap.HapInstaller
import com.kira.harmoid.hdc.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34], application = Application::class, manifest = Config.NONE)
class HdcDaemonCompatibilityTest {
    private fun connect(port: Int): Socket {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            val socket = Socket()
            try {
                socket.soTimeout = 5000
                socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                return socket
            } catch (e: Exception) {
                socket.close()
                if (System.nanoTime() >= deadline) throw e
                Thread.sleep(10)
            }
        } while (true)
    }

    private fun receive(socket: Socket): Triple<PayloadHead, PayloadProtect, ByteArray> {
        val input = DataInputStream(socket.getInputStream())
        val header = ByteArray(PAYLOAD_HEAD_SIZE).also { input.readFully(it) }
        val head = checkNotNull(PayloadHead.fromBytes(header))
        val rest = ByteArray(packetTotalSize(head) - PAYLOAD_HEAD_SIZE).also { input.readFully(it) }
        val packet = header + rest
        return checkNotNull(parsePacket(packet, 0, packet.size))
    }

    @Test fun rapidStopAndRestartCannotReviveAnOldListener() {
        val port = ServerSocket(0).use { it.localPort }
        val daemon = HdcDaemon(RuntimeEnvironment.getApplication(), port, "127.0.0.1")
        try {
            repeat(10) { daemon.start(); daemon.stop() }
            daemon.start()
            daemon.start()
            connect(port).use { socket ->
                socket.getOutputStream().write(buildPacket(1, CMD_KERNEL_HANDSHAKE, SessionHandShake().toBytes()))
                assertEquals(CMD_KERNEL_HANDSHAKE, receive(socket).second.commandFlag)
            }
            assertTrue(daemon.isRunning())
        } finally {
            daemon.stop()
        }
        assertFalse(daemon.isRunning())
    }

    @Test fun realTcpInstallHandlesLz4AndIgnoresHostControlledFilename() {
        val app = RuntimeEnvironment.getApplication()
        val victim = File(app.cacheDir.parentFile, "victim").apply { writeText("keep") }
        val hap = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, value) in mapOf(
                    "module.json" to """{"app":{"bundleName":"com.example.hdc"},"module":{"name":"entry","type":"entry","mainElement":"EntryAbility"}}""",
                    "ets/modules.abc" to "bytecode")) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
                }
            }
        }.toByteArray()
        val port = ServerSocket(0).use { it.localPort }
        val daemon = HdcDaemon(app, port, "127.0.0.1")
        daemon.start()
        try {
            connect(port).use { socket ->
                val output = socket.getOutputStream()
                output.write(buildPacket(1, CMD_KERNEL_HANDSHAKE, SessionHandShake().toBytes()))
                receive(socket)
                val config = ByteArrayOutputStream().apply {
                    writeUint64Field(1, hap.size.toLong(), this)
                    writeStringField(6, "../victim", this)
                    writeUint32Field(8, 1, this)
                }.toByteArray()
                output.write(buildPacket(1, CMD_APP_CHECK, config))
                assertEquals(CMD_APP_BEGIN, receive(socket).second.commandFlag)
                val compressed = ByteArrayOutputStream().apply {
                    write(0xF0)
                    var length = hap.size - 15
                    while (length >= 255) { write(255); length -= 255 }
                    write(length)
                    write(hap)
                }.toByteArray()
                val prefix = ByteArrayOutputStream().apply {
                    writeUint64Field(1, 0, this)
                    writeUint32Field(2, 1, this)
                    writeUint32Field(3, compressed.size, this)
                    writeUint32Field(4, hap.size, this)
                }.toByteArray().copyOf(PAYLOAD_PREFIX_RESERVE)
                output.write(buildPacket(1, CMD_APP_DATA, prefix + compressed))
                val result = receive(socket)
                assertEquals(CMD_APP_FINISH, result.second.commandFlag)
                assertEquals(1, result.third[1].toInt())
            }
            assertEquals("keep", victim.readText())
            val installed = HapInstaller(app).getInstalledHaps().single { it.bundleName == "com.example.hdc" }
            assertEquals("bytecode", File(installed.contentDir, "ets/modules.abc").readText())
        } finally {
            daemon.stop()
        }
    }
}
