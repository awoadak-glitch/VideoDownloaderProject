from flask import Flask, request, jsonify
from flask_cors import CORS
import yt_dlp
import os

app = Flask(__name__)
CORS(app)  # لكي يقبل السيرفر الطلبات القادمة من المتصفح

# مسار مجلد التحميلات (سيقوم بإنشاء مجلد Downloads إذا لم يوجد)
DOWNLOAD_PATH = os.path.join(os.getcwd(), 'downloads')
if not os.path.exists(DOWNLOAD_PATH):
    os.makedirs(DOWNLOAD_PATH)

@app.route('/download', methods=['POST'])
def download_video():
    data = request.json
    video_url = data.get('video_url')
    
    if not video_url:
        return jsonify({"message": "رابط الفيديو مفقود"}), 400

    # إعدادات yt-dlp
    ydl_opts = {
        'format': 'best', # يختار أفضل جودة فيديو مدمجة مع الصوت
        'outtmpl': f'{DOWNLOAD_PATH}/%(title)s.%(ext)s', # مسار واسم الملف
        'noplaylist': True,
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([video_url])
        return jsonify({"message": "تم التحميل بنجاح في مجلد downloads"}), 200
    except Exception as e:
        return jsonify({"message": f"حدث خطأ: {str(e)}"}), 500

if __name__ == '__main__':
    print(f"Server is running on http://127.0.0.1:5000")
    app.run(port=5000, debug=True)
