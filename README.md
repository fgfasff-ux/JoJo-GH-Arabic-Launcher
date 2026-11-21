# JoJo-GH-Arabic-Launcher

تطبيق أندرويد مستقل لتثبيت وإدارة تعريب لعبة **JoJo's Bizarre Adventure: Golden Hymn** (`com.wanda.jojo.gp.global`) مع **حارس الأمان وفحص التحديثات (Version Safety Guard)** والنسخ الاحتياطي التلقائي عبر Shizuku و SAF.

## الميزات الرئيسية
- **حارس الأمان وفحص التحديثات (Version Safety Guard):**
  - يتحقق تلقائياً عند فتح التطبيق وقبل التثبيت وقبل تشغيل اللعبة من تطابق `versionCode` الخاص باللعبة (`21` لإصدار `1.0.21`) ومن حجم وبصمة MD5 لملف اللغة `force/t_languageBean.bbytes` وفهرس `config/force.conf`.
  - في حال اكتشاف تحديث جديد للعبة (تحديث APK أو Hot-Update داخلي)، يقوم اللانشر فوراً بإيقاف التعريب واستعادة الملفات الأصلية الآمنة من `.jojo_gh_arabic_backup` لحماية الحساب.
- **وصول متعدد الطبقات لـ `Android/data`:** يدعم Shizuku (عبر جسر محلي مستقل على المنفذ `38922`) و SAF و DirectFS و Root.
- **التثبيت أونلاين أو من ZIP محلي:** يدعم التحديث من GitHub Releases (`manifest.json`) أو التثبيت من حزمة ZIP محلية للاختبار الفوري.
- **النسخ الاحتياطي والاستعادة بضغطة زر:** يحفظ تلقائياً كل ملف أصلي قبل استبداله في `.jojo_gh_arabic_backup`.

## البناء التلقائي عبر GitHub Actions
عند كل دفع (push) إلى فرع `main`، يقوم سير العمل `.github/workflows/build-apk.yml` ببناء ملف الـ APK تلقائياً ورفعه كـ Artifact باسم `jojo-gh-arabic-launcher-debug-apk`.
