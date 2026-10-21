using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Diagnostics;
using System.Threading.Tasks;
using System.Collections.Generic;
using AwrNext;
class TurboTests {
 static string root=Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"turbo-test"),url="http://127.0.0.1:18941";
 static string sha=File.ReadAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"test-payload.sha256")).Trim();
 static int pass;
 static void Check(bool ok,string what){if(!ok)throw new Exception("FAIL: "+what);Console.WriteLine("PASS TURBO "+(++pass)+": "+what);}
 static async Task<Manager> New(string name,string mode="Fixed",int limit=0){var m=new Manager(Path.Combine(root,name));await m.Engine.Start(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"dist","Engine","aria2c.exe"),m.DataDir);m.State.Settings.EngineMode=mode;m.State.Settings.Concurrent=1;m.State.Settings.LimitKiB=limit;await m.ApplySettings();return m;}
 static async Task Until(Manager m,DownloadJob j,Func<bool> done,int seconds=35){var end=DateTime.UtcNow.AddSeconds(seconds);while(!done()&&DateTime.UtcNow<end){await m.Tick();await Task.Delay(80);}if(!done())throw new Exception("Timeout: "+j.Status+" "+j.Message+" "+j.EngineNote);}
 static async Task ErrorCase(string path,string text){Manager m=await New(path);try{var j=m.Enqueue(url+"/"+path,Path.Combine(root,"files"),path+".bin",8,sha);await Until(m,j,()=>j.Status=="Error");Check(j.Message.Contains(text)&&!File.Exists(j.Target),path+": invalid server bytes rejected");}finally{m.Close().GetAwaiter().GetResult();}}
 static async Task<double> Benchmark(int connections,string mode){
  var m=await New("bench-"+connections+"-"+mode,mode);try{
   var j=m.Enqueue(url+"/benchmark",Path.Combine(root,"bench"),connections+"-"+mode+".bin",connections);
   await Until(m,j,()=>j.Done>0);
   // Adaptive ramp is allowed 17 s; fixed runs warm up for 2 s.
   var warm=Stopwatch.StartNew();while(warm.Elapsed.TotalSeconds<(mode=="Adaptive"?17:2)){await m.Tick();await Task.Delay(80);}
   long start=j.Done;var timer=Stopwatch.StartNew();
   while(timer.Elapsed.TotalSeconds<4&&j.Status=="Downloading"){await m.Tick();await Task.Delay(80);}
   double seconds=timer.Elapsed.TotalSeconds;double rate=(j.Done-start)/seconds;
   int peak=j.PeakConnections;await m.Pause(j);
   Console.WriteLine("BENCH "+mode+" ceiling="+connections+" peak="+peak+" bytes/s="+((long)rate)+" Mbps="+(rate*8/1000000).ToString("0.00")+" window_s="+seconds.ToString("0.00"));
   Check(j.Backend=="Turbo"&&rate>0&&peak<=connections,"measured bytes and real connection ceiling: "+mode+" "+connections);
   if(connections==128)Check(peak>16,"native engine actually exceeds the old 16-connection limit");
   return rate;
  }finally{m.Close().GetAwaiter().GetResult();}
 }
 static async Task CrashWorker(){var m=await New("crash","Fixed",1024);var j=m.Enqueue(url+"/data",Path.Combine(root,"files"),"crash.bin",16,sha);var clock=Stopwatch.StartNew();await Until(m,j,()=>j.Done>3*1024*1024&&clock.Elapsed.TotalSeconds>4);File.WriteAllText(Path.Combine(root,"crash-ready"),"ready");Process.GetCurrentProcess().Kill();}
 static async Task MainAsync(){
  Directory.CreateDirectory(root);
  await ErrorCase("wrong","invalid range");await ErrorCase("flip","ETag changed");
  var warmManager=await New("warmup","Adaptive");try{var warmJob=warmManager.Enqueue(url+"/delayed-headers",Path.Combine(root,"files"),"warm.bin",128,sha);await Until(warmManager,warmJob,()=>warmJob.Status=="Downloading");var warmClock=Stopwatch.StartNew();while(warmClock.Elapsed.TotalSeconds<3.2){await warmManager.Tick();await Task.Delay(80);}Check(warmJob.Done==0&&warmJob.TargetConnections==4,"slow response headers are not mistaken for a throughput plateau");await warmManager.Pause(warmJob);}finally{warmManager.Close().GetAwaiter().GetResult();}
  var cookieManager=await New("redirect-cookie");try{var cookieJob=cookieManager.Enqueue(url+"/redirect",Path.Combine(root,"files"),"cookie.bin",16,sha);await Until(cookieManager,cookieJob,()=>cookieJob.Status=="Complete"||cookieJob.Status=="Error");Check(cookieJob.Status=="Complete"&&cookieJob.TransferUrl.EndsWith("/cookie-data")&&Util.Hash(cookieJob.Target)==sha,"redirect resolved once and session cookies preserved for ranges");}finally{cookieManager.Close().GetAwaiter().GetResult();}
  var m=await New("backoff");try{var j=m.Enqueue(url+"/busy",Path.Combine(root,"files"),"backoff.bin",16,sha);await Until(m,j,()=>j.Status=="Complete"||j.Status=="Error");Check(j.Status=="Complete"&&Util.Hash(j.Target)==sha&&j.RetryCount>=1,"HTTP 429 backoff recovers with exact SHA-256");}finally{m.Close().GetAwaiter().GetResult();}
  m=await New("corrupt-map","Fixed",1024);DownloadJob bad;
  try{bad=m.Enqueue(url+"/data",Path.Combine(root,"files"),"corrupt.bin",16,sha);await Until(m,bad,()=>bad.Done>512*1024);await m.Pause(bad);}finally{m.Close().GetAwaiter().GetResult();}
  var map=Util.Json().Deserialize<TurboMap>(File.ReadAllText(bad.Part+".awrmap"));map.Chunks[0].Done=map.Chunks[0].Length+1;File.WriteAllText(bad.Part+".awrmap",Util.Json().Serialize(map));
  m=await New("corrupt-map");try{var j=m.State.Jobs.Single();m.Resume(j);await Until(m,j,()=>j.Status=="Error");Check(j.Message.Contains("Invalid range map")&&!File.Exists(j.Target),"corrupt map cannot mark a preallocated file complete");}finally{m.Close().GetAwaiter().GetResult();}
  var self=Process.GetCurrentProcess().MainModule.FileName;
  using(var child=Process.Start(new ProcessStartInfo{FileName=self,Arguments="--crash-worker",UseShellExecute=false,CreateNoWindow=true})){
   bool exited=child.WaitForExit(30000);if(!exited)child.Kill();Check(exited&&File.Exists(Path.Combine(root,"crash-ready")),"abrupt process death after a flushed checkpoint");
  }
  m=await New("crash");try{var j=m.State.Jobs.Single();var saved=Util.Json().Deserialize<TurboMap>(File.ReadAllText(j.Part+".awrmap")).Chunks.Sum(c=>c.Done);m.Resume(j);await Until(m,j,()=>j.Status=="Complete"||j.Status=="Error");Check(saved>0&&j.Status=="Complete"&&Util.Hash(j.Target)==sha,"resume after abrupt process death produces exact original bytes");}finally{m.Close().GetAwaiter().GetResult();}
  Console.WriteLine("BENCHMARK: controlled localhost HTTP server, 300 KiB/s per connection, same 256 MiB file. NOT the user's ISP or IDM.");
  double one=await Benchmark(1,"Fixed"),sixteen=await Benchmark(16,"Fixed"),many=await Benchmark(128,"Fixed"),adaptive=await Benchmark(128,"Adaptive");
  Check(sixteen>one*4&&many>sixteen*1.3,"parallel scaling under a per-connection server cap");
  Check(adaptive>one*4,"adaptive ramp improves actual throughput over one connection");
  Console.WriteLine("ALL "+pass+" TURBO CHECKS PASSED");
 }
 static int Main(string[] args){try{(args.Contains("--crash-worker")?CrashWorker():MainAsync()).GetAwaiter().GetResult();return 0;}catch(Exception e){Console.Error.WriteLine(e);return 1;}}
}
