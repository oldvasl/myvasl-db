package ir.dehaat.kiosk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

private const val CHANNEL_ID = "dehaat_notifications"

class DehaatFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // توکن جدید رو نگه می‌داریم و به اکتیویتی (اگه باز باشه) اطلاع می‌دیم
        FcmTokenHolder.updateToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title ?: message.data["title"] ?: "دهات"
        val body = message.notification?.body ?: message.data["body"] ?: ""
        // اگه سرور یه لینک داخل data بفرسته (مثلاً url به یه پست خاص)، وقتی کاربر
        // روی نوتیف بزنه همون صفحه توی اپ باز می‌شه
        val link = message.data["url"]

        showNotification(title, body, link)
    }

    private fun showNotification(title: String, body: String, link: String?) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "اعلان‌های دهات", NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!link.isNullOrEmpty()) putExtra("open_url", link)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}

/**
 * پل ساده بین سرویس FCM (که ممکنه وقتی اپ بسته‌ست اجرا بشه) و MainActivity
 * (که فقط وقتی باز و زنده‌ست می‌تونه توکن رو به صفحه‌ی وب پاس بده).
 */
object FcmTokenHolder {
    var latestToken: String? = null
        private set

    private var onTokenReady: ((String) -> Unit)? = null

    fun updateToken(token: String) {
        latestToken = token
        onTokenReady?.invoke(token)
    }

    fun setListener(listener: (String) -> Unit) {
        onTokenReady = listener
        latestToken?.let { listener(it) }
    }

    fun clearListener() {
        onTokenReady = null
    }
}
