package com.awr.downloaderstudio

import android.annotation.SuppressLint
import android.app.*
import android.database.Cursor
import android.graphics.BitmapFactory
import android.os.*
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.net.URL
import kotlin.concurrent.thread

class MainActivity:AppCompatActivity(){
 private lateinit var webView:WebView
 private lateinit var urlBox:EditText
 private lateinit var mediaCounter:TextView
 private lateinit var mediaHeader:TextView
 private lateinit var detectedList:LinearLayout
 private lateinit var downloadsList:LinearLayout
 private lateinit var downloader:Downloader
 private val media=linkedMapOf<String,MediaItem>()
 private val tasks=linkedMapOf<Long,DownloadTask>()
 private val handler=Handler(Looper.getMainLooper())

 @SuppressLint("SetJavaScriptEnabled")
 override fun onCreate(b:Bundle?){
  super.onCreate(b)
  setContentView(resources.getIdentifier("activity_main","layout",packageName))
  webView=findViewById(resources.getIdentifier("webView","id",packageName))
  urlBox=findViewById(resources.getIdentifier("urlBox","id",packageName))
  mediaCounter=findViewById(resources.getIdentifier("mediaCounter","id",packageName))
  mediaHeader=findViewById(resources.getIdentifier("mediaHeader","id",packageName))
  detectedList=findViewById(resources.getIdentifier("detectedList","id",packageName))
  downloadsList=findViewById(resources.getIdentifier("downloadsList","id",packageName))
  downloader=Downloader(this)

  setupNav()
  setupWebView()

  findViewById<Button>(resources.getIdentifier("goBtn","id",packageName)).setOnClickListener{
   val raw=urlBox.text.toString().trim()
   webView.loadUrl(if(raw.startsWith("http"))raw else "https://$raw")
   showPage("browser")
  }
  findViewById<Button>(resources.getIdentifier("downloadFloat","id",packageName)).setOnClickListener{showQualityCards()}
  findViewById<Button>(resources.getIdentifier("startBrowserBtn","id",packageName)).setOnClickListener{showPage("browser")}
  findViewById<Button>(resources.getIdentifier("homeToDetectedBtn","id",packageName)).setOnClickListener{showPage("detected")}
  findViewById<Button>(resources.getIdentifier("homeToDownloadsBtn","id",packageName)).setOnClickListener{showPage("downloads")}

  urlBox.setText("https://example.com")
  showPage("home")
  startProgressLoop()
 }

 private fun setupNav(){
  findViewById<Button>(resources.getIdentifier("navHome","id",packageName)).setOnClickListener{showPage("home")}
  findViewById<Button>(resources.getIdentifier("navBrowser","id",packageName)).setOnClickListener{showPage("browser")}
  findViewById<Button>(resources.getIdentifier("navDetected","id",packageName)).setOnClickListener{showPage("detected")}
  findViewById<Button>(resources.getIdentifier("navDownloads","id",packageName)).setOnClickListener{showPage("downloads")}
 }

 private fun showPage(p:String){
  listOf("homePage","browserPage","detectedPage","downloadsPage").forEach{
   findViewById<View>(resources.getIdentifier(it,"id",packageName)).visibility=View.GONE
  }
  findViewById<View>(resources.getIdentifier(p+"Page","id",packageName)).visibility=View.VISIBLE
  refreshDetected()
  refreshDownloadsUi()
 }

 @SuppressLint("SetJavaScriptEnabled")
 private fun setupWebView(){
  CookieManager.getInstance().setAcceptCookie(true)
  CookieManager.getInstance().setAcceptThirdPartyCookies(webView,true)
  webView.settings.javaScriptEnabled=true
  webView.settings.domStorageEnabled=true
  webView.settings.mediaPlaybackRequiresUserGesture=false
  webView.settings.userAgentString=webView.settings.userAgentString+" AWRStudioUltraCards/1.4"
  webView.addJavascriptInterface(AwrBridge{runOnUiThread{addMedia(it)}},"AWRBridge")
  webView.webChromeClient=WebChromeClient()
  webView.webViewClient=object:WebViewClient(){
   override fun onPageFinished(v:WebView?,u:String?){injectLoader()}
   override fun shouldInterceptRequest(v:WebView?,r:WebResourceRequest?):WebResourceResponse?{
    val u=r?.url?.toString()?:""
    if(MediaDetector.isMedia(u)){
     runOnUiThread{
      addMedia(MediaItem(u,webView.title?:"video",webView.url?:"","request",MediaDetector.quality(u),MediaDetector.type(u),"",""))
     }
    }
    return super.shouldInterceptRequest(v,r)
   }
  }
  webView.setDownloadListener{url,_,_,mime,_->
   if(MediaDetector.isMedia(url)||mime.startsWith("video")||mime.startsWith("audio")){
    addMedia(MediaItem(url,webView.title?:"video",webView.url?:"","download-listener",MediaDetector.quality(url),MediaDetector.type(url),"",""))
   }
  }
 }

