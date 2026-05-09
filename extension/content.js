// وظيفة لإرسال الرابط للسيرفر
function sendToDownloader(url) {
    fetch('http://127.0.0.1:5000/download', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ video_url: url })
    })
    .then(response => response.json())
    .then(data => alert("✅ تم إرسال الطلب: " + data.message))
    .catch(error => alert("❌ تأكد من تشغيل سيرفر البايثون أولاً!"));
}

// إنشاء وحقن الزر
function injectButton() {
    // نتحقق إذا كان الزر موجوداً مسبقاً لمنع التكرار
    if (document.getElementById("my-pro-downloader-btn")) return;

    const btn = document.createElement("button");
    btn.id = "my-pro-downloader-btn";
    btn.innerHTML = "📥 تحميل هذا الفيديو";
    
    // تصميم الزر (CSS)
    Object.assign(btn.style, {
        position: "fixed",
        top: "20px",
        right: "20px",
        zIndex: "10000",
        padding: "10px 15px",
        backgroundColor: "#ff0000",
        color: "white",
        border: "none",
        borderRadius: "5px",
        cursor: "pointer",
        fontWeight: "bold",
        boxShadow: "0px 4px 6px rgba(0,0,0,0.2)"
    });

    btn.onclick = () => sendToDownloader(window.location.href);
    document.body.appendChild(btn);
}

// مراقبة الصفحة للتأكد من وجود فيديو
const observer = new MutationObserver(() => {
    if (document.querySelector('video')) {
        injectButton();
    }
});

observer.observe(document.body, { childList: true, subtree: true });
