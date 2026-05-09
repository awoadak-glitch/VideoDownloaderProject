// --- إعدادات خاصة بك ---
const GITHUB_USERNAME = "awoadak-glitch"; 
const REPO_NAME = "VideoDownloaderProject";
const GITHUB_TOKEN = ""; // التوكن الخاص بك هنا

function sendToGitHub(videoUrl) {
    const github_api = `https://api.github.com/repos/${GITHUB_USERNAME}/${REPO_NAME}/dispatches`;
    
    fetch(github_api, {
        method: 'POST',
        headers: {
            'Authorization': `Bearer ${GITHUB_TOKEN}`,
            'Accept': 'application/vnd.github.v3+json',
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            event_type: 'download-command',
            client_payload: { url: videoUrl }
        })
    })
    .then(response => {
        if (response.status === 204) {
            showNotification("🚀 تم إرسال الطلب لـ GitHub! سيصلك الفيديو على تليجرام.");
        } else {
            showNotification("❌ فشل الطلب! تحقق من صلاحيات التوكن.");
        }
    })
    .catch(() => showNotification("❌ خطأ في الاتصال."));
}

function showNotification(msg) {
    const note = document.createElement("div");
    note.innerText = msg;
    Object.assign(note.style, {
        position: "fixed", bottom: "30px", left: "30px", zIndex: "10001",
        backgroundColor: "#1c2128", color: "#adbac7", padding: "12px 25px",
        borderRadius: "10px", border: "1px solid #444c56", fontSize: "14px",
        boxShadow: "0 10px 25px rgba(0,0,0,0.5)", fontFamily: "sans-serif"
    });
    document.body.appendChild(note);
    setTimeout(() => note.remove(), 5000);
}

function injectBtn() {
    if (document.getElementById("pro-cloud-dl")) return;
    const btn = document.createElement("button");
    btn.id = "pro-cloud-dl";
    btn.innerHTML = "📥 تحميل سحابي";
    Object.assign(btn.style, {
        position: "fixed", top: "20px", right: "20px", zIndex: "9999",
        padding: "12px 20px", backgroundColor: "#238636", color: "white",
        border: "none", borderRadius: "8px", cursor: "pointer", 
        fontWeight: "bold", fontSize: "14px", boxShadow: "0 4px 6px rgba(0,0,0,0.1)"
    });
    btn.onclick = () => sendToGitHub(window.location.href);
    document.body.appendChild(btn);
}

// فحص ذكي لوجود الفيديو
const checkExist = setInterval(() => {
    if (document.querySelector('video')) {
        injectBtn();
    }
}, 3000);
