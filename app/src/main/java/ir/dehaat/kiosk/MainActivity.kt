package ir.dehaat.kiosk

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileOutputStream

// آدرس سایت دهات - این رو با دامنه واقعی خودت عوض کن
private const val SITE_URL = "https://YOUR-SITE-URL.example"
private const val SITE_HOST = "YOUR-SITE-URL.example"

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // برای آپلود فایل (input type=file توی سایت)
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

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
        webView.loadUrl(SITE_URL)

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
                return if (url.host == SITE_HOST) {
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

    // اسکریپتی که کلیک روی لینک‌های دانلود بلاب رو می‌گیره و از طریق بریج به کاتلین می‌فرسته
    private fun injectBlobDownloadScript() {
        val js = """
            (function() {
                if (window.__dehaatDownloadHooked) return;
                window.__dehaatDownloadHooked = true;

                function blobToBase64(blobUrl, fileName) {
                    fetch(blobUrl).then(function(res) { return res.blob(); }).then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            var base64 = reader.result.split(',')[1];
                            AndroidDownloader.saveBase64File(base64, fileName, blob.type || 'application/octet-stream');
                        };
                        reader.readAsDataURL(blob);
                    });
                }

                document.addEventListener('click', function(e) {
                    var el = e.target;
                    while (el && el.tagName !== 'A') { el = el.parentElement; }
                    if (el && el.hasAttribute('download') && el.href && el.href.indexOf('blob:') === 0) {
                        e.preventDefault();
                        var name = el.getAttribute('download') || 'file';
                        blobToBase64(el.href, name);
                    }
                }, true);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
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
