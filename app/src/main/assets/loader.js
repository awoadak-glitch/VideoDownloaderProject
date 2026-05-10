(function(){
 if(window.__AWR_ULTRA_CARDS__)return; window.__AWR_ULTRA_CARDS__=true;

 function isMedia(u){
   u=String(u||"");
   return u.includes(".mp4")||u.includes(".m3u8")||u.includes(".mpd")||
          u.includes(".webm")||u.includes("videoplayback")||
          u.includes("mime=video")||u.includes("mime=audio");
 }

 function getMeta(){
   var v=document.querySelector("video");
   var thumb=(document.querySelector('meta[property="og:image"]')||{}).content ||
             (document.querySelector('meta[name="twitter:image"]')||{}).content ||
             (v&&v.poster) || "";
   var title=(document.querySelector('meta[property="og:title"]')||{}).content ||
             (document.querySelector('meta[name="twitter:title"]')||{}).content ||
             document.title || "video";
   var duration="";
   try{
     if(v && isFinite(v.duration) && v.duration>0){
       var sec=Math.floor(v.duration);
       var m=Math.floor(sec/60);
       var s=sec%60;
       duration=m+":"+(s<10?"0"+s:s);
     }
   }catch(e){}
   return {title:title,thumb:thumb,page:location.href,duration:duration};
 }

 function send(u,s){
   try{
     if(!isMedia(u))return;
     var m=getMeta();
     window.AWRBridge.onMediaFound(JSON.stringify({
       url:String(u), source:s||"js", title:m.title, page:m.page,
       thumbnail:m.thumb, duration:m.duration
     }));
   }catch(e){}
 }

 function scan(){
   try{
     document.querySelectorAll("video,source,a[href]").forEach(function(v){
       send(v.currentSrc,"video");
       send(v.src,"video");
       send(v.href,"link");
     });
     performance.getEntriesByType("resource").forEach(function(r){
       send(r.name,"resource");
     });
   }catch(e){}
 }

 var f=window.fetch;
 if(f){
   window.fetch=function(i,n){
     send(typeof i==="string"?i:i&&i.url,"fetch");
     return f.apply(this,arguments).then(function(r){
       send(r.url,"fetch");
       return r;
     });
   };
 }

 var o=XMLHttpRequest.prototype.open;
 XMLHttpRequest.prototype.open=function(m,u){
   send(u,"xhr");
   return o.apply(this,arguments);
 };

 scan();
 setInterval(scan,1500);
})();