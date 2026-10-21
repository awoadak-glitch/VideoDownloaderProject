using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Threading.Tasks;
using AwrNext;
class Tests {
 static string root="/vercel/awr/test",url="http://127.0.0.1:18941",sha=File.ReadAllText("/vercel/awr/test-payload.sha256").Trim(),small=File.ReadAllText("/vercel/awr/test-small.sha256").Trim();
 static int pass=0;
 static void Check(bool c,string what){if(!c)throw new Exception("FAIL: "+what);Console.WriteLine("PASS "+(++pass)+": "+what);}
 static async Task Until(Manager m,DownloadJob j,Func<bool> predicate,int seconds=45){
  var until=DateTime.UtcNow.AddSeconds(seconds);
  while(!predicate()&&DateTime.UtcNow<until){await m.Tick();await Task.Delay(100);}
  if(!predicate())throw new Exception("Timeout: "+j.Status+" "+j.Message);
 }
 static async Task<Manager> New(string name){
  var m=new Manager(Path.Combine(root,name,"state"));await m.Engine.Start("/usr/bin/aria2c",m.DataDir);m.State.Settings.Concurrent=1;m.State.Settings.EngineMode=Environment.GetEnvironmentVariable("AWR_TEST_MODE")??"Adaptive";await m.ApplySettings();return m;
 }
 static async Task MainAsync(){
  root+="-"+(Environment.GetEnvironmentVariable("AWR_TEST_MODE")??"Adaptive");Directory.CreateDirectory(root);Manager m=null;
  try {
   m=await New("resume");m.State.Settings.LimitKiB=1024;await m.ApplySettings();
   string dir=Path.Combine(root,"files");Directory.CreateDirectory(dir);File.WriteAllText(Path.Combine(dir,"test.bin"),"KEEP");
   var j=m.Enqueue(url+"/data",dir,"test.bin",8,sha);
   await Until(m,j,()=>j.Done>524288);
   Check(j.ActiveConnections>1,"real parallel HTTP range connections");
   await m.Pause(j);Check(j.Status=="Paused"&&File.Exists(j.Part+(j.Backend=="Turbo"?".awrmap":".aria2")),"pause writes resume map");
   long saved=j.Done;await m.Close();
   m=await New("resume");j=m.State.Jobs.Single();
   Check(j.Status=="Paused"&&j.Done>=saved,"history and partial progress survive app restart");
   m.State.Settings.LimitKiB=0;await m.ApplySettings();m.Resume(j);await Until(m,j,()=>j.Status=="Complete"||j.Status=="Error");
   Check(j.Status=="Complete","resume completes: "+j.Message);
   Check(Util.Hash(j.Target)==sha,"resumed bytes match reference SHA-256");
   Check(File.ReadAllText(Path.Combine(dir,"test.bin"))=="KEEP"&&j.Name=="test (1).bin","existing destination never overwritten");
   await m.Close();m=null;
   m=await New("changed");j=m.Enqueue(url+"/mutable",dir,"mutable.bin",8,sha);await Until(m,j,()=>j.Done>524288);await m.Pause(j);
   using(var c=new WebClient())c.DownloadString(url+"/mutate");
   m.Resume(j);await Until(m,j,()=>j.Status=="Error");
   Check(j.Message.Contains("changed")&&!File.Exists(j.Target),"changed ETag blocks unsafe resume");
   await m.Close();m=null;
   m=await New("norange");j=m.Enqueue(url+"/norange",dir,"single.bin",16,sha);await Until(m,j,()=>j.Done>524288);
   Check(!j.Range&&j.ActiveConnections<=1,"non-range server uses one connection");
   await m.Pause(j);m.Resume(j);await Until(m,j,()=>j.Status=="Error");
   Check(j.Message.Contains("does not support"),"non-resumable server refuses unsafe resume");
   await m.Restart(j);await Until(m,j,()=>j.Status=="Complete"||j.Status=="Error");
   Check(j.Status=="Complete"&&Util.Hash(j.Target)==sha,"clean restart works on non-range server");
   await m.Close();m=null;
   m=await New("hash");j=m.Enqueue(url+"/bad",dir,"bad.bin",8,new string('0',64));await Until(m,j,()=>j.Status=="Error");
   Check(j.Message.Contains("SHA-256 mismatch")&&!File.Exists(j.Target)&&File.Exists(j.Part),"bad checksum quarantines output");
   var html=m.Enqueue(url+"/html",dir,"login.zip",8);await Until(m,html,()=>html.Status=="Error");
   Check(html.Message.Contains("web page")&&!File.Exists(html.Target),"HTML login pages are not accepted as files");
   await m.Close();m=null;
   m=await New("drop");j=m.Enqueue(url+"/drop",dir,"drop.bin",8,sha);await Until(m,j,()=>j.Status=="Complete"||j.Status=="Error");
   Check(j.Status=="Complete"&&Util.Hash(j.Target)==sha,"interrupted connection retries to exact final bytes");
   await m.Close();m=null;
   m=await New("missing-map");j=m.Enqueue(url+"/data",dir,"missing.bin",8,sha);await Until(m,j,()=>j.Done>524288);await m.Pause(j);await m.Close();m=null;
   m=await New("missing-map");j=m.State.Jobs.Single();File.Delete(j.Part+(j.Backend=="Turbo"?".awrmap":".aria2"));m.Resume(j);await Until(m,j,()=>j.Status=="Error");
   Check(j.Message.Contains("map is missing"),"missing resume map cannot produce false completion");
   await m.Close();m=null;
   m=await New("race");j=m.Enqueue(url+"/small",dir,"race.bin",2,small);await m.Tick();await m.Pause(j);await Task.Delay(500);
   Check(j.Status=="Paused"&&j.Gid=="","pause during link probe prevents orphan transfer");
   await m.Close();m=null;
   Check(Util.SafeName("../CON.txt")=="_CON.txt"&&Util.SafeName("a:b?.exe")=="a_b_.exe","Windows filename and device-name sanitizing");
   m=await New("filename");var named=m.Enqueue(url+"/small",dir,"",8,small);await Until(m,named,()=>named.Status=="Complete"||named.Status=="Error");Check(named.Status=="Complete"&&named.Name=="original.bin","server filename is used when user leaves filename blank");await m.Close();m=null;
   Console.WriteLine("ALL "+pass+" CHECKS PASSED");
  } finally {if(m!=null)m.Close().GetAwaiter().GetResult();}
 }
 static int Main(){try{MainAsync().GetAwaiter().GetResult();return 0;}catch(Exception e){Console.Error.WriteLine(e);return 1;}}
}