 private fun injectLoader(){
  try{webView.evaluateJavascript(assets.open("loader.js").bufferedReader().use{it.readText()},null)}catch(_:Exception){}
 }

 private fun addMedia(i:MediaItem){
  if(i.quality=="AUTO" && i.type=="VIDEO") return
  val key=MediaDetector.cleanKey(i.url)
  if(media.containsKey(key)) return
  media[key]=i
  mediaCounter.text="${smartItems().size} found"
  findViewById<Button>(resources.getIdentifier("downloadFloat","id",packageName)).text="⬇ ${smartItems().size}"
  refreshDetected()
  mediaCounter.animate().scaleX(1.12f).scaleY(1.12f).setDuration(140).withEndAction{
   mediaCounter.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
  }.start()
 }

 private fun smartItems():List<MediaItem>{
  val best=linkedMapOf<String,MediaItem>()
  for(i in media.values){
   val k="${i.type}-${i.quality}"
   val old=best[k]
   if(old==null || i.url.length < old.url.length) best[k]=i
  }
  return best.values
   .filter{it.quality!="AUTO" || it.type=="HLS" || it.type=="DASH" || it.type=="AUDIO"}
   .sortedWith(compareByDescending<MediaItem>{MediaDetector.score(it)}.thenBy{it.type})
 }

 private fun firstMeta():MediaItem? = media.values.firstOrNull{it.title.isNotBlank() || it.thumbnail.isNotBlank()} ?: media.values.firstOrNull()

 private fun refreshDetected(){
  val first=firstMeta()
  mediaHeader.text=if(first==null){
   "No media yet. Play the video for a few seconds."
  }else{
   "Title: ${first.title}\nDuration: ${first.duration.ifBlank{"unknown"}}\nThumbnail: ${if(first.thumbnail.isBlank())"not detected" else "detected"}\nReady qualities: ${smartItems().size}"
  }

  detectedList.removeAllViews()
  for(i in smartItems()){
   val card=qualityCard(i, compact=false)
   detectedList.addView(card)
  }
 }

 private fun qualityCard(item:MediaItem, compact:Boolean):View{
  val box=LinearLayout(this)
  box.orientation=LinearLayout.VERTICAL
  box.setPadding(18,14,18,14)
  box.setBackgroundResource(resources.getIdentifier("panel_bg","drawable",packageName))

  val title=TextView(this)
  title.text="${MediaDetector.displayQuality(item)}  •  ${item.type}"
  title.setTextColor(0xFFF8FAFC.toInt())
  title.textSize=if(compact)15f else 17f
  title.setTypeface(null,1)

  val sub=TextView(this)
  sub.text=when(item.type){
   "AUDIO" -> "Audio only"
   "HLS","DASH" -> "Stream format"
   else -> "Direct file"
  }
  sub.setTextColor(0xFF94A3B8.toInt())
  sub.textSize=12f

  val btn=Button(this)
  btn.text="Download"
  btn.setOnClickListener{confirmDownload(item)}

  box.addView(title)
  box.addView(sub)
  box.addView(btn)
  return box
 }

