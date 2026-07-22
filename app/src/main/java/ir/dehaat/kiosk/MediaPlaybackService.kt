package ir.dehaat.kiosk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.net.URL
import kotlin.concurrent.thread

// این سرویس معادلِ بومیِ همون navigator.mediaSession وبه که خودِ سایت استفاده می‌کنه.
// چون WebView اندروید از Media Session API وب پشتیبانی نمی‌کنه (و در نتیجه بدون این سرویس
// هیچ نوتیفیکیشن/کنترل‌گوشی‌ای برای موزیک ساخته نمی‌شه)، این سرویس یه MediaSessionCompat واقعی
// می‌سازه و کنترل‌هاش (پلی/پاز/قبلی/بعدی) رو از طریق MainActivity به جاوااسکریپت خودِ سایت برمی‌گردونه.
class MediaPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "dehaat_media_playback"
        const val NOTIFICATION_ID = 501

        const val ACTION_UPDATE_METADATA = "ir.dehaat.kiosk.action.UPDATE_METADATA"
        const val ACTION_UPDATE_PLAYBACK_STATE = "ir.dehaat.kiosk.action.UPDATE_PLAYBACK_STATE"
        const val ACTION_STOP = "ir.dehaat.kiosk.action.STOP"

        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ARTWORK_URL = "artwork_url"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_POSITION = "position"

        // پلی/پاز/قبلی/بعدی که کاربر از نوتیفیکیشن می‌زنه، از اینجا به بقیه‌ی اپ (MainActivity) اطلاع داده می‌شه
        var actionListener: ((String) -> Unit)? = null
    }

    private lateinit var mediaSession: MediaSessionCompat
    private var currentArtwork: Bitmap? = null
    private var isPlaying = false
    private var lastTitle = ""
    private var lastArtist = ""
    private var lastArtworkUrl = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "DehaatMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { actionListener?.invoke("play") }
                override fun onPause() { actionListener?.invoke("pause") }
                override fun onSkipToNext() { actionListener?.invoke("nexttrack") }
                override fun onSkipToPrevious() { actionListener?.invoke("previoustrack") }
                override fun onStop() { actionListener?.invoke("pause") }
                override fun onSeekTo(pos: Long) { actionListener?.invoke("seekto:$pos") }
            })
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // طبق قاعده‌ی اندروید ۱۲+، بعد از startForegroundService باید ظرف چند ثانیه startForeground
        // واقعی صدا زده بشه؛ برای اطمینان همون لحظه‌ی اول (حتی قبل از دانلود کاور/آماده شدن متادیتای کامل)
        // یه نوتیف اولیه می‌سازیم و بعداً با اطلاعات کامل‌تر آپدیتش می‌کنیم.
        if (intent?.action == ACTION_UPDATE_METADATA || intent?.action == ACTION_UPDATE_PLAYBACK_STATE) {
            postNotification()
        }
        when (intent?.action) {
            ACTION_UPDATE_METADATA -> {
                lastTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
                lastArtist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
                val artworkUrl = intent.getStringExtra(EXTRA_ARTWORK_URL) ?: ""
                if (artworkUrl != lastArtworkUrl) {
                    lastArtworkUrl = artworkUrl
                    loadArtworkAndUpdate(artworkUrl)
                } else {
                    updateMetadata()
                    postNotification()
                }
            }
            ACTION_UPDATE_PLAYBACK_STATE -> {
                // وقتی فقط موقعیت/مدت‌زمان به‌روز می‌شه (position_only)، isPlaying فعلی دست‌نخورده می‌مونه
                if (!intent.getBooleanExtra("position_only", false)) {
                    isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
                }
                val duration = intent.getLongExtra(EXTRA_DURATION, 0)
                val position = intent.getLongExtra(EXTRA_POSITION, 0)
                // نکته‌ی مهم: عمداً اینجا audio focus نمی‌گیریم. پخش واقعی صدا داخل خودِ
                // WebView (المان audio/video سایت) اتفاق می‌افته و کرومیوم خودش از قبل
                // audio focus رو نگه داشته. اگه این سرویس هم جداگانه (مخصوصاً هر بار که
                // فقط موقعیت پخش آپدیت می‌شه، یعنی تقریباً هر ثانیه) درخواست GAIN بده،
                // اندروید فوکوس رو از کرومیوم می‌گیره و AUDIOFOCUS_LOSS به کرومیوم می‌فرسته؛
                // رفتار پیش‌فرض کرومیوم با از دست دادن فوکوس، پاز خودکار مدیاست. همین باعث
                // می‌شد موزیک هر چند ثانیه یک‌بار خودش استاپ بشه. این سرویس فقط نقش
                // نوتیفیکیشن/ریموت‌کنترل رو داره، نباید صاحب audio focus باشه.
                updatePlaybackState(duration, position)
                postNotification()
                if (!isPlaying) {
                    // وقتی مکث شده، اگه اپ کامل بسته بشه سرویس هم می‌تونه بی‌سروصدا جمع بشه؛
                    // ولی نوتیف رو نگه می‌داریم تا کاربر بتونه از همونجا دوباره پلی بزنه
                }
            }
            ACTION_STOP -> {
                mediaSession.isActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            // دکمه‌های خودِ نوتیفیکیشن (پلی/پاز/قبلی/بعدی) — مستقیماً به جاوااسکریپتِ سایت پاس داده می‌شن
            "native_play" -> actionListener?.invoke("play")
            "native_pause" -> actionListener?.invoke("pause")
            "native_prev" -> actionListener?.invoke("previoustrack")
            "native_next" -> actionListener?.invoke("nexttrack")
        }
        return START_NOT_STICKY
    }

    private fun loadArtworkAndUpdate(url: String) {
        if (url.isBlank()) {
            currentArtwork = null
            updateMetadata()
            postNotification()
            return
        }
        thread {
            val bmp = try {
                val stream = URL(url).openStream()
                BitmapFactory.decodeStream(stream).also { stream.close() }
            } catch (e: Exception) {
                null
            }
            currentArtwork = bmp
            updateMetadata()
            postNotification()
        }
    }

    private fun updateMetadata() {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, lastTitle.ifBlank { "بدون‌عنوان" })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, lastArtist.ifBlank { "دهات" })
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "دهات")
        currentArtwork?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        mediaSession.setMetadata(builder.build())
    }

    private fun updatePlaybackState(duration: Long, position: Long) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, position, 1.0f)
                .build()
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID, "پخش موزیک", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "کنترل پخش آهنگ‌های دهات"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildActionIntent(action: String): PendingIntent {
        val intent = Intent(this, MediaPlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun postNotification() {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseAction = NotificationCompat.Action(
            playPauseIcon, if (isPlaying) "توقف" else "پخش",
            buildActionIntent(if (isPlaying) "native_pause" else "native_play")
        )
        val prevAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous, "قبلی", buildActionIntent("native_prev")
        )
        val nextAction = NotificationCompat.Action(
            android.R.drawable.ic_media_next, "بعدی", buildActionIntent("native_next")
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(lastTitle.ifBlank { "بدون‌عنوان" })
            .setContentText(lastArtist.ifBlank { "دهات" })
            .setLargeIcon(currentArtwork)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // همیشه به‌عنوان فورگراند سرویس نمایش داده می‌شه (چه در حال پخش، چه مکث)؛ این‌جوری هم
        // محدودیتِ ۵ثانیه‌ایِ اندروید ۱۲+ برای startForegroundService نقض نمی‌شه، هم کاربر همیشه
        // می‌تونه از همون نوتیف دوباره پلی بزنه. فقط با ACTION_STOP کامل جمع می‌شه.
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
