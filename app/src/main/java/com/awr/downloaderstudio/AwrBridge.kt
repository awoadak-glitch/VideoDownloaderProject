package com.awr.downloaderstudio
import android.webkit.JavascriptInterface
import org.json.JSONObject
class AwrBridge(private val onFound:(MediaItem)->Unit){
 @JavascriptInterface fun onMediaFound(json:String){
  try{
   val o=JSONObject(json)
   val u=o.optString("url")
   if(!MediaDetector.isMedia(u))return
   onFound(
    MediaItem(
     u,
     o.optString("title","video"),
     o.optString("page",""),
     o.optString("source","js"),
     MediaDetector.quality(u),
     MediaDetector.type(u),
     o.optString("thumbnail",""),
     o.optString("duration","")
    )
   )
  }catch(_:Exception){}
 }
}
