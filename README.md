# VideoDownloaderProject
# نظام تحميل الفيديوهات الشخصي

## التشغيل:
1. **السيرفر:**
   - ادخل مجلد `server`.
   - ثبت المكتبات: `pip install -r requirements.txt`.
   - شغل السيرفر: `python app.py`.

2. **الإضافة:**
   - افتح كروم -> `chrome://extensions/`.
   - فعل `Developer Mode`.
   - اضغط `Load unpacked` واختر مجلد `extension`.

3. **الاستخدام:**
   - افتح أي موقع فيديو (يوتيوب، أنمي، إلخ).
   - سيظهر زر أحمر "تحميل" في الأعلى.
   - اضغط عليه وسيتم التحميل داخل مجلد `server/downloads`.
