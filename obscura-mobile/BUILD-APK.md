# كيف تحصل على ملف الـ APK (مجاني 100٪)

لديك طريقتان، كلتاهما مجانية تمامًا. الطريقة (أ) هي الأسهل — لا تحتاج تثبيت
أي شيء على جهازك، وتُنفَّذ حتى من متصفح هاتفك.

## (أ) البناء التلقائي المجاني عبر GitHub — خطوة واحدة تدوم للأبد

GitHub Actions يُنفّذ بناء تطبيقات أندرويد **للمستودعات العامة مجانًا بلا حدود**.
مطلوب منك **مرة واحدة فقط** إضافة ملف workflow إلى المستودع (توكني الآلي لا يملك
صلاحية الكتابة على ملفات `.github/workflows`، لذلك هذه الخطوة بيدك أنت:

1. افتح هذا الرابط في المتصفح (مجهز لك، يفتح نموذج «ملف جديد» على الفرع الصحيح):

   ```
   https://github.com/mrsalimeki/OBS/new/arena%2F01a0dd4d-obs/.github/workflows/obscura-mobile-apk.yml
   ```

   > إن لم يفتح النموذج: من صفحة المستودع اختر الفرع `arena/01a0dd4d-obs` ←
   > Add file ← Create new file ← في خانة الاسم الصق
   > `.github/workflows/obscura-mobile-apk.yml`

2. الصق في صندوق الملف **كل نص البلوك أدناه** (كما هو):

   ```yaml
   name: Obscura Mobile APK

   on:
     push:
       branches: [main, "arena/**"]
       paths:
         - "obscura-mobile/**"
         - ".github/workflows/obscura-mobile-apk.yml"
     workflow_dispatch:

   jobs:
     build:
       runs-on: ubuntu-latest
       steps:
         - name: Checkout
           uses: actions/checkout@v4

         - name: Set up JDK 17
           uses: actions/setup-java@v4
           with:
             distribution: temurin
             java-version: "17"

         - name: Set up Android SDK
           uses: android-actions/setup-android@v3
           with:
             packages:
               - platform-tools
               - platforms;android-34
               - build-tools;34.0.0

         - name: Install Gradle
           run: |
             curl -fsSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.7-bin.zip
             unzip -q /tmp/gradle.zip -d /opt
             echo "/opt/gradle-8.7/bin" >> "$GITHUB_PATH"

         - name: Build debug APK
           working-directory: obscura-mobile
           run: gradle assembleDebug --no-daemon --stacktrace

         - name: Upload APK as artifact
           uses: actions/upload-artifact@v4
           with:
             name: obscura-mobile-apk
             path: obscura-mobile/app/build/outputs/apk/debug/*.apk
             if-no-files-found: error

         - name: Publish GitHub Release with APK
           env:
             GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
           run: |
             APK="$(ls obscura-mobile/app/build/outputs/apk/debug/*.apk | head -n 1)"
             cp "$APK" obscura-mobile-v1.0.apk
             gh release create "obscura-mobile-v1.0" \
               --title "Obscura Mobile v1.0 (Android APK)" \
               --notes "Obscura Mobile v1.0 - free Android companion for the Obscura CDP server." \
               --target "$(git rev-parse HEAD)" \
               ./obscura-mobile-v1.0.apk \
               || echo "::warning::Release skipped (exists); APK available as artifact."
   ```

3. أسفل الصفحة: **Commit new file** ← ثم **Commit changes**.

✅ انتهت المهمة الدائمة! من الآن وكل مرة تُحفظ فيها تعديلات داخل `obscura-mobile/`:
- يبني GitHub تطبيقك تلقائيًا (مجاني).
- يظهر ملف `obscura-mobile-v1.0.apk` في:
  **Releases** ← `obscura-mobile-v1.0` — حمّله وثبّته على هاتفك.
  (أو من صفحة Actions ← اسم التنفيذ ← Artifacts ← `obscura-mobile-apk`.)

يمكنك أيضًا تشغيل البناء يدويًا في أي وقت: صفحة Actions ← «Obscura Mobile APK» ←
Run workflow.

## (ب) البناء بنفسك على جهازك — Android Studio (مجاني)

1. ثبّت [Android Studio](https://developer.android.com/studio) (مجاني، ~1.5GB).
2. File ← Open ← اختر المجلد `obscura-mobile/` — وافق إن طلب استيراد Gradle
   (سيحمّل Gradle 8.7 وتبعياته تلقائيًا، مجانية).
3. Build ← **Build App Bundle(s) / APK(s) ← Build APK(s)**.
4. الملف الناتج: `obscura-mobile/app/build/outputs/apk/debug/app-debug.apk`.

> بعد البناء الأول، أي تغيير تقم به يُعاد بناؤه بأمر واحد:
> `./gradlew assembleDebug` (إن لم يجد `gradlew` شغّل أولًا `gradle wrapper`
> من داخل مجلد `obscura-mobile`).

## ملاحظات

- التطبيق يُبنى كنسخة debug (موقعة بمفتاح توقيعي تجريبي) — هذا طبيعي ومكافٍ
  تمامًا للاستخدام الشخصي؛ التوقيع الرسمي يصبح مطلوبًا فقط عند النشر على
  Google Play.
- إن فشل البناء في Actions، افتح صفحات التنفيذ في تبويب Actions وقراءة الأخطاء —
  أو ارجع إلى هنا وسأصلحها.
