package com.kira.harmoid.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.URLDecoder

/**
 * Harmoid media bridge — HAP audio playback ⇄ Android media controls.
 *
 * A music-player HAP uses `@ohos.multimedia.media` (AVPlayer), which the
 * ArkUI-X plugin maps onto a bare `MediaPlayer`.  A bare MediaPlayer is
 * invisible to Android: no lock-screen controls, no Bluetooth/Auto/Wear
 * surface, no assistant.  This bridge publishes the HAP's playback as a
 * real `MediaSession` (plus a MediaStyle notification) and routes the
 * resulting control events **back into the HAP's own player**, so the
 * ArkTS side observes them through its normal state-change callbacks.
 *
 * The bridge lives in the same `:hapN` process as the HAP, so all calls
 * are in-process.
 */
object HarmoidMediaBridge {

    private const val TAG = "Harmoid.Media"
    private const val CHANNEL_ID = "harmoid_media"
    private const val NOTIFICATION_ID = 0x4D4544 // "MED"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var appContext: Context? = null
    @Volatile private var session: MediaSession? = null
    @Volatile private var audioFocusRequest: AudioFocusRequest? = null

    /** Player id currently owning the media session. */
    @Volatile private var activePlayerId: Long = -1L
    @Volatile private var title: String = "HAP media"
    @Volatile private var artist: String = "Harmoid"
    @Volatile private var durationMs: Long = 0L
    @Volatile private var positionMs: Long = 0L
    @Volatile private var playing: Boolean = false

    // ------------------------------------------------------------------
    // PlayerPlugin → bridge notifications
    // ------------------------------------------------------------------

    /** A new player was created; remember it as the active one. */
    @JvmStatic
    fun onPlayerCreated(context: Context?, playerId: Long) {
        if (context != null && appContext == null) appContext = context.applicationContext
        activePlayerId = playerId
        Log.i(TAG, "player created: id=$playerId")
    }

    /** Data source (url / fd path) was set — used as the track title. */
    @JvmStatic
    fun onSourceChanged(playerId: Long, url: String?) {
        activePlayerId = playerId
        if (!url.isNullOrBlank()) {
            title = runCatching { URLDecoder.decode(url.substringAfterLast('/'), "UTF-8") }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/')
        }
        Log.i(TAG, "source changed: id=$playerId title=$title")
    }

    @JvmStatic
    fun onPrepared(playerId: Long, durationMs: Long) {
        activePlayerId = playerId
        this.durationMs = durationMs.coerceAtLeast(0)
        publish()
    }

    @JvmStatic
    fun onPlay(playerId: Long) {
        activePlayerId = playerId
        playing = true
        requestAudioFocus()
        publish()
    }

    @JvmStatic
    fun onPause(playerId: Long) {
        activePlayerId = playerId
        playing = false
        publish()
    }

    @JvmStatic
    fun onStop(playerId: Long) {
        playing = false
        positionMs = 0L
        abandonAudioFocus()
        publish()
    }

    @JvmStatic
    fun onReleased(playerId: Long) {
        if (playerId == activePlayerId) {
            playing = false
            positionMs = 0L
            durationMs = 0L
            abandonAudioFocus()
            publish()
        }
    }

    /** Periodic position ticker (PlayerPlugin.runTimeUpdate). */
    @JvmStatic
    fun onPositionChanged(playerId: Long, positionMs: Long) {
        if (playerId != activePlayerId) return
        this.positionMs = positionMs.coerceAtLeast(0)
        publishStateOnly()
    }

    // ------------------------------------------------------------------
    // Android media session
    // ------------------------------------------------------------------

    private fun ensureSession(): MediaSession? {
        val ctx = appContext ?: return null
        session?.let { return it }
        val s = MediaSession(ctx, "HarmoidMedia").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    Log.i(TAG, "system control: play")
                    dispatch { it.play(activePlayerId) }
                }

                override fun onPause() {
                    Log.i(TAG, "system control: pause")
                    dispatch { it.pause(activePlayerId) }
                }

                override fun onStop() {
                    Log.i(TAG, "system control: stop")
                    dispatch { it.stop(activePlayerId) }
                }

                override fun onSeekTo(pos: Long) {
                    Log.i(TAG, "system control: seek $pos")
                    dispatch { it.seekTo(activePlayerId, pos.toInt(), 0) }
                }

                override fun onSkipToNext() {
                    Log.i(TAG, "system control: next (no playlist — ignored)")
                }

                override fun onSkipToPrevious() {
                    Log.i(TAG, "system control: previous (no playlist — ignored)")
                }
            }, mainHandler)
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }
        session = s
        Log.i(TAG, "MediaSession created")
        return s
    }

    /** Runs a media command against the HAP's player on the main thread. */
    private fun dispatch(command: (ohos.ace.plugin.playerplugin.PlayerPlugin) -> Unit) {
        val plugin = ohos.ace.plugin.playerplugin.PlayerPlugin.getInstance()
        if (plugin == null) {
            Log.w(TAG, "no PlayerPlugin instance — control event dropped")
            return
        }
        mainHandler.post {
            try {
                command(plugin)
            } catch (t: Throwable) {
                Log.e(TAG, "media control dispatch failed", t)
            }
        }
    }

    private fun publish() {
        mainHandler.post {
            val s = ensureSession() ?: return@post
            try {
                s.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                        .build()
                )
                publishState(s)
                publishNotification(s)
            } catch (t: Throwable) {
                Log.e(TAG, "publish failed", t)
            }
        }
    }

    /** Cheap update used by the position ticker (no notification churn). */
    private fun publishStateOnly() {
        val s = session ?: return
        try {
            publishState(s)
        } catch (t: Throwable) {
            // ignore — position updates are best-effort
        }
    }

    private fun publishState(s: MediaSession) {
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, positionMs, if (playing) 1.0f else 0.0f,
                    android.os.SystemClock.elapsedRealtime())
                .build()
        )
    }

    private fun publishNotification(s: MediaSession) {
        val ctx = appContext ?: return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        ensureChannel(nm)

        val style = Notification.MediaStyle()
            .setMediaSession(s.sessionToken)
            .setShowActionsInCompactView(0)

        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(if (playing) "正在播放" else "已暂停")
            .setStyle(style)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        HapSession.launchIntent(
            ctx,
            HapSession.wantParams(
                "harmoid.source" to "media",
                "harmoid.action" to "resume",
                "harmoid.playerId" to activePlayerId,
            ),
        )?.let { intent ->
            builder.setContentIntent(
                PendingIntent.getActivity(
                    ctx, 0x4D45, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        }

        runCatching { nm.notify(NOTIFICATION_ID, builder.build()) }
            .onFailure { Log.w(TAG, "media notification failed: ${it.message}") }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "HAP 媒体播放", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "HAP 音频播放控制"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    // ------------------------------------------------------------------
    // Audio focus — makes Android treat the HAP as a real music app
    // ------------------------------------------------------------------

    private fun requestAudioFocus() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .build()
                audioFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    { }, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "audio focus request failed: ${t.message}")
        }
    }

    private fun abandonAudioFocus() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus { }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "audio focus abandon failed: ${t.message}")
        } finally {
            audioFocusRequest = null
        }
    }

    /** Drops the session + notification, e.g. when the last player is gone. */
    @JvmStatic
    fun release() {
        mainHandler.post {
            abandonAudioFocus()
            session?.let {
                it.isActive = false
                it.release()
            }
            session = null
            activePlayerId = -1L
            playing = false
            (appContext?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
            Log.i(TAG, "media bridge released")
        }
    }
}
