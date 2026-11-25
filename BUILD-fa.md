# ساخت APK (روی GitHub، بدون نصب چیزی روی کامپیوتر)

1. یک مخزن **خصوصی** در GitHub بسازید و همه‌ی فایل‌های این پوشه را آپلود کنید.
2. **کلید امضا** (فقط یک‌بار؛ برای آپدیت برنامه حیاتی است):
   روی هر کامپیوتر/گوشی دارای Java این دستور را بزنید و فایل `release.jks` را **جایی امن نگه دارید**:
   `keytool -genkeypair -v -keystore release.jks -alias vodi -keyalg RSA -keysize 2048 -validity 10000`
   سپس در GitHub: Settings ← Secrets and variables ← Actions ← این‌ها را اضافه کنید:
   - `KEYSTORE_B64` : خروجی `base64 -w0 release.jks`
   - `KEYSTORE_PASSWORD` ، `KEY_ALIAS` (مثلاً vodi) ، `KEY_PASSWORD`
3. تب **Actions** ← «Build APK» ← **Run workflow** ← نام نسخه (مثلاً 1.0.0) و کد نسخه (عدد؛ هر بار بزرگ‌تر).
4. بعد از ~۱۰–۱۵ دقیقه، فایل `VodiPanel-apk` را از پایین صفحه‌ی اجرا دانلود کنید.
5. در پنل مدیریت سایت ← «نسخه‌های برنامه» همان APK را آپلود کنید. تمام.

## نکات
- آدرس پنل داخل `app/build.gradle.kts` (`PANEL_URL`) روی `https://apk.vodisnapp.ir` تنظیم است.
- رنگ‌ها، لوگو، بنر، اعلان، لینک تلگرام/پشتیبانی و لیست سرورها همه از پنل می‌آیند؛ برای تغییرشان APK جدید لازم نیست.
- هسته‌ی VPN (sing-box / libbox) در هر بیلد از سورس ساخته می‌شود. اگر مرحله‌ی «Build libbox.aar» خطا داد، لاگ آن را بفرستید.
- مجوز: sing-box تحت GPLv3 است؛ پخش APK شامل آن یعنی باید سورس برنامه را هم در صورت درخواست در اختیار کاربران بگذارید.
