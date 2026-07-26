package ir.dehaat.kiosk

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/**
 * صفحه‌ی اول اپ: دو آدرسِ سایتِ دهات رو نشون می‌ده و کاربر یکی رو انتخاب می‌کنه.
 * اگه کاربر تیکِ «پیش‌فرض کن» رو بزنه، دفعه‌ی بعد که اپ لانچ بشه اصلاً این صفحه دیده
 * نمی‌شه و مستقیم می‌ره سراغ همون آدرس (از طریق SharedPreferences).
 */
class SiteSelectorActivity : AppCompatActivity() {

    private var rememberChoice = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // اگه از قبل یه پیش‌فرض ذخیره شده، اصلاً UI رو نشون نده؛ مستقیم برو سراغ MainActivity
        val savedDefault = getSharedPreferences(SITE_PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_KEY_DEFAULT_SITE, null)
        if (savedDefault != null) {
            launchMain(savedDefault)
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUi()
    }

    private fun launchMain(url: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_SITE_URL, url)
        })
        finish()
    }

    private fun onSiteChosen(url: String) {
        if (rememberChoice) {
            getSharedPreferences(SITE_PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_KEY_DEFAULT_SITE, url)
                .apply()
        }
        launchMain(url)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun buildUi() {
        val root = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#1A0B2E"))
        }

        // گرادیانِ بنفشِ قطری برای پس‌زمینه
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#1A0B2E"),
                Color.parseColor("#3B1C6B"),
                Color.parseColor("#8B5CF6")
            )
        )
        root.background = gradient

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(72), dp(32), dp(48))
        }
        root.addView(
            content,
            ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.MATCH_PARENT)
        )

        val title = TextView(this).apply {
            text = "دهات"
            textSize = 40f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        content.addView(title)

        val subtitle = TextView(this).apply {
            text = "کدوم آدرس رو می‌خوای باز کنی؟"
            textSize = 15f
            setTextColor(Color.parseColor("#D8CCF0"))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(40))
        }
        content.addView(subtitle)

        content.addView(siteButton("دهات (Faggot)", "dehaat.faggott.fun", SITE_URL_FAGGOT))
        content.addView(spacer(dp(16)))
        content.addView(siteButton("دهات (Workers)", "dehaat.aghey.workers.dev", SITE_URL_WORKERS))

        content.addView(spacer(dp(32)))

        val checkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val checkBox = CheckBox(this).apply {
            setTextColor(Color.parseColor("#E6DFFA"))
            text = "این انتخاب رو برای دفعه‌های بعد پیش‌فرض کن"
            textSize = 13.5f
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E6DFFA"))
            setOnCheckedChangeListener { _, isChecked -> rememberChoice = isChecked }
        }
        checkRow.addView(checkBox)
        content.addView(checkRow)

        setContentView(root)
    }

    private fun spacer(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
    }

    private fun siteButton(label: String, domain: String, url: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            val bg = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#33FFFFFF"))
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
            background = bg
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onSiteChosen(url) }
        }

        val labelView = TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        card.addView(labelView)

        val domainView = TextView(this).apply {
            text = domain
            textSize = 12.5f
            setTextColor(Color.parseColor("#C9BCEA"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(domainView)

        return card
    }
}
