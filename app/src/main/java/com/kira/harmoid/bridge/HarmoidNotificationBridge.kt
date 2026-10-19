package com.kira.harmoid.bridge

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import ohos.ace.plugin.notificationmanager.NotificationRequest

/**
 * Harmoid extension layer for `libnotificationmanager.so` /
 * `ohos.ace.plugin.notificationmanager.NotificationPlugin`.
 *
 * The stock ArkUI-X plugin posts a notification with **no** content intent,
 * so tapping it does nothing at all — the HAP never learns about the tap.
 * This object restores the OHOS `wantAgent` semantics:
 *
 *  * tap      -> HAP ability is brought to the foreground and receives
 *                `onNewWant()` / the initial `want` with the click payload
 *  * dismiss  -> same want pipeline, with `harmoid.action = "dismiss"`
 *  * live view-> OHOS 实况窗 (progress / ongoing) is re-shaped into an
 *                Android 16 `Notification.ProgressStyle` "Live Update"
 */
object HarmoidNotificationBridge {

    private const val TAG = "Harmoid.Notif"

    const val ACTION_CLICK = "click"
    const val ACTION_DISMISS = "dismiss"
    const val ACTION_ACTION = "action"

    /** Extra keys handed to the ArkTS side via WantParams. */
    private const val KEY_SOURCE = "harmoid.source"
    private const val KEY_ACTION = "harmoid.action"
    private const val KEY_NOTIFICATION_ID = "harmoid.notificationId"
    private const val KEY_NOTIFICATION_LABEL = "harmoid.notificationLabel"
    private const val KEY_NOTIFICATION_TITLE = "harmoid.notificationTitle"
    private const val KEY_BUTTON_INDEX = "harmoid.buttonIndex"

    private const val SOURCE_NOTIFICATION = "notification"

    /**
     * Content intent for a HAP notification.
     *
     * Returns null when this process hosts no HAP (nothing to route to);
     * the caller then simply leaves the notification without an intent,
     * which is the previous behaviour.
     */
    @JvmStatic
    fun contentIntent(context: Context, request: NotificationRequest): PendingIntent? {
        val intent = HapSession.launchIntent(context, wantFor(request, ACTION_CLICK))
            ?: return null
        return PendingIntent.getActivity(
            context,
            requestCodeFor(request),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Delete (swipe-away) intent, so the HAP can observe dismissal. */
    @JvmStatic
    fun deleteIntent(context: Context, request: NotificationRequest): PendingIntent? {
        val intent = HapSession.launchIntent(context, wantFor(request, ACTION_DISMISS))
            ?: return null
        return PendingIntent.getActivity(
            context,
            requestCodeFor(request) + 100_000,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Applies a "Live Update" look to [builder].
     *
     * OHOS 实况窗 notifications arrive here either as an explicit
     * `liveView` request or as an ongoing request carrying a progress
     * range.  On Android 16+ (API 36) we map that onto
     * `Notification.ProgressStyle`, which is what the platform promotes to
     * the status-bar chip / Live Update surface.  On older platforms we
     * fall back to the classic `setProgress()`.
     *
     * @return true when a live-update style was applied
     */
    @JvmStatic
    fun applyLiveUpdate(builder: Notification.Builder, request: NotificationRequest): Boolean {
        val max = request.progressMax ?: 0
        val value = request.progressValue ?: 0
        val live = request.liveView || (request.isOngoing && max > 0)
        if (!live) {
            return false
        }

        builder.setOngoing(true)
        builder.setCategory(Notification.CATEGORY_PROGRESS)
        builder.setOnlyAlertOnce(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val style = Notification.ProgressStyle()
            if (max > 0) {
                // ProgressStyle derives the range from the segments, so emit
                // a single full-length segment and place the playhead on it.
                style.addProgressSegment(Notification.ProgressStyle.Segment(max))
                style.setProgress(value.coerceIn(0, max))
                style.setProgressIndeterminate(false)
                style.setStyledByProgress(true)
            } else {
                style.setProgressIndeterminate(true)
                style.setStyledByProgress(true)
            }
            // Title / text stay on the builder (ProgressStyle has no own
            // content-title setters before they were finalised).
            builder.setStyle(style)
        } else {
            builder.setProgress(max, value, max <= 0)
        }

        Log.i(TAG, "live update applied (max=$max value=$value explicit=${request.liveView})")
        return true
    }

    /** True when this request should be treated as a live/ongoing update. */
    @JvmStatic
    fun isLiveUpdate(request: NotificationRequest): Boolean {
        return request.liveView || (request.isOngoing && (request.progressMax ?: 0) > 0)
    }

    private fun wantFor(request: NotificationRequest, action: String): String {
        return HapSession.wantParams(
            KEY_SOURCE to SOURCE_NOTIFICATION,
            KEY_ACTION to action,
            KEY_NOTIFICATION_ID to request.id,
            KEY_NOTIFICATION_LABEL to (request.label ?: ""),
            KEY_NOTIFICATION_TITLE to (request.content?.content?.title ?: ""),
        )
    }

    private fun requestCodeFor(request: NotificationRequest): Int {
        // Stable per-notification request code so FLAG_UPDATE_CURRENT keeps
        // one live PendingIntent per HAP notification id.
        val id = request.id ?: 0
        val label = request.label
        return if (label.isNullOrEmpty()) id else (id * 31 + label.hashCode())
    }
}
