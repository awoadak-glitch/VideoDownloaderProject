using System;
using System.IO;
using System.Net;
using System.Linq;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;

namespace AwrNext {
 // A separate range engine. No modified aria2 binaries or fake connection counters.
 public sealed class TurboEngine {
  readonly Dictionary<string,TurboTransfer> jobs=new Dictionary<string,TurboTransfer>();
  readonly object gate=new object();
  internal readonly SemaphoreSlim Slots=new SemaphoreSlim(128,128);
  internal readonly TransferRateGate Rate=new TransferRateGate();
  public TurboEngine(){ServicePointManager.DefaultConnectionLimit=Math.Max(128,ServicePointManager.DefaultConnectionLimit);}
  public bool Owns(string id){lock(gate)return jobs.ContainsKey(id);}
  public string Add(DownloadJob job){
   var id="turbo-"+Guid.NewGuid().ToString("N");var transfer=new TurboTransfer(job,this);
   lock(gate)jobs.Add(id,transfer);transfer.Start();return id;
  }
  public void Limit(int kib){Rate.Set(Math.Max(0,(long)kib)*1024);}
  public async Task<object> Call(string method,string id){
   TurboTransfer t;lock(gate)t=jobs[id];
   switch(method){
    case "tellStatus":return t.Snapshot();
    case "pause":await t.Stop(false);return id;
    case "forceRemove":await t.Stop(true);return id;
    case "removeDownloadResult":await t.Stop(true);lock(gate)jobs.Remove(id);return "OK";
    default:throw new Exception("Unsupported Turbo action: "+method);
   }
  }
  public async Task Shutdown(){TurboTransfer[] all;lock(gate)all=jobs.Values.ToArray();await Task.WhenAll(all.Select(t=>t.Stop(false)));}
 }
 public sealed class TransferRateGate {
  readonly object gate=new object();long limit;double next;
  public void Set(long value){lock(gate){limit=value;next=0;}}
  public async Task Consume(int bytes,CancellationToken token){
   double wait=0;lock(gate){if(limit==0)return;double now=(double)Stopwatch.GetTimestamp()/Stopwatch.Frequency;next=Math.Max(now,next);wait=next-now;next+=(double)bytes/limit;}
   while(wait>0){int ms=(int)Math.Min(1000,Math.Ceiling(wait*1000));await Task.Delay(ms,token);wait-=ms/1000.0;lock(gate)if(limit==0)return;}
  }
 }
 public sealed class TurboChunk {
  public long Start,Length,Done;
  [System.Web.Script.Serialization.ScriptIgnore] public bool Busy;
  [System.Web.Script.Serialization.ScriptIgnore] public int Failures;
 }
 public sealed class TurboMap {
  public int Version=1;public string JobId="",ETag="",Modified="";public long Size;
  public List<TurboChunk> Chunks=new List<TurboChunk>();
 }
 sealed class FatalTransferException:Exception {public FatalTransferException(string message):base(message){}}
 sealed class ServerBusyException:Exception {
  public int Delay;public ServerBusyException(int delay):base("Server is limiting requests; reducing connections."){Delay=delay;}
 }
 public sealed class TurboTransfer {
  readonly DownloadJob job;readonly TurboEngine engine;readonly object gate=new object();
  readonly CancellationTokenSource stop=new CancellationTokenSource();
  readonly HashSet<HttpWebRequest> requests=new HashSet<HttpWebRequest>();
  TurboMap map;FileStream file;Task running;string status="active",error="",note="";
  int desired,maximum,active,peak,retries,failuresSinceTune,stalledWindows;
  long done,speed,previousDone;double previousRate=-1,bestRate;int previousCount,bestCount;
  DateTime notBefore=DateTime.MinValue,lastTune=DateTime.UtcNow,lastSave=DateTime.UtcNow;
  bool plateau=false;readonly Stopwatch clock=Stopwatch.StartNew();double lastSample;
  public TurboTransfer(DownloadJob j,TurboEngine e){job=j;engine=e;}
  public void Start(){running=Task.Run((Func<Task>)Run);}
  public async Task Stop(bool removed){stop.Cancel();AbortRequests();if(running!=null)await running;lock(gate)if(status!="complete"&&status!="error")status=removed?"removed":"paused";}
  void AbortRequests(){HttpWebRequest[] q;lock(gate)q=requests.ToArray();foreach(var r in q)try{r.Abort();}catch{}}
  void Fail(Exception e){lock(gate){if(error=="")error=e.Message;}stop.Cancel();AbortRequests();}
  public Dictionary<string,object> Snapshot(){lock(gate)return new Dictionary<string,object>{
   {"status",status},{"completedLength",done.ToString()},{"totalLength",job.Size.ToString()},
   {"downloadSpeed",status=="active"?speed.ToString():"0"},{"connections",active.ToString()},
   {"errorCode",error==""?"0":"TURBO"},{"errorMessage",error},
   {"engineNote",note},{"targetConnections",desired.ToString()},{"peakConnections",peak.ToString()},{"retryCount",retries.ToString()}};}
  void Open(){
   Directory.CreateDirectory(job.WorkDir);string path=job.Part+".awrmap";
   if(File.Exists(path)){
    map=Util.Json().Deserialize<TurboMap>(File.ReadAllText(path));
    if(map==null||map.Version!=1||map.JobId!=job.Id||map.Size!=job.Size||map.Chunks==null||map.Chunks.Count==0||map.Chunks.Count>8192)throw new FatalTransferException("Invalid resume map. Use Restart; partial data was preserved.");
    long at=0;foreach(var c in map.Chunks){if(c==null||c.Start!=at||c.Length<=0||c.Length>job.Size-at||c.Done<0||c.Done>c.Length)throw new FatalTransferException("Invalid range map. Use Restart.");at+=c.Length;}
    if(at!=job.Size||!File.Exists(job.Part)||new FileInfo(job.Part).Length!=job.Size)throw new FatalTransferException("Partial file and resume map disagree. Use Restart.");
   }else{
    if(File.Exists(job.Part)&&new FileInfo(job.Part).Length>0)throw new FatalTransferException("Resume map is missing. Partial bytes were preserved.");
    map=new TurboMap{JobId=job.Id,Size=job.Size,ETag=job.ETag,Modified=job.Modified};
    long chunk=Math.Max(1024*1024,Math.Min(8*1024*1024,(job.Size+job.Connections*4-1)/(job.Connections*4)));
    chunk=Math.Max(chunk,(job.Size+8191)/8192);
    for(long at=0;at<job.Size;at+=chunk)map.Chunks.Add(new TurboChunk{Start=at,Length=Math.Min(chunk,job.Size-at)});
   }
   maximum=Math.Max(1,Math.Min(128,Math.Min(job.Connections,map.Chunks.Count)));
   desired=job.EngineMode=="Fixed"?maximum:Math.Min(4,maximum);
   bestCount=desired;note=job.EngineMode=="Fixed"?"Turbo: fixed connection ceiling.":"Turbo: measuring throughput and adapting connections.";
   done=map.Chunks.Sum(c=>c.Done);previousDone=done;
   file=new FileStream(job.Part,FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.Read,65536,FileOptions.RandomAccess);
   if(file.Length!=job.Size)file.SetLength(job.Size);
   SaveMap();
  }
  void SaveMap(){lock(gate){if(file==null||map==null)return;
   file.Flush(true);string path=job.Part+".awrmap",tmp=path+".tmp";
   byte[] data=Encoding.UTF8.GetBytes(Util.Json().Serialize(map));
   using(var s=new FileStream(tmp,FileMode.Create,FileAccess.Write,FileShare.None)){s.Write(data,0,data.Length);s.Flush(true);}
   if(File.Exists(path))File.Replace(tmp,path,null);else File.Move(tmp,path);
  }}
  async Task Run(){
   Task[] workers=new Task[0];
   try{
    Open();lastSample=clock.Elapsed.TotalSeconds;
    workers=Enumerable.Range(0,maximum).Select(i=>Task.Run(()=>Worker(i))).ToArray();
    var all=Task.WhenAll(workers);
    while(!all.IsCompleted){
     await Task.WhenAny(all,Task.Delay(500));
     Sample();
     if(DateTime.UtcNow-lastSave>TimeSpan.FromSeconds(2)){SaveMap();lastSave=DateTime.UtcNow;}
    }
    await all;
   }catch(Exception e){Fail(e);}
   {
    try{await Task.WhenAll(workers);}catch(Exception e){Fail(e);}
    try{SaveMap();}catch(Exception e){lock(gate)error="Cannot save resume map: "+e.Message;}
    lock(gate){
     if(file!=null){file.Dispose();file=null;}
     status=error!=""?"error":map!=null&&map.Chunks.All(c=>c.Done==c.Length)?"complete":"paused";active=0;speed=0;
    }
   }
  }
  void Sample(){lock(gate){
   double now=clock.Elapsed.TotalSeconds,elapsed=now-lastSample;if(elapsed<=0)return;
   long delta=done-previousDone;double rate=Math.Max(0,delta/elapsed);
   speed=(long)(speed==0?rate:0.45*rate+0.55*speed);previousDone=done;lastSample=now;
   if(job.EngineMode=="Fixed"||DateTime.UtcNow-lastTune<TimeSpan.FromSeconds(3)||stop.IsCancellationRequested)return;
   lastTune=DateTime.UtcNow;
   if(speed>bestRate){bestRate=speed;bestCount=desired;}
   if(failuresSinceTune>0){failuresSinceTune=0;previousRate=-1;plateau=false;return;}
   double target=job.LineMbps*1000000.0/8;
   if(job.LineMbps>0&&speed>=target*0.92){note="Turbo: close to the configured line speed; holding connections.";previousRate=-1;return;}
   if(plateau){note="Turbo: extra connections gave no sustained gain; keeping the faster measured setting.";return;}
   // Require two stable windows before declaring a plateau, to allow TCP warm-up.
   if(previousRate>0&&speed<previousRate*1.10&&desired>previousCount){
    if(++stalledWindows<2)return;
    desired=speed<previousRate*0.9?previousCount:Math.Min(desired,bestCount);plateau=true;
    note="Turbo: connection growth stopped after a throughput plateau.";return;
   }
   stalledWindows=0;
   if(desired<maximum){previousRate=Math.Max(1,speed);previousCount=desired;desired=Math.Min(maximum,desired*2);note="Turbo: throughput improved; testing "+desired+" connections.";}
   else note="Turbo: using the configured maximum; server and network determine further speed.";
  }}
  TurboChunk Claim(int worker){lock(gate){
   if(worker>=desired||DateTime.UtcNow<notBefore)return null;
   var c=map.Chunks.Where(x=>!x.Busy&&x.Done<x.Length).OrderByDescending(x=>x.Length-x.Done).FirstOrDefault();
   if(c!=null)c.Busy=true;return c;
  }}
  bool Finished(){lock(gate)return map.Chunks.All(c=>c.Done==c.Length);}
  async Task Worker(int index){
   try{
    while(!stop.IsCancellationRequested){
     var c=Claim(index);if(c==null){if(Finished())return;await Task.Delay(100,stop.Token);continue;}
     int delay=0;
     try{await ReadChunk(c);c.Failures=0;}
     catch(OperationCanceledException){if(stop.IsCancellationRequested)return;delay=1000;}
     catch(FatalTransferException e){Fail(e);return;}
     catch(Exception e){
      if(stop.IsCancellationRequested)return;
      var busy=e as ServerBusyException;
      lock(gate){retries++;failuresSinceTune++;c.Failures++;
       if(busy!=null){desired=Math.Max(1,desired/2);notBefore=DateTime.UtcNow.AddSeconds(busy.Delay);note="Server requested backoff; reducing concurrency to "+desired+".";delay=busy.Delay*1000;}
       else{note="Retrying interrupted range from its last saved byte.";delay=Math.Min(10000,500*(1<<Math.Min(4,c.Failures)));}
      }
      if(c.Failures>=8){Fail(new Exception("Repeated range failures. "+e.Message));return;}
     }finally{lock(gate)c.Busy=false;}
     if(delay>0)await Task.Delay(delay,stop.Token);
    }
   }catch(OperationCanceledException){}catch(Exception e){Fail(e);}
  }
  async Task ReadChunk(TurboChunk chunk){
   await engine.Slots.WaitAsync(stop.Token);HttpWebRequest request=null;bool counted=false;
   try{
    long from,end;lock(gate){from=chunk.Start+chunk.Done;end=chunk.Start+chunk.Length-1;}
    if(from>end)return;
    request=(HttpWebRequest)WebRequest.Create(job.Url);
    request.Method="GET";request.AllowAutoRedirect=true;request.MaximumAutomaticRedirections=10;request.AutomaticDecompression=DecompressionMethods.None;
    request.KeepAlive=true;request.UserAgent=job.UserAgent;request.Timeout=20000;request.ReadWriteTimeout=20000;
    if(!job.SystemProxy)request.Proxy=null;
    request.ServicePoint.ConnectionLimit=128;request.ServicePoint.Expect100Continue=false;
    request.AddRange(from,end);request.Headers[HttpRequestHeader.AcceptEncoding]="identity";
    if(job.Cookie!="")request.Headers[HttpRequestHeader.Cookie]=job.Cookie;
    if(job.Referer!="")request.Referer=job.Referer;
    if(job.ETag!=""&&!job.ETag.StartsWith("W/",StringComparison.OrdinalIgnoreCase))request.Headers[HttpRequestHeader.IfMatch]=job.ETag;
    else if(job.Modified!="")request.Headers[HttpRequestHeader.IfUnmodifiedSince]=job.Modified;
    lock(gate)requests.Add(request);
    using(var timeout=CancellationTokenSource.CreateLinkedTokenSource(stop.Token))
    using(timeout.Token.Register(()=>{try{request.Abort();}catch{}})){
     timeout.CancelAfter(20000);HttpWebResponse response;
     try{response=(HttpWebResponse)await request.GetResponseAsync();}
     catch(WebException e){
      var er=e.Response as HttpWebResponse;
      if(er!=null){using(er){int code=(int)er.StatusCode;
       if(code==429||code==503){int wait;DateTime at;if(!int.TryParse(er.Headers["Retry-After"],out wait)){wait=DateTime.TryParse(er.Headers["Retry-After"],out at)?(int)Math.Ceiling((at.ToUniversalTime()-DateTime.UtcNow).TotalSeconds):4;}throw new ServerBusyException(Math.Max(1,Math.Min(300,wait)));}
       if(code==412||code==416)throw new FatalTransferException("Remote file changed or requested range was rejected (HTTP "+code+"). Use a fresh link or Restart.");
       if(code==401||code==403||code==404)throw new FatalTransferException("HTTP "+code+": refresh the direct link or its login headers.");
      }}throw;
     }
     using(response){
      var match=Regex.Match(response.Headers["Content-Range"]??"",@"^bytes (\d+)-(\d+)/(\d+)$",RegexOptions.IgnoreCase);
      long a,b,total;
      if(response.StatusCode!=HttpStatusCode.PartialContent||!match.Success||!long.TryParse(match.Groups[1].Value,out a)||!long.TryParse(match.Groups[2].Value,out b)||!long.TryParse(match.Groups[3].Value,out total)||a!=from||b!=end||total!=job.Size)throw new FatalTransferException("Server returned an invalid range. Partial bytes preserved; use Compatibility mode for a new download.");
      if(response.ContentLength>=0&&response.ContentLength!=end-from+1)throw new FatalTransferException("Content-Length does not match the requested range.");
      string encoding=response.ContentEncoding;
      if(!String.IsNullOrEmpty(encoding)&&!String.Equals(encoding,"identity",StringComparison.OrdinalIgnoreCase))throw new FatalTransferException("Compressed range response rejected.");
      if(job.ETag!=""&&!job.ETag.StartsWith("W/",StringComparison.OrdinalIgnoreCase)&&response.Headers["ETag"]!=job.ETag)throw new FatalTransferException("Remote ETag changed during transfer; mixed file bytes were rejected.");
      if(job.Modified!=""&&(job.ETag==""||job.ETag.StartsWith("W/",StringComparison.OrdinalIgnoreCase))&&response.Headers["Last-Modified"]!=job.Modified)throw new FatalTransferException("Remote modification date changed during transfer.");
      lock(gate){active++;peak=Math.Max(peak,active);counted=true;}
      using(var input=response.GetResponseStream()){
       byte[] buffer=new byte[65536];long remaining=end-from+1;
       while(remaining>0){
        timeout.CancelAfter(20000);int n=await input.ReadAsync(buffer,0,(int)Math.Min(buffer.Length,remaining),timeout.Token);timeout.CancelAfter(Timeout.Infinite);
        if(n==0)throw new IOException("Connection ended before the range was complete.");
        await engine.Rate.Consume(n,stop.Token);stop.Token.ThrowIfCancellationRequested();
        lock(gate){try{file.Position=chunk.Start+chunk.Done;file.Write(buffer,0,n);}catch(Exception e){throw new FatalTransferException("Cannot write download data: "+e.Message);}chunk.Done+=n;done+=n;}
        remaining-=n;
       }
      }
     }
    }
   }finally{lock(gate){if(request!=null)requests.Remove(request);if(counted)active--;}engine.Slots.Release();}
  }
 }
}
