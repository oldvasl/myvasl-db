package ir.dehaat.kiosk

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// آدرس سایت دهات - این رو با دامنه واقعی خودت عوض کن
private const val SITE_URL = "https://YOUR-SITE-URL.example"
private const val SITE_HOST = "YOUR-SITE-URL.example"

// پسوند فایل‌هایی که تقریباً همیشه یعنی «این یه دانلوده، نه لینک به یه اپ دیگه»؛
// حتی اگه از یه هاست/CDN دیگه (غیر از SITE_HOST) سرو بشن، بازم باید داخل وب‌ویو دست‌کاری بشن
// تا setDownloadListener بگیرتشون، نه اینکه با ACTION_VIEW به یه اپ خارجی پاس داده بشن
private val downloadFileExtensions = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "txt",
    "zip", "rar", "7z", "apk",
    "png", "jpg", "jpeg", "webp", "gif", "svg",
    "mp3", "mp4", "mov", "avi", "mkv", "wav"
)

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // برای آپلود فایل (input type=file توی سایت)
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    // برای پوش نوتیفیکیشن
    private var pendingFcmToken: String? = null
    private var pendingOpenUrl: String? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* نتیجه لازم نیست هندل بشه */ }

    // دانلود معمولی‌ای که منتظر گرفتن پرمیشن WRITE_EXTERNAL_STORAGE (فقط اندروید ۹ و پایین‌تر) مونده
    private var pendingDownload: Triple<String, String?, String?>? = null

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val download = pendingDownload
            pendingDownload = null
            if (granted && download != null) {
                startRegularFileDownload(download.first, download.second, download.third)
            }
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            if (callback == null) return@registerForActivityResult

            if (result.resultCode != Activity.RESULT_OK) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }

            val data = result.data
            val results: Array<Uri>? = when {
                data?.dataString != null -> arrayOf(Uri.parse(data.dataString))
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                cameraImageUri != null -> arrayOf(cameraImageUri!!)
                else -> null
            }
            callback.onReceiveValue(results)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // فول‌اسکرین edge-to-edge واقعی
        WindowCompat.setDecorFitsSystemWindows(window, false)

        webView = WebView(this)
        setContentView(webView)

        hideSystemBars()
        setupWebView()
        injectMediaSessionPolyfill()
        setupMediaBridge()
        webView.loadUrl(SITE_URL)

        setupPushNotifications()
        handleNotificationIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    // وقتی کاربر روی نوتیف می‌زنه، اگه لینک خاصی همراهش اومده باشه همون رو باز می‌کنیم
    private fun handleNotificationIntent(intent: Intent?) {
        val url = intent?.getStringExtra("open_url")
        if (!url.isNullOrEmpty()) {
            if (::webView.isInitialized) {
                webView.loadUrl(url)
            } else {
                pendingOpenUrl = url
            }
        }
    }

    private fun setupPushNotifications() {
        // پرمیشن نمایش نوتیف (فقط اندروید ۱۳ به بالا لازمه)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // هر وقت توکن FCM آماده/تازه شد، سعی کن به صفحه‌ی وب پاسش بدی
        FcmTokenHolder.setListener { token ->
            pendingFcmToken = token
            trySendTokenToWebPage()
        }

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                task.result?.let { FcmTokenHolder.updateToken(it) }
            } else {
                Log.w("DehaatKiosk", "fcm token fetch failed", task.exception)
            }
        }
    }

    // توکن رو با صدا زدن window.onFcmToken(token) توی صفحه‌ی خودت پاس می‌دیم.
    // خود سایت (که از قبل لاگین/سشن معتبر داره) باید این تابع رو تعریف کنه و توکن
    // رو با fetch به بک‌اند خودش بفرسته تا ذخیره بشه.
    private fun trySendTokenToWebPage() {
        val token = pendingFcmToken ?: return
        if (!::webView.isInitialized) return
        val js = "if (window.onFcmToken) { window.onFcmToken(${JSONObject.quote(token)}); }"
        webView.evaluateJavascript(js, null)
    }

    // پلی‌فیلِ navigator.mediaSession: خودِ WebView اندروید از Media Session وب پشتیبانی نمی‌کنه،
    // پس بدون این کار هرچی سایت با navigator.mediaSession.metadata/playbackState/setActionHandler
    // تنظیم می‌کنه بی‌صدا نادیده گرفته می‌شه. این اسکریپت رو *قبل از هر اسکریپت دیگه‌ی صفحه* (document start)
    // تزریق می‌کنیم، چون سایت همون لحظه‌ی لود صفحه initMediaSessionHandlers() رو صدا می‌زنه.
    private fun injectMediaSessionPolyfill() {
        val script = """
            (function() {
                if (window.__dehaatMediaSessionPolyfilled) return;
                window.__dehaatMediaSessionPolyfilled = true;
                // نکته: عمداً چک نمی‌کنیم که navigator.mediaSession از قبل وجود داره یا نه.
                // WebView اندروید (Chromium) توی خیلی از نسخه‌ها خودش یه navigator.mediaSession
                // واقعی تعریف می‌کنه، ولی اون پیاده‌سازی هیچ‌وقت به نوتیفیکیشن سیستمی اندروید وصل
                // نمی‌شه (این کار فقط توی اپ کروم انجام می‌شه، نه توی WebView جاسازی‌شده). پس همیشه
                // باید مقدارش رو با نسخه‌ی خودمون (که به AndroidMediaBridge وصله) جایگزین کنیم،
                // وگرنه کنترل‌های موزیک هیچ‌وقت به نوتیفیکیشن اندروید نمی‌رسن.

                if (typeof window.MediaMetadata === 'undefined') {
                    window.MediaMetadata = function(init) {
                        init = init || {};
                        this.title = init.title || '';
                        this.artist = init.artist || '';
                        this.album = init.album || '';
                        this.artwork = init.artwork || [];
                    };
                }

                var actionHandlers = {};
                var currentMetadata = null;
                var currentPlaybackState = 'none';

                var polyfill = {
                    get metadata() { return currentMetadata; },
                    set metadata(m) {
                        currentMetadata = m;
                        try {
                            var art = (m && m.artwork && m.artwork.length) ? m.artwork[m.artwork.length - 1].src : '';
                            AndroidMediaBridge.setMetadata((m && m.title) || '', (m && m.artist) || '', art || '');
                        } catch (e) {}
                    },
                    get playbackState() { return currentPlaybackState; },
                    set playbackState(s) {
                        currentPlaybackState = s;
                        try { AndroidMediaBridge.setPlaybackState(s === 'playing'); } catch (e) {}
                    },
                    setActionHandler: function(action, handler) {
                        actionHandlers[action] = handler;
                    },
                    setPositionState: function(state) {
                        try {
                            AndroidMediaBridge.setPositionState(
                                Math.round(((state && state.duration) || 0) * 1000),
                                Math.round(((state && state.position) || 0) * 1000)
                            );
                        } catch (e) {}
                    }
                };

                Object.defineProperty(navigator, 'mediaSession', {
                    value: polyfill, writable: false, configurable: true
                });

                // این تابع رو کاتلین صدا می‌زنه وقتی کاربر روی دکمه‌های نوتیفیکیشن/گوشی بزنه
                window.__dehaatInvokeMediaAction = function(action, seekMs) {
                    var handler = actionHandlers[action];
                    if (!handler) return;
                    try {
                        if (action === 'seekto') { handler({ seekTime: (seekMs || 0) / 1000, fastSeek: false }); }
                        else { handler({}); }
                    } catch (e) {}
                };
            })();
        """.trimIndent()

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        } else {
            // فال‌بک برای WebViewهای خیلی قدیمی: تزریق دیرتر بهتر از هیچیه، هرچند initMediaSessionHandlers
            // ممکنه قبلش اجرا شده باشه و بی‌اثر بمونه
            webView.evaluateJavascript(script, null)
        }
    }

    // کنترل‌های نوتیفیکیشن/دکمه‌های گوشی (پلی/پاز/قبلی/بعدی) از سرویس موزیک به اینجا می‌رسن
    // و از همینجا به جاوااسکریپت خودِ سایت (همون action handlerهایی که با navigator.mediaSession
    // ثبت کرده) پاس داده می‌شن.
    private fun setupMediaBridge() {
        webView.addJavascriptInterface(MediaBridge(this), "AndroidMediaBridge")

        MediaPlaybackService.actionListener = { action ->
            runOnUiThread {
                if (action.startsWith("seekto:")) {
                    val ms = action.substringAfter(":").toLongOrNull() ?: 0
                    webView.evaluateJavascript("window.__dehaatInvokeMediaAction && window.__dehaatInvokeMediaAction('seekto', $ms);", null)
                } else {
                    webView.evaluateJavascript("window.__dehaatInvokeMediaAction && window.__dehaatInvokeMediaAction('$action');", null)
                }
            }
        }
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = userAgentString + " DehaatKioskApp/1.0"
        }

        // برای دانلودهای بلاب (فایل‌هایی که با جاوااسکریپت ساخته می‌شن)
        webView.addJavascriptInterface(DownloadBridge(this), "AndroidDownloader")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val host = url.host ?: ""
                // ساب‌دامین‌های همون سایت (مثل cdn.example.com یا files.example.com) هم مجازن،
                // چون خیلی از سایت‌ها فایل‌های دانلودی رو از یه ساب‌دامین جدا سرو می‌کنن، نه از دامنه‌ی اصلی
                val isSameSite = host == SITE_HOST || host.endsWith(".$SITE_HOST")
                // اگه لینک به یه پسوند فایل دانلودی معمول ختم بشه، صرف‌نظر از هاست بذار خودِ وب‌ویو
                // امتحانش کنه (و از طریق setDownloadListener بگیرتش)، چون تقریباً مطمئنیم لینک به
                // یه اپ دیگه (مثل تلگرام) مربوط نیست و باید دانلود بشه
                val looksLikeDownload = downloadFileExtensions.any {
                    url.path?.lowercase()?.endsWith(".$it") == true
                }
                return if (isSameSite || looksLikeDownload) {
                    false // بذار خود وب‌ویو لود کنه
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (e: Exception) {
                        Log.w("DehaatKiosk", "cannot open external url: $url")
                    }
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectBlobDownloadScript()
                trySendTokenToWebPage()
                pendingOpenUrl?.let {
                    webView.loadUrl(it)
                    pendingOpenUrl = null
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webViewParam: WebView,
                filePathCallbackParam: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallbackParam

                val acceptTypes = fileChooserParams.acceptTypes
                val mimeType =
                    if (acceptTypes.isNotEmpty() && acceptTypes[0].isNotBlank()) acceptTypes[0] else "*/*"

                val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = mimeType
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(
                        Intent.EXTRA_ALLOW_MULTIPLE,
                        fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    )
                }

                var cameraIntent: Intent? = null
                if (mimeType.startsWith("image/") || mimeType == "*/*") {
                    cameraImageUri = createImageUri()
                    cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                }

                val chooserIntent = Intent.createChooser(contentIntent, "انتخاب فایل")
                if (cameraIntent != null) {
                    chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                }

                return try {
                    fileChooserLauncher.launch(chooserIntent)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        // دانلود مستقیم فایل‌ها (لینک‌های http معمولی)
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            downloadRegularFile(url, contentDisposition, mimeType)
        }
    }

    private fun createImageUri(): Uri {
        val imagesDir = File(cacheDir, "images").apply { mkdirs() }
        val file = File(imagesDir, "capture_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    private fun downloadRegularFile(url: String, contentDisposition: String?, mimeType: String?) {
        // فقط اندروید ۹ و پایین‌تر (API ≤ 28) به این پرمیشن نیاز داره؛ اندروید ۱۰+ نیازی نداره
        // و اصلاً این پرمیشن رو نمی‌شناسه (خودِ DownloadManager بدون پرمیشن هم کار می‌کنه)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingDownload = Triple(url, contentDisposition, mimeType)
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        startRegularFileDownload(url, contentDisposition, mimeType)
    }

    private fun startRegularFileDownload(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setTitle(fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        } catch (e: Exception) {
            Log.e("DehaatKiosk", "download failed", e)
        }
    }

    // اسکریپتی که دانلودهای blob رو (چه با <a download> چه window.open) می‌گیره و از طریق بریج به کاتلین می‌فرسته
    private fun injectBlobDownloadScript() {
        val js = """
            (function() {
                if (window.__dehaatDownloadHooked) return;
                window.__dehaatDownloadHooked = true;

                function guessExtension(mime) {
                    var map = {
                        'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp',
                        'image/gif': 'gif', 'application/pdf': 'pdf', 'video/mp4': 'mp4',
                        'audio/mpeg': 'mp3', 'application/zip': 'zip', 'text/plain': 'txt',
                        'application/json': 'json'
                    };
                    return map[mime] || 'bin';
                }

                function blobToBase64(blobUrl, fileName) {
                    fetch(blobUrl).then(function(res) { return res.blob(); }).then(function(blob) {
                        var name = fileName;
                        if (!name) name = 'file.' + guessExtension(blob.type);
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            var base64 = reader.result.split(',')[1];
                            AndroidDownloader.saveBase64File(base64, name, blob.type || 'application/octet-stream');
                        };
                        reader.readAsDataURL(blob);
                    });
                }

                // حالت ۱: کلیک روی <a> با href بلاب (چه attribute دانلود داشته باشه چه نه)
                document.addEventListener('click', function(e) {
                    var el = e.target;
                    while (el && el.tagName !== 'A') { el = el.parentElement; }
                    if (el && el.href && el.href.indexOf('blob:') === 0) {
                        e.preventDefault();
                        var name = el.getAttribute('download') || '';
                        blobToBase64(el.href, name);
                    }
                }, true);

                // حالت ۲: window.open(blobUrl) یا window.open(blobUrl, '_blank')
                var originalOpen = window.open;
                window.open = function(url, target, features) {
                    if (typeof url === 'string' && url.indexOf('blob:') === 0) {
                        blobToBase64(url, '');
                        return null;
                    }
                    return originalOpen.call(window, url, target, features);
                };
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun onDestroy() {
        FcmTokenHolder.clearListener()
        MediaPlaybackService.actionListener = null
        webView.destroy()
        super.onDestroy()
    }
}

// بریج جاوااسکریپت که navigator.mediaSession پلی‌فیل‌شده رو به سرویسِ پخشِ موزیک (نوتیفیکیشن واقعیِ اندروید) وصل می‌کنه
class MediaBridge(private val context: Context) {

    @JavascriptInterface
    fun setMetadata(title: String, artist: String, artworkUrl: String) {
        val intent = Intent(context, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_UPDATE_METADATA
            putExtra(MediaPlaybackService.EXTRA_TITLE, title)
            putExtra(MediaPlaybackService.EXTRA_ARTIST, artist)
            putExtra(MediaPlaybackService.EXTRA_ARTWORK_URL, artworkUrl)
        }
        startMediaService(intent)
    }

    @JavascriptInterface
    fun setPlaybackState(isPlaying: Boolean) {
        val intent = Intent(context, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_UPDATE_PLAYBACK_STATE
            putExtra(MediaPlaybackService.EXTRA_IS_PLAYING, isPlaying)
        }
        startMediaService(intent)
    }

    @JavascriptInterface
    fun setPositionState(durationMs: Long, positionMs: Long) {
        val intent = Intent(context, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_UPDATE_PLAYBACK_STATE
            // موقعیت رو هم با همون پیام وضعیتِ پخش می‌فرستیم؛ isPlaying رو عمداً ست نمی‌کنیم
            // چون آخرین مقدارش توی خودِ سرویس نگه داشته می‌شه و اینجا فقط موقعیت/مدت رو به‌روز می‌کنیم
            putExtra(MediaPlaybackService.EXTRA_DURATION, durationMs)
            putExtra(MediaPlaybackService.EXTRA_POSITION, positionMs)
            putExtra("position_only", true)
        }
        startMediaService(intent)
    }

    private fun startMediaService(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.w("DehaatKiosk", "cannot start media service", e)
        }
    }
}

// بریج جاوااسکریپت برای ذخیره فایل‌های بلاب (بیس۶۴)
class DownloadBridge(private val context: Context) {

    @JavascriptInterface
    fun saveBase64File(base64Data: String, fileName: String, mimeType: String) {
        try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            var file = File(downloadsDir, fileName)
            var counter = 1
            val baseName = fileName.substringBeforeLast('.', fileName)
            val ext = fileName.substringAfterLast('.', "")
            while (file.exists()) {
                val newName = if (ext.isNotEmpty()) "$baseName($counter).$ext" else "$baseName($counter)"
                file = File(downloadsDir, newName)
                counter++
            }

            FileOutputStream(file).use { it.write(bytes) }

            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            intent.data = Uri.fromFile(file)
            context.sendBroadcast(intent)

        } catch (e: Exception) {
            Log.e("DehaatKiosk", "saveBase64File failed", e)
        }
    }
}
