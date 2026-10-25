package com.kira.harmoid.hdc

import android.content.Context
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** TCP HDC daemon; each server generation owns its listener and sessions. */
class HdcDaemon(private val appContext: Context, val port: Int = DEFAULT_PORT,
    private val listenAddress: String = "0.0.0.0") {
    private val tag = "Harmoid.HDC.Daemon"
    private val running = AtomicBoolean(false)
    private val sessions = CopyOnWriteArrayList<HdcSession>()
    private val channelIdGen = AtomicInteger(1)
    private val sessionIdGen = AtomicInteger(1)
    @Volatile private var serverSocket: ServerSocket? = null
    val version = "3.2.0c-HOA"

    fun log(msg: String) { Log.i(tag, msg) }
    fun nextChannelId(): Int = channelIdGen.getAndIncrement()
    fun nextSessionId(): Int = sessionIdGen.getAndIncrement()
    fun removeSession(session: HdcSession) { sessions.remove(session) }
    fun getApplicationContext(): Context? = appContext
    fun isRunning(): Boolean = running.get()
    fun sessionCount(): Int = sessions.size

    @Synchronized fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val listener = ServerSocket()
            serverSocket = listener // Publish before binding, so stop() can always close it.
            Thread({ serve(listener) }, "hdc-daemon").start()
        } catch (e: Exception) {
            running.set(false)
            Log.e(tag, "Cannot start HDC daemon", e)
        }
    }

    private fun serve(listener: ServerSocket) {
        try {
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(InetAddress.getByName(listenAddress), port), 4)
            log("HDC daemon listening on port $port")
            while (running.get() && serverSocket === listener) {
                val client = listener.accept()
                val session = synchronized(this) {
                    if (!running.get() || serverSocket !== listener) {
                        client.close()
                        null
                    } else HdcSession(client, this).also { sessions.add(it) }
                } ?: break
                Thread(session, "hdc-session-${session.hashCode()}").start()
            }
        } catch (e: Exception) {
            if (running.get() && serverSocket === listener) Log.e(tag, "HDC listener failed", e)
        } finally {
            synchronized(this) {
                // A stopped listener must not tear down a newer restart.
                if (serverSocket === listener) stop()
            }
            try { listener.close() } catch (_: Exception) { }
        }
    }

    @Synchronized fun stop() {
        running.set(false)
        val listener = serverSocket
        serverSocket = null
        try { listener?.close() } catch (_: Exception) { }
        sessions.forEach { it.close() }
        sessions.clear()
    }
}