 private fun showQualityCards(){
  val items=smartItems()
  if(items.isEmpty()){
   AlertDialog.Builder(this).setTitle("AWR Ultra").setMessage("No clean qualities found yet. Play the video for a few seconds.").setPositiveButton("OK",null).show()
   return
  }

  val first=firstMeta()
  val root=LinearLayout(this)
  root.orientation=LinearLayout.VERTICAL
  root.setPadding(22,18,22,8)

  val title=TextView(this)
  title.text=first?.title ?: "Video"
  title.setTextColor(0xFF111827.toInt())
  title.textSize=20f
  title.setTypeface(null,1)
  root.addView(title)

  val meta=TextView(this)
  meta.text="Duration: ${(first?.duration ?: "").ifBlank{"unknown"}} • Qualities: ${items.size}"
  meta.setTextColor(0xFF475569.toInt())
  meta.textSize=13f
  root.addView(meta)

  val thumbUrl=first?.thumbnail ?: ""
  if(thumbUrl.isNotBlank()){
   val img=ImageView(this)
   img.adjustViewBounds=true
   img.maxHeight=360
   img.setBackgroundColor(0xFFE5E7EB.toInt())
   root.addView(img)
   thread{
    try{
     val bmp=BitmapFactory.decodeStream(URL(thumbUrl).openStream())
     runOnUiThread{img.setImageBitmap(bmp)}
    }catch(_:Exception){}
   }
  }

  val scroll=ScrollView(this)
  val list=LinearLayout(this)
  list.orientation=LinearLayout.VERTICAL
  list.setPadding(0,12,0,0)
  for(i in items){
   val row=qualityCard(i, compact=true)
   val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
   lp.setMargins(0,0,0,12)
   list.addView(row,lp)
  }
  scroll.addView(list)
  root.addView(scroll)

  AlertDialog.Builder(this).setView(root).setNegativeButton("Close",null).show()
 }

 private fun confirmDownload(item:MediaItem){
  AlertDialog.Builder(this)
   .setTitle("Confirm Download")
   .setMessage("${item.title}\n\nQuality: ${MediaDetector.displayQuality(item)}\nType: ${item.type}\nSave to: Downloads/AWR Studio/")
   .setPositiveButton("Download"){_,_->
    downloader.start(item,item.type=="AUDIO")?.let{
     tasks[it.id]=it
     showPage("downloads")
     refreshDownloadsUi()
    }
   }
   .setNegativeButton("Cancel",null)
   .show()
 }

 private fun startProgressLoop(){
  handler.postDelayed(object:Runnable{
   override fun run(){updateProgress();handler.postDelayed(this,1000)}
  },1000)
 }

 private fun updateProgress(){
  if(tasks.isEmpty())return
  val dm=getSystemService(DOWNLOAD_SERVICE) as DownloadManager
  for((id,t) in tasks){
   var c:Cursor?=null
   try{
    c=dm.query(DownloadManager.Query().setFilterById(id))
    if(c!=null&&c.moveToFirst()){
     val b=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
     val total=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
     val st=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
     t.progress=if(total>0)((b*100)/total).toInt() else 0
     t.totalText="${fmt(b)} / ${if(total>0)fmt(total) else "unknown"}"
     t.status=when(st){
      DownloadManager.STATUS_SUCCESSFUL->"Completed"
      DownloadManager.STATUS_FAILED->"Failed"
      DownloadManager.STATUS_PAUSED->"Paused"
      DownloadManager.STATUS_PENDING->"Pending"
      DownloadManager.STATUS_RUNNING->"Downloading"
      else->"Working"
     }
    }
   }catch(_:Exception){}finally{c?.close()}
  }
  refreshDownloadsUi()
 }

 private fun refreshDownloadsUi(){
  downloadsList.removeAllViews()
  if(tasks.isEmpty()){
   TextView(this).also{
    it.text="No downloads yet.\nFiles will be saved in Downloads/AWR Studio/"
    it.setTextColor(0xFF94A3B8.toInt())
    it.textSize=14f
    it.setPadding(16,16,16,16)
    downloadsList.addView(it)
   }
   return
  }
  for(t in tasks.values){
   val box=LinearLayout(this)
   box.orientation=LinearLayout.VERTICAL
   box.setPadding(14,12,14,12)
   val title=TextView(this)
   title.text=t.name
   title.setTextColor(0xFFF8FAFC.toInt())
   title.textSize=14f
   title.setTypeface(null,1)
   val info=TextView(this)
   info.text="${t.status} • ${t.progress}% • ${t.totalText}\n${t.path}"
   info.setTextColor(0xFF94A3B8.toInt())
   info.textSize=12f
   val pr=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal)
   pr.max=100
   pr.progress=t.progress
   box.addView(title);box.addView(info);box.addView(pr)
   downloadsList.addView(box)
  }
 }

 private fun fmt(b:Long):String{
  if(b<1024)return "$b B"
  val kb=b/1024.0
  if(kb<1024)return String.format("%.1f KB",kb)
  val mb=kb/1024.0
  if(mb<1024)return String.format("%.1f MB",mb)
  return String.format("%.1f GB",mb/1024)
 }
}
