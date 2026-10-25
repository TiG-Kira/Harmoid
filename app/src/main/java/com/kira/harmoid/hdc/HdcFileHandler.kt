package com.kira.harmoid.hdc

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** HDC file send shares the checked chunk receiver used by HAP installation. */
class HdcFileHandler(private val daemon: HdcDaemon) {
    private data class State(val path: File, val transfer: IncomingTransfer? = null)
    private val states = ConcurrentHashMap<Int, State>()

    private fun cacheDir(): File = checkNotNull(daemon.getApplicationContext()).cacheDir

    fun handleFileInit(data: ByteArray, session: HdcSession) {
        try {
            val command = String(data, Charsets.UTF_8).trim()
            val tokens = Regex("\"[^\"]*\"|'[^']*'|\\S+").findAll(command).map { it.value.trim('"', '\'') }.toList()
            val destination = tokens.lastOrNull() ?: throw IllegalArgumentException("Missing HDC destination")
            val path = HdcPaths.resolve(cacheDir(), destination)
            states.put(session.channelId, State(path))?.transfer?.abort()
        } catch (e: Exception) {
            fail(session, e)
        }
    }

    fun handleFileCheck(data: ByteArray, session: HdcSession) {
        var transfer: IncomingTransfer? = null
        try {
            val config = TransferConfig.fromBytes(data)
            require(config.fileSize >= 0) { "Invalid transfer size" }
            require(config.compressType in 0..1) { "Unsupported HDC compression" }
            val previous = states.remove(session.channelId)
            previous?.transfer?.abort()
            val rawPath = config.path.ifBlank { "/data/local/tmp/" }
            val destination = if (config.path.isBlank() && previous != null) previous.path
                else HdcPaths.resolve(cacheDir(), rawPath)
            // Existing directories and a trailing slash select directory mode;
            // an explicit filename is not incorrectly treated as a directory.
            val root = File(cacheDir(), "hdc").canonicalFile
            val directoryHint = config.path.endsWith('/') || (config.path.isBlank() && previous == null)
            val file = if (destination.isDirectory || directoryHint || destination == root) {
                HdcPaths.child(destination, config.optionalName)
            } else destination
            check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Cannot create transfer directory" }
            require(config.fileSize <= cacheDir().usableSpace) { "Not enough storage for file transfer" }
            transfer = IncomingTransfer(file, config.fileSize)
            states[session.channelId] = State(file, transfer)
            session.sendPacket(CMD_FILE_BEGIN, CMD_FILE_BEGIN_FLAGS)
            if (transfer.complete) complete(session)
        } catch (e: Exception) {
            transfer?.abort()
            fail(session, e)
        }
    }

    fun handleFileData(data: ByteArray, session: HdcSession) {
        val transfer = states[session.channelId]?.transfer ?: return
        try {
            transfer.accept(data)
            if (transfer.complete) complete(session)
        } catch (e: Exception) {
            fail(session, e)
        }
    }

    fun handleFileFinish(data: ByteArray, session: HdcSession) {
        val transfer = states[session.channelId]?.transfer
        if (transfer != null && !transfer.complete) {
            fail(session, IllegalArgumentException("Incomplete file: ${transfer.received}/${transfer.expectedSize}"))
        } else complete(session)
    }

    private fun complete(session: HdcSession) {
        states.remove(session.channelId)?.transfer?.close()
        session.sendPacket(CMD_FILE_FINISH, byteArrayOf(0))
        session.sendPacket(CMD_KERNEL_CHANNEL_CLOSE, byteArrayOf(1))
        session.resetChannel()
    }

    private fun fail(session: HdcSession, cause: Exception) {
        states.remove(session.channelId)?.transfer?.let {
            try { it.abort() } catch (e: Exception) { daemon.log("Transfer cleanup failed: ${e.message}") }
        }
        daemon.log("File transfer failed: ${cause.message}")
        session.sendPacket(CMD_KERNEL_ECHO_RAW, "[Fail] ${cause.message}".toByteArray())
        session.sendPacket(CMD_KERNEL_CHANNEL_CLOSE, byteArrayOf(1))
        session.resetChannel()
    }

    fun close() {
        for (key in states.keys) {
            val transfer = states.remove(key)?.transfer ?: continue
            try { transfer.abort() } catch (e: Exception) { daemon.log("Transfer cleanup failed: ${e.message}") }
        }
    }
}
