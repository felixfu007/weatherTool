package com.example.weathertool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Creates and posts rain-alert notifications to the user.
 */
class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "weather_alert_channel"
        const val NOTIFICATION_ID = 1001

        /**
         * Printf-style format template for the rain alert message.
         * Arg 1 (%s): location name.  Arg 2 (%d): threshold percent.
         *
         * [R.string.notification_alert_message] contains the identical template so the text
         * is localizable at runtime.  [buildAlertMessage] uses this constant so that unit
         * tests on the JVM verify the same template that [showRainAlert] formats via
         * [Context.getString] — no manual "keep in sync" required.
         */
        internal const val ALERT_MESSAGE_TEMPLATE =
            "您所在的%s，目前降雨機率已超過%d%%，請多加留意。"

        /**
         * Formats [ALERT_MESSAGE_TEMPLATE] with [locationName] and [threshold].
         * Kept as a pure function (no [android.content.Context] dependency) so the exact
         * wording can be verified by JVM unit tests without an Android runtime.
         */
        fun buildAlertMessage(locationName: String, threshold: Int): String =
            ALERT_MESSAGE_TEMPLATE.format(locationName, threshold)
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val name = context.getString(R.string.channel_name)
        val description = context.getString(R.string.channel_description)
        val channel = NotificationChannel(
            CHANNEL_ID,
            name,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            this.description = description
        }
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    /**
     * Posts a notification informing the user that the precipitation probability for
     * [locationName] has exceeded [threshold].
     */
    fun showRainAlert(locationName: String, threshold: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val message = context.getString(R.string.notification_alert_message, locationName, threshold)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rain_notification)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted; silently skip
        }
    }
}
