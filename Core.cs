using System;
using System.Collections.Generic;
using System.Linq;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;
using System.Security.Cryptography;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

namespace AwrNext {
 public class DownloadJob {
  public string Id=Guid.NewGuid().ToString("N"), Url="", Name="", Folder="", Referer="", Cookie="", UserAgent="Mozilla/5.0 AWRDownloadNext/2.0";
  public string Status="Queued", Message="", Gid="", ETag="", Modified="", ExpectedHash="", ActualHash="";
  public long Size=-1, Done=0, Speed=0;
  public int Connections=8, ActiveConnections=0;
  public bool Range=false, ReadyToFinalize=false, NameFromServer=false;
  public DateTime Added=DateTime.UtcNow;
  public string WorkDir { get { return Path.Combine(Folder,".awr-parts",Id); } }
  public string Part { get { return Path.Combine(WorkDir,"payload.part"); } }
  public string Target { get { return Path.Combine(Folder,Name); } }
 }
 public class Settings {
  public string Folder=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),"Downloads");
  public int Connections=8, Concurrent=2, LimitKiB=0;
  public DateTime StartAt=DateTime.MinValue;
 }
 public class State { public List<DownloadJob> Jobs=new List<DownloadJob>(); public Settings Settings=new Settings(); }
 public class RemoteInfo {
  public long Size=-1; public bool Range=false; public string ETag="", Modified="", Name="", ContentType="";
 }
 public static class Util {
  public static JavaScriptSerializer Json() { return new JavaScriptSerializer { MaxJsonLength=16*1024*1024, RecursionLimit=80 }; }
  public static string SafeName(string name) {
   if(String.IsNullOrWhiteSpace(name)) name="download.bin";
   name=name.Replace('\\','/'); int slash=name.LastIndexOf('/'); if(slash>=0)name=name.Substring(slash+1);
   name=Regex.Replace(name,@"[<>:""/\\|?*\x00-\x1f]","_").Trim().TrimEnd('.');
   if(name.Length>180) name=name.Substring(0,140)+Path.GetExtension(name).Substring(0,Math.Min(Path.GetExtension(name).Length,35));
   if(name.Length==0) name="download.bin";
   if(Regex.IsMatch(name,@"^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\.|$)",RegexOptions.IgnoreCase)) name="_"+name;
   return name;
  }
  public static string UniqueName(string dir,string name,IEnumerable<DownloadJob> jobs) {
   name=SafeName(name); string ext=Path.GetExtension(name),stem=Path.GetFileNameWithoutExtension(name); int i=1;
   while(File.Exists(Path.Combine(dir,name)) || jobs.Any(j=>String.Equals(j.Target,Path.Combine(dir,name),StringComparison.OrdinalIgnoreCase))) name=stem+" ("+(i++)+")"+ext;
   return name;
  }
  public static bool ValidUrl(string s) { Uri u; return Uri.TryCreate(s,UriKind.Absolute,out u) && (u.Scheme=="http"||u.Scheme=="https") && String.IsNullOrEmpty(u.UserInfo); }
  public static void Header(string s) { if(s!=null && (s.Contains("\r")||s.Contains("\n"))) throw new Exception("Header contains a line break."); }
  public static string Bytes(long n) { if(n<0)return "—"; string[] u={"B","KB","MB","GB","TB"}; double x=n; int i=0; while(x>=1024 && i<4){x/=1024;i++;} return x.ToString(i==0?"0":"0.0")+" "+u[i]; }
  public static string Hash(string path) { using(var s=File.OpenRead(path)) using(var h=SHA256.Create()) return BitConverter.ToString(h.ComputeHash(s)).Replace("-","").ToLowerInvariant(); }
  public static string Get(Dictionary<string,object> d,string k) { return d.ContainsKey(k)&&d[k]!=null?Convert.ToString(d[k],System.Globalization.CultureInfo.InvariantCulture):""; }
  public static long Num(Dictionary<string,object> d,string k) { long n; return long.TryParse(Get(d,k),out n)?n:0; }
 }
 public static class Probe {
  public static async Task<RemoteInfo> Fetch(DownloadJob j) {
   if(!Util.ValidUrl(j.Url)) throw new Exception("Use a direct HTTP or HTTPS download link.");
   Util.Header(j.Cookie);Util.Header(j.Referer);Util.Header(j.UserAgent);
   using(var h=new HttpClientHandler{AllowAutoRedirect=true,AutomaticDecompression=DecompressionMethods.None,UseCookies=false})
   using(var c=new HttpClient(h){Timeout=TimeSpan.FromSeconds(25)})
   using(var q=new HttpRequestMessage(HttpMethod.Get,j.Url)) {
    q.Headers.Range=new System.Net.Http.Headers.RangeHeaderValue(0,0);
    q.Headers.TryAddWithoutValidation("User-Agent",j.UserAgent);
    q.Headers.TryAddWithoutValidation("Accept-Encoding","identity");
    if(j.Referer!="")q.Headers.TryAddWithoutValidation("Referer",j.Referer);
    if(j.Cookie!="")q.Headers.TryAddWithoutValidation("Cookie",j.Cookie);
    using(var r=await c.SendAsync(q,HttpCompletionOption.ResponseHeadersRead)) {
     r.EnsureSuccessStatusCode();
     var p=new RemoteInfo();
     p.ETag=r.Headers.ETag==null?"":r.Headers.ETag.ToString();
     p.Modified=r.Content.Headers.LastModified.HasValue?r.Content.Headers.LastModified.Value.ToString("R"):"";
     p.ContentType=r.Content.Headers.ContentType==null?"":r.Content.Headers.ContentType.MediaType;
     if(p.ContentType=="text/html"||p.ContentType=="application/xhtml+xml") throw new Exception("This is a web page, login page, or expired link. Copy the direct file URL.");
     var cr=r.Content.Headers.ContentRange;
     p.Range=r.StatusCode==HttpStatusCode.PartialContent && cr!=null && cr.From==0 && cr.To==0 && cr.Length.HasValue;
     p.Size=p.Range?cr.Length.Value:(r.StatusCode==HttpStatusCode.OK?(r.Content.Headers.ContentLength??-1):-1);
     if(r.StatusCode==HttpStatusCode.PartialContent&&!p.Range) throw new Exception("Invalid Content-Range returned by server.");
     if(r.Content.Headers.ContentEncoding.Any(e=>!String.Equals(e,"identity",StringComparison.OrdinalIgnoreCase))) throw new Exception("Server sent compressed transfer bytes; safe segmented download is unavailable.");
     var cd=r.Content.Headers.ContentDisposition;
     if(cd!=null)p.Name=(cd.FileNameStar??cd.FileName??"").Trim('"');
     if(p.Name=="")p.Name=Uri.UnescapeDataString(Path.GetFileName(r.RequestMessage.RequestUri.AbsolutePath));
     p.Name=Util.SafeName(p.Name);
     return p;
    }
   }
  }
  public static bool Same(DownloadJob j,RemoteInfo p) {
   if(j.Size<0||p.Size<0||j.Size!=p.Size)return false;
   if(!String.IsNullOrEmpty(j.ETag)&&!j.ETag.StartsWith("W/",StringComparison.OrdinalIgnoreCase))return j.ETag==p.ETag;
   if(j.Modified!="")return j.Modified==p.Modified;
   return j.ExpectedHash!="";
  }
 }
 public sealed class AriaEngine:IDisposable {
  Process process; HttpClient http; string secret; int port; long counter;
  public async Task Start(string exe,string data) {
   Directory.CreateDirectory(data);
   using(var rng=RandomNumberGenerator.Create()){byte[] b=new byte[32];rng.GetBytes(b);secret=BitConverter.ToString(b).Replace("-","");}
   var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();port=((IPEndPoint)listener.LocalEndpoint).Port;listener.Stop();
   var cfg=Path.Combine(data,"engine.conf");
   File.WriteAllText(cfg,"enable-rpc=true\nrpc-listen-all=false\nrpc-listen-port="+port+"\nrpc-secret="+secret+
    "\nrpc-allow-origin-all=false\nno-conf=true\ncheck-certificate=true\nauto-save-interval=2\nfile-allocation=none\nsummary-interval=0\nconsole-log-level=error\nmax-concurrent-downloads=16\nmax-download-result=2000\nkeep-unfinished-download-result=true\nfollow-torrent=false\nfollow-metalink=false\nstop-with-process="+Process.GetCurrentProcess().Id+"\n",new UTF8Encoding(false));
   process=Process.Start(new ProcessStartInfo{FileName=exe,Arguments="--conf-path=\""+cfg+"\"",UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true});
   process.OutputDataReceived+=(s,e)=>{};process.ErrorDataReceived+=(s,e)=>{};process.BeginOutputReadLine();process.BeginErrorReadLine();
   http=new HttpClient(new HttpClientHandler{UseProxy=false}){Timeout=TimeSpan.FromSeconds(8)};
   Exception last=null;
   for(int i=0;i<50;i++){try{await Call("getVersion");try{File.Delete(cfg);}catch{}return;}catch(Exception e){last=e;}if(process.HasExited)break;await Task.Delay(100);}
   throw new Exception("Download engine did not start. "+(last==null?"":last.Message));
  }
  public async Task<object> Call(string method,params object[] args) {
   var pp=new List<object>{"token:"+secret};pp.AddRange(args);
   var json=Util.Json().Serialize(new{jsonrpc="2.0",id=Interlocked.Increment(ref counter).ToString(),method="aria2."+method,@params=pp});
   using(var r=await http.PostAsync("http://127.0.0.1:"+port+"/jsonrpc",new StringContent(json,Encoding.UTF8,"application/json"))) {
    string body=await r.Content.ReadAsStringAsync();
    var d=Util.Json().Deserialize<Dictionary<string,object>>(body);
    if(d.ContainsKey("error"))throw new Exception(Util.Get((Dictionary<string,object>)d["error"],"message"));
    r.EnsureSuccessStatusCode(); return d["result"];
   }
  }
  public async Task<string> Add(DownloadJob j,int limit) {
   var opts=new Dictionary<string,object>{
    {"dir",j.WorkDir},{"out","payload.part"},{"continue","true"},{"allow-overwrite","false"},{"auto-file-renaming","false"},
    {"split",j.Range?j.Connections.ToString():"1"},{"max-connection-per-server",j.Range?j.Connections.ToString():"1"},
    {"min-split-size","1M"},{"max-tries","0"},{"max-file-not-found","3"},{"retry-wait","4"},{"connect-timeout","15"},{"timeout","25"},
    {"max-resume-failure-tries","0"},{"user-agent",j.UserAgent},{"max-download-limit","0"},{"http-accept-gzip","false"}
   };
   var headers=new List<string>{"Accept-Encoding: identity"};
   if(j.ETag!=""&&!j.ETag.StartsWith("W/",StringComparison.OrdinalIgnoreCase))headers.Add("If-Match: "+j.ETag);
   else if(j.Modified!="")headers.Add("If-Unmodified-Since: "+j.Modified);
   if(j.Cookie!="")headers.Add("Cookie: "+j.Cookie);
   if(j.Referer!="")opts["referer"]=j.Referer;
   opts["header"]=headers.ToArray();
   // The expected checksum is verified by our finalizer, never by a silent redownload.
   return Convert.ToString(await Call("addUri",new string[]{j.Url},opts));
  }
  public async Task Shutdown() {try{await Call("shutdown");}catch{} if(process!=null)await Task.Run(()=>{try{if(!process.WaitForExit(5000))process.Kill();}catch{}});Dispose();}
  public void Dispose(){if(http!=null)http.Dispose();if(process!=null)process.Dispose();}
 }
 public class Manager {
  public State State=new State(); public AriaEngine Engine=new AriaEngine();
  public string DataDir; public event Action Changed; bool ticking=false,closing=false; int changing=0;
  Dictionary<string,int> generation=new Dictionary<string,int>();
  public Manager(string data){DataDir=data;Load();}
  public void Notify(){if(Changed!=null)Changed();}
  public void Load() {
   Directory.CreateDirectory(DataDir);string f=Path.Combine(DataDir,"downloads.json");
   if(File.Exists(f)) {
    try {State=Read(f);}
    catch {try{State=Read(f+".bak");}catch{throw new Exception("Download history cannot be read. It was preserved; restore downloads.json.bak before continuing.");}}
   }
   foreach(var j in State.Jobs){
    j.Gid="";j.Speed=0;j.ActiveConnections=0;
    if(j.Status!="Complete"&&j.Status!="Error")j.Status="Paused";
   }
  }
  State Read(string f) {
   byte[] b=File.ReadAllBytes(f);
   if(b.Length>4&&b[0]==65&&b[1]==87&&b[2]==82&&b[3]==50)b=ProtectedData.Unprotect(b.Skip(4).ToArray(),null,DataProtectionScope.CurrentUser);
   return Util.Json().Deserialize<State>(Encoding.UTF8.GetString(b));
  }
  public void Save() {
   var f=Path.Combine(DataDir,"downloads.json");var tmp=f+".tmp";
   byte[] b=Encoding.UTF8.GetBytes(Util.Json().Serialize(State));
   if(Environment.OSVersion.Platform==PlatformID.Win32NT)b=new byte[]{65,87,82,50}.Concat(ProtectedData.Protect(b,null,DataProtectionScope.CurrentUser)).ToArray();
   using(var s=new FileStream(tmp,FileMode.Create,FileAccess.Write,FileShare.None)){s.Write(b,0,b.Length);s.Flush(true);}
   if(File.Exists(f)){File.Replace(tmp,f,f+".bak");}else File.Move(tmp,f);
  }
  int Version(DownloadJob j){return generation.ContainsKey(j.Id)?generation[j.Id]:0;}
  void Advance(DownloadJob j){generation[j.Id]=Version(j)+1;}
  public DownloadJob Enqueue(string url,string folder,string name,int con,string hash="",string referer="",string cookie=""){
   if(!Util.ValidUrl(url))throw new Exception("Direct HTTP/HTTPS URL required.");
   if(!String.IsNullOrWhiteSpace(hash)&&!Regex.IsMatch(hash,@"\A[0-9a-fA-F]{64}\z"))throw new Exception("SHA-256 must contain exactly 64 hexadecimal characters.");
   Directory.CreateDirectory(folder);
   bool autoName=String.IsNullOrWhiteSpace(name);
   if(name=="")name=Uri.UnescapeDataString(Path.GetFileName(new Uri(url).AbsolutePath));
   var j=new DownloadJob{NameFromServer=autoName,Url=url,Folder=Path.GetFullPath(folder),Name=Util.UniqueName(folder,name,State.Jobs),Connections=Math.Max(1,Math.Min(16,con)),ExpectedHash=hash.ToLowerInvariant(),Referer=referer,Cookie=cookie};
   State.Jobs.Add(j);Save();Notify();return j;
  }
  public async Task Tick(){
   if(ticking||closing)return;ticking=true;
   try{
    foreach(var j in State.Jobs.ToArray()){
     if(j.Gid==""||j.Status!="Downloading")continue;
     try{
      var d=(Dictionary<string,object>)await Engine.Call("tellStatus",j.Gid);
      if(j.Status!="Downloading")continue;
      j.Done=Util.Num(d,"completedLength");j.Speed=Util.Num(d,"downloadSpeed");j.ActiveConnections=(int)Util.Num(d,"connections");
      var total=Util.Num(d,"totalLength");if(j.Size<0&&total>0)j.Size=total;
      var status=Util.Get(d,"status");
      if(status=="complete"){
       j.ReadyToFinalize=true;j.Status="Verifying";j.Speed=0;Save();
       await FinalizeJob(j);
      } else if(status=="error"||status=="removed"){
       j.Status="Error";j.Speed=0;j.Message="Engine "+Util.Get(d,"errorCode")+": "+Util.Get(d,"errorMessage");
       try{await Engine.Call("removeDownloadResult",j.Gid);}catch{}j.Gid="";Save();
      }
     }catch(Exception e){j.Message=e.Message;}
    }
    bool scheduled=State.Settings.StartAt!=DateTime.MinValue&&DateTime.Now<State.Settings.StartAt;
    if(!scheduled) {
     if(State.Settings.StartAt!=DateTime.MinValue){State.Settings.StartAt=DateTime.MinValue;Save();}
     int active=State.Jobs.Count(x=>x.Status=="Downloading"||x.Status=="Checking"||x.Status=="Verifying");
     foreach(var j in State.Jobs.Where(x=>x.Status=="Queued").Take(Math.Max(0,State.Settings.Concurrent-active)).ToArray()){
      j.Status="Checking";j.Message="";StartDetached(j);
     }
    }
    Notify();
   }finally{ticking=false;}
  }
  async void StartDetached(DownloadJob j){changing++;try{await StartJob(j);}finally{changing--;Notify();}}
  public async Task StartJob(DownloadJob j) {
   int v=Version(j);
   try{
    if(j.ReadyToFinalize){j.Status="Verifying";await FinalizeJob(j);return;}
    var p=await Probe.Fetch(j);
    if(v!=Version(j)||closing||j.Status=="Paused")return;
    bool existing=File.Exists(j.Part)&&new FileInfo(j.Part).Length>0;
    if(existing){
     if(!p.Range)throw new Exception("Server does not support resuming. Use Restart for a clean download.");
     if(!File.Exists(j.Part+".aria2"))throw new Exception("Resume map is missing. Use Restart; partial bytes were preserved.");
     if(!Probe.Same(j,p))throw new Exception("Remote file changed or identity cannot be verified. Use Restart, or supply a trusted SHA-256.");
    }
    j.Size=p.Size;j.ETag=p.ETag;j.Modified=p.Modified;j.Range=p.Range;
    if(!existing&&j.NameFromServer&&p.Name!="download.bin")j.Name=Util.UniqueName(j.Folder,p.Name,State.Jobs.Where(x=>x.Id!=j.Id));
    Directory.CreateDirectory(j.WorkDir);
    if(j.Size>0){var disk=new DriveInfo(Path.GetPathRoot(j.Folder));if(disk.IsReady && disk.AvailableFreeSpace<Math.Max(0,j.Size-j.Done)+4*1024*1024)throw new Exception("Not enough free disk space.");}
    Save();
    if(j.Gid!=""){try{await Engine.Call("forceRemove",j.Gid);}catch{}try{await Engine.Call("removeDownloadResult",j.Gid);}catch{}j.Gid="";}
    var gid=await Engine.Add(j,State.Settings.LimitKiB);
    if(v!=Version(j)||closing||j.Status=="Paused"){try{await Engine.Call("forceRemove",gid);}catch{}return;}
    j.Gid=gid;j.Status="Downloading";j.Message=p.Range?"":"Server has no resume/range support. A single connection is used.";Save();
   }catch(Exception e){if(v==Version(j)&&!closing&&j.Status!="Paused"){j.Status="Error";j.Message=e.Message;j.Speed=0;Save();}}
  }
  public async Task FinalizeJob(DownloadJob j){
   try{
    if(!File.Exists(j.Part)){
     if(j.ActualHash!=""&&File.Exists(j.Target)&&await Task.Run(()=>Util.Hash(j.Target))==j.ActualHash){j.Status="Complete";j.ReadyToFinalize=false;j.Done=new FileInfo(j.Target).Length;Save();return;}
     throw new Exception("Completed temporary file is missing.");
    }
    long len=new FileInfo(j.Part).Length;
    if(j.Size>=0&&len!=j.Size)throw new Exception("File size mismatch: expected "+j.Size+", received "+len+".");
    j.Message="Checking SHA-256…";Notify();
    j.ActualHash=await Task.Run(()=>Util.Hash(j.Part));
    if(j.ExpectedHash!=""&&j.ActualHash!=j.ExpectedHash)throw new Exception("SHA-256 mismatch. File kept in temporary storage and NOT marked complete. Use Restart.");
    if(File.Exists(j.Target))j.Name=Util.UniqueName(j.Folder,j.Name,State.Jobs.Where(x=>x.Id!=j.Id));
    Save(); // Persist hash and final path before the atomic rename, allowing crash recovery.
    File.Move(j.Part,j.Target);
    j.Done=len;j.Size=len;j.Speed=0;j.Status="Complete";j.ReadyToFinalize=false;
    j.Message=j.ExpectedHash!=""?"SHA-256 matched the supplied reference.":"Size checked; SHA-256 calculated (no reference supplied).";
    try{if(File.Exists(j.Part+".aria2"))File.Delete(j.Part+".aria2");Directory.Delete(j.WorkDir);}catch{}
    if(j.Gid!=""){try{await Engine.Call("removeDownloadResult",j.Gid);}catch{}j.Gid="";}
    Save();Notify();
   }catch(Exception e){j.Status="Error";j.Message=e.Message;j.Speed=0;Save();Notify();}
  }
  public async Task Pause(DownloadJob j){
   if(j.Status=="Complete"||j.Status=="Verifying")return;
   Advance(j);j.Status="Paused";j.Speed=0;
   if(j.Gid!=""){
    try{await Engine.Call("pause",j.Gid);
     for(int n=0;n<40;n++){var s=(Dictionary<string,object>)await Engine.Call("tellStatus",j.Gid);j.Done=Util.Num(s,"completedLength");if(Util.Get(s,"status")=="complete"){j.ReadyToFinalize=true;break;}if(Util.Get(s,"status")=="paused")break;await Task.Delay(50);}
    }catch(Exception e){j.Message=e.Message;}
   }
   Save();Notify();
  }
  public void Resume(DownloadJob j){if(j.Status=="Complete"||j.Status=="Verifying"||j.Status=="Downloading"||j.Status=="Checking")return;j.Status="Queued";j.Message="";Save();Notify();}
  public async Task Restart(DownloadJob j){
   if(j.Status=="Complete"||j.Status=="Verifying")return;
   await Pause(j);
   if(j.Gid!=""){try{await Engine.Call("forceRemove",j.Gid);}catch{}try{await Engine.Call("removeDownloadResult",j.Gid);}catch{}j.Gid="";}
   if(File.Exists(j.Part))File.Delete(j.Part);if(File.Exists(j.Part+".aria2"))File.Delete(j.Part+".aria2");
   j.Done=0;j.Size=-1;j.ETag="";j.Modified="";j.ActualHash="";j.ReadyToFinalize=false;Resume(j);
  }
  public async Task UpdateUrl(DownloadJob j,string url){
   if(!Util.ValidUrl(url))throw new Exception("Invalid URL.");
   if(j.Status=="Complete"||j.Status=="Verifying")return;
   await Pause(j);j.Url=url;j.Message="Link updated. The remote file identity will be checked before resume.";Save();Notify();
  }
  public async Task Remove(DownloadJob j){
   if(j.Status=="Verifying")return;
   await Pause(j);if(j.Gid!=""){try{await Engine.Call("forceRemove",j.Gid);}catch{}try{await Engine.Call("removeDownloadResult",j.Gid);}catch{}}
   // Delete only our isolated temporary directory. Completed user files are retained.
   if(Directory.Exists(j.WorkDir))Directory.Delete(j.WorkDir,true);
   State.Jobs.Remove(j);Save();Notify();
  }
  public async Task ApplySettings(){await Engine.Call("changeGlobalOption",new Dictionary<string,string>{{"max-overall-download-limit",State.Settings.LimitKiB<=0?"0":State.Settings.LimitKiB+"K"}});Save();}
  public async Task Close(){
   closing=true;
   foreach(var j in State.Jobs.Where(x=>x.Status!="Complete"&&x.Status!="Verifying").ToArray())await Pause(j);
   for(int i=0;i<350&&(ticking||changing>0);i++)await Task.Delay(100);
   Save();await Engine.Shutdown();
  }
 }
}
