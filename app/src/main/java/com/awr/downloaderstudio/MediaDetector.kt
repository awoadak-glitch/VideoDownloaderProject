package com.awr.downloaderstudio
object MediaDetector{
 fun cleanKey(url:String):String{
  return url
   .substringBefore("&range=")
   .substringBefore("&rn=")
   .substringBefore("&rbuf=")
   .substringBefore("&pot=")
   .substringBefore("&alr=")
   .take(260)
 }
 fun isMedia(url:String):Boolean{
  val u=url.lowercase()
  return u.contains(".mp4")||u.contains(".m3u8")||u.contains(".mpd")||
         u.contains(".webm")||u.contains("videoplayback")||
         u.contains("mime=video")||u.contains("mime=audio")
 }
 fun isStream(url:String):Boolean{
  val u=url.lowercase()
  return u.contains(".m3u8")||u.contains(".mpd")
 }
 fun isAudio(url:String):Boolean{
  val u=url.lowercase()
  return u.contains("mime=audio")||u.contains("audio/")||u.contains(".m4a")
 }
 fun quality(url:String):String{
  val q=Regex("(2160|1440|1080|720|480|360|240|144)").find(url)?.value
  if(q!=null)return q+"P"
  val itag=Regex("[?&]itag=(\\d+)").find(url)?.groupValues?.getOrNull(1)
  return when(itag){
   "313","315"->"2160P"
   "271","308"->"1440P"
   "137","248","299"->"1080P"
   "136","247","298"->"720P"
   "135","244"->"480P"
   "134","243"->"360P"
   "133","242"->"240P"
   "160","278"->"144P"
   "140","251","250","249"->"AUDIO"
   else->"AUTO"
  }
 }
 fun type(url:String):String{
  val u=url.lowercase()
  return when{
   isAudio(url)->"AUDIO"
   u.contains(".m3u8")->"HLS"
   u.contains(".mpd")->"DASH"
   u.contains(".mp4")->"MP4"
   u.contains(".webm")->"WEBM"
   else->"VIDEO"
  }
 }
 fun score(item:MediaItem):Int{
  return when(item.quality){
   "2160P"->2160
   "1440P"->1440
   "1080P"->1080
   "720P"->720
   "480P"->480
   "360P"->360
   "240P"->240
   "144P"->144
   "AUDIO"->1
   else->0
  }
 }
 fun displayQuality(item:MediaItem):String{
  return if(item.type=="AUDIO") "Audio" else item.quality
 }
}
