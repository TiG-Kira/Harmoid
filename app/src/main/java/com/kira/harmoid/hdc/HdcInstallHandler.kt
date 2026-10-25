package com.kira.harmoid.hdc

import android.content.Intent
import com.kira.harmoid.hap.HapInstaller
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Receives a complete HAP before handing it to the transactional installer. */
class HdcInstallHandler(private val daemon: HdcDaemon) {
    private val states = ConcurrentHashMap<Int, IncomingTransfer>()

    fun handleAppCheck(data: ByteArray, session: HdcSession) {
        var temporary: File? = null
        try {
            val config = TransferConfig.fromBytes(data)
            require(config.fileSize > 0) { "Empty or invalid HAP transfer size" }
            require(config.compressType in 0..1) { "Unsupported HDC compression: ${config.compressType}" }
            val context = checkNotNull(daemon.getApplicationContext()) { "No application context" }
            require(config.fileSize <= context.cacheDir.usableSpace) { "Not enough storage for HAP transfer" }
            // optionalName is host-controlled and must never become a local path.
            val file = File.createTempFile("hdc-install-", ".hap", context.cacheDir)
            temporary = file
            val incoming = IncomingTransfer(file, config.fileSize)
            states.put(session.channelId, incoming)?.abort()
            daemon.log("Receiving HAP: ${config.fileSize} bytes, channel=${session.channelId}")
            session.sendPacket(CMD_APP_BEGIN, ByteArray(0))
        } catch (e: Exception) {
            temporary?.delete()
            fail(session, e)
        }
    }

    fun handleAppBegin(session: HdcSession) {
        daemon.log("CMD_APP_BEGIN: channel=${session.channelId}")
    }

    fun handleAppData(data: ByteArray, session: HdcSession) {
        val transfer = states[session.channelId] ?: return
        try {
            transfer.accept(data)
            if (transfer.complete && states.remove(session.channelId, transfer)) {
                transfer.close()
                install(transfer.file, session)
            }
        } catch (e: Exception) {
            fail(session, e)
        }
    }

    fun handleAppFinish(session: HdcSession) {
        // Some clients acknowledge our finish result with APP_FINISH. Do not
        // send a second failure after a successful automatic installation.
        val transfer = states.remove(session.channelId) ?: return
        try {
            require(transfer.complete) { "Incomplete HAP: ${transfer.received}/${transfer.expectedSize} bytes" }
            transfer.close()
            install(transfer.file, session)
        } catch (e: Exception) {
            transfer.abort()
            fail(session, e)
        }
    }

    private fun install(file: File, session: HdcSession) {
        try {
            val context = checkNotNull(daemon.getApplicationContext()) { "No application context" }
            val installed = HapInstaller(context).install(file.path)
            context.sendBroadcast(Intent("com.kira.harmoid.HAP_INSTALLED").setPackage(context.packageName))
            session.sendResult(true, "[Success] installed ${installed.bundleName}/${installed.mainAbility}")
        } catch (e: Exception) {
            daemon.log("Install failed: ${e.message}")
            session.sendResult(false, "[Fail] ${e.message}")
        } finally {
            file.delete()
        }
    }

    fun handleAppUninstall(data: ByteArray, session: HdcSession) {
        val payload = String(data, Charsets.UTF_8).trim()
        val packageName = payload.split(Regex("\\s+")).lastOrNull { !it.startsWith("-") }.orEmpty()
        try {
            val context = checkNotNull(daemon.getApplicationContext()) { "No application context" }
            HapInstaller(context).uninstall(packageName)
            context.sendBroadcast(Intent("com.kira.harmoid.HAP_INSTALLED").setPackage(context.packageName))
            session.sendUninstallResult("[Success] uninstalled $packageName", true)
        } catch (e: Exception) {
            daemon.log("Uninstall failed: ${e.message}")
            session.sendUninstallResult("[Fail] ${e.message}", false)
        }
    }

    private fun fail(session: HdcSession, cause: Exception) {
        states.remove(session.channelId)?.let { transfer ->
            try { transfer.abort() } catch (e: Exception) { daemon.log("Transfer cleanup failed: ${e.message}") }
        }
        daemon.log("HAP transfer failed: ${cause.message}")
        session.sendResult(false, "[Fail] ${cause.message}")
    }

    fun close() {
        for (key in states.keys) {
            val transfer = states.remove(key) ?: continue
            try { transfer.abort() } catch (e: Exception) { daemon.log("Transfer cleanup failed: ${e.message}") }
        }
    }
}
