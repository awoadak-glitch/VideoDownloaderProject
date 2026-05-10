package com.awr.downloaderstudio
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.widget.Toast
class Downloader(private val context:Context){
 fun start(item:MediaItem, audioOnly:Boolean=false):DownloadTask?{
  if(MediaDetector.isStream(item.url)){Toast.makeText(context,"HLS/DASH يحتاج FFmpeg محلي لاحقًا",Toast.LENGTH_LONG).show();return null}
  val filename=clean(item.title)+"-"+item.quality+"-"+System.currentTimeMillis()+if(audioOnly||item.type=="AUDIO")".m4a" else ext(item.url)
  val folder="AWR Studio"
  val req=DownloadManager.Request(Uri.parse(item.url)).setTitle(filename).setDescription("${item.type} ${item.quality}").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,"$folder/$filename")
  val cookie=CookieManager.getInstance().getCookie(item.url); if(!cookie.isNullOrBlank())req.addRequestHeader("Cookie",cookie)
  req.addRequestHeader("User-Agent","Mozilla/5.0 AWRStudioUltra/1.3"); if(item.page.isNotBlank())req.addRequestHeader("Referer",item.page)
  val id=(context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
  Toast.makeText(context,"Started: $filename",Toast.LENGTH_SHORT).show()
  return DownloadTask(id,filename,status="Downloading",path="Downloads/$folder/$filename")
 }
 private fun clean(s:String)=s.replace(Regex("[^A-Za-z0-9._-]+"),"_").take(50).ifBlank{"video"}
 private fun ext(u:String)=if(u.lowercase().contains(".webm"))".webm" else ".mp4"
}
