# دهات کیوسک (DehaatKiosk)

اپ Android که فقط سایت دهات رو توی WebView تمام‌صفحه (edge-to-edge) باز می‌کنه،
با پشتیبانی کامل از دانلود و آپلود فایل.

## قبل از هر کاری

فایل `app/src/main/java/ir/dehaat/kiosk/MainActivity.kt` رو باز کن و این دو خط بالای فایل رو
با دامنه‌ی واقعی سایت دهات عوض کن:

```kotlin
private const val SITE_URL = "https://dehaat.aghey.workers.dev"
private const val SITE_HOST = "dehaat.aghey.workers.dev"
```

## قابلیت‌ها

- فول‌اسکرین edge-to-edge واقعی (نوار وضعیت و نویگیشن مخفی، مثل کیوسک)
- محدود به دامنه‌ی خودت — لینک‌های خارجی (تلگرام و غیره) با اپ مربوطه باز می‌شن
- دانلود فایل‌های معمولی (لینک مستقیم) → `DownloadManager`
- دانلود فایل‌های blob (ساخته‌شده با جاوااسکریپت، `<a download>`) → تبدیل به Base64 و ذخیره
- آپلود فایل از گالری یا دوربین (برای input type=file، مثل آپلود آواتار/پست)
- دکمه‌ی بک اندروید داخل تاریخچه‌ی وب‌ویو کار می‌کنه

## ساخت APK

هر پوشی روی برنچ `main` پوش کنی، GitHub Actions خودکار یه APK دیباگ می‌سازه.
از تب Actions مخزن، آخرین ران رو باز کن و از پایین صفحه artifact با اسم
`dehaat-kiosk-apk` رو دانلود کن.

برای اجرای دستی: تب Actions → workflow «Build APK» → دکمه‌ی Run workflow.

## نکته درباره‌ی آیکون

فعلاً از آیکون پیش‌فرض اندروید استفاده شده (`android:icon="@android:drawable/sym_def_app_icon"`)
تا بدون نیاز به فایل عکس، بیلد بگیره. هر وقت خواستی آیکون واقعی دهات رو بذاری،
فایل‌های PNG رو توی پوشه‌های `app/src/main/res/mipmap-xxxhdpi/` و مشابهش با اسم
`ic_launcher.png` بذار و در AndroidManifest.xml مقدار icon رو به `@mipmap/ic_launcher` تغییر بده.
