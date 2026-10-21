using System;
using System.IO;
using System.Linq;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;
using System.Threading;
using System.Threading.Tasks;
using System.Diagnostics;
using System.Reflection;
using Microsoft.Win32;

[assembly:AssemblyTitle("AWR Download Next")]
[assembly:AssemblyProduct("AWR Download Next")]
[assembly:AssemblyVersion("2.1.0.0")]
[assembly:AssemblyFileVersion("2.1.0.0")]
namespace AwrNext {
 static class Program {
  public static string EnginePath { get { return Environment.GetEnvironmentVariable("AWR_TEST_ENGINE")??Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"Engine","aria2c.exe"); } }
  [STAThread] static void Main(string[] args){
   System.Net.ServicePointManager.SecurityProtocol=System.Net.SecurityProtocolType.Tls12;
   bool first;using(var mutex=new Mutex(true,@"Local\AWRDownloadNext20",out first)){
    Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);
    if(!first){MessageBox.Show("البرنامج مفتوح بالفعل. افتحه من شريط المهام.","AWR Download Next");return;}
    try {Application.Run(new MainForm(args));}catch(Exception e){MessageBox.Show(e.Message,"AWR Download Next",MessageBoxButtons.OK,MessageBoxIcon.Error);}
   }
  }
 }
 static class Theme {
  public static Color Bg=Color.FromArgb(16,20,30),Panel=Color.FromArgb(25,31,44),Line=Color.FromArgb(43,53,73),Text=Color.FromArgb(231,237,247),Muted=Color.FromArgb(155,169,191),Accent=Color.FromArgb(66,214,164);
  public static Button Button(string t,Action a,bool accent=false){
   var b=new Button{Text=t,Height=38,Width=130,Margin=new Padding(5),FlatStyle=FlatStyle.Flat,BackColor=accent?Accent:Panel,ForeColor=accent?Bg:Text,Cursor=Cursors.Hand};
   b.FlatAppearance.BorderColor=Line;b.Click+=(s,e)=>a();return b;
  }
  public static Label Label(string t,int size=10,bool bold=false){return new Label{Text=t,AutoSize=true,ForeColor=Text,Font=new Font("Segoe UI",size,bold?FontStyle.Bold:FontStyle.Regular),Margin=new Padding(8)};}
  public static void Style(Form f){f.BackColor=Bg;f.ForeColor=Text;f.Font=new Font("Segoe UI",10);f.StartPosition=FormStartPosition.CenterParent;f.AutoScaleMode=AutoScaleMode.Dpi;}
  public static TextBox Input(string t=""){return new TextBox{Text=t,BackColor=Panel,ForeColor=Text,BorderStyle=BorderStyle.FixedSingle,Dock=DockStyle.Fill,Margin=new Padding(7),RightToLeft=RightToLeft.No};}
  public static NumericUpDown Number(int value,int min,int max){return new NumericUpDown{Value=value,Minimum=min,Maximum=max,BackColor=Panel,ForeColor=Text,Width=100,Margin=new Padding(7)};}
  public static void Field(TableLayoutPanel t,string label,Control c,int row){t.Controls.Add(Label(label),1,row);t.Controls.Add(c,0,row);}
 }
 public class MainForm:Form {
  Manager manager; DataGridView grid; Label stats,detail,banner; TextBox search; ComboBox filter; NotifyIcon tray;
  System.Windows.Forms.Timer timer=new System.Windows.Forms.Timer{Interval=500}; bool ready=false,closing=false,finishedClosing=false,refreshing=false; string[] startup;
  HashSet<string> seenComplete=new HashSet<string>();
  public MainForm(string[] args){
   startup=args;Theme.Style(this);Text="AWR Download Next 2.1 • Turbo";Width=1240;Height=800;MinimumSize=new Size(1100,700);StartPosition=FormStartPosition.CenterScreen;
   try{Icon=System.Drawing.Icon.ExtractAssociatedIcon(Application.ExecutablePath);}catch{Icon=SystemIcons.Application;}
   var layout=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=1,RowCount=11,Padding=new Padding(20)};
   layout.RowStyles.Add(new RowStyle(SizeType.Absolute,82));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,48));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,50));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,46));layout.RowStyles.Add(new RowStyle(SizeType.Percent,100));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,112));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,36));Controls.Add(layout);
   var head=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2};head.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,60));head.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,40));
   var brand=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown};
   brand.Controls.Add(Theme.Label("AWR  /  NEXT TURBO",22,true));var tag=Theme.Label("اتصالات تكيفية • تنزيل مجزأ • استئناف محفوظ",10);tag.ForeColor=Theme.Muted;brand.Controls.Add(tag);head.Controls.Add(brand,0,0);
   stats=Theme.Label("جارٍ تشغيل محرك التنزيل…",12,true);stats.Dock=DockStyle.Fill;stats.TextAlign=ContentAlignment.MiddleRight;head.Controls.Add(stats,1,0);layout.Controls.Add(head,0,0);
   var bar=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.RightToLeft};
   bar.Controls.Add(Theme.Button("+  إضافة رابط",()=>Add(),true));
   bar.Controls.Add(Theme.Button("إضافة عدة روابط",()=>Batch()));
   bar.Controls.Add(Theme.Button("إيقاف الكل",()=>Run(async()=>{foreach(var j in manager.State.Jobs.ToArray())await manager.Pause(j);})));
   bar.Controls.Add(Theme.Button("استئناف الكل",()=>Run(async()=>{foreach(var j in manager.State.Jobs.ToArray())manager.Resume(j);await Task.CompletedTask;})));
   bar.Controls.Add(Theme.Button("الإعدادات",()=>Settings()));bar.Controls.Add(Theme.Button("تقرير السرعة",()=>Diagnostics()));layout.Controls.Add(bar,0,1);
   var actions=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.RightToLeft};
   actions.Controls.Add(Theme.Button("استئناف المحدد",()=>SelectedRun(async j=>{manager.Resume(j);await Task.CompletedTask;}),true));
   actions.Controls.Add(Theme.Button("إيقاف مؤقت",()=>SelectedRun(j=>manager.Pause(j))));
   actions.Controls.Add(Theme.Button("تحديث الرابط",()=>UpdateLink()));
   actions.Controls.Add(Theme.Button("إعادة من البداية",()=>Restart()));
   actions.Controls.Add(Theme.Button("فتح المجلد",()=>OpenFolder()));
   actions.Controls.Add(Theme.Button("حذف من القائمة",()=>Remove()));layout.Controls.Add(actions,0,2);
   var find=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=3};find.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));find.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,190));find.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,70));
   search=Theme.Input();search.TextChanged+=(s,e)=>RefreshJobs();filter=new ComboBox{Dock=DockStyle.Fill,DropDownStyle=ComboBoxStyle.DropDownList,BackColor=Theme.Panel,ForeColor=Theme.Text,Margin=new Padding(6)};filter.Items.AddRange(new object[]{"جميع التحميلات","قيد التنزيل","متوقفة / انتظار","مكتملة","تحتاج مراجعة"});filter.SelectedIndex=0;filter.SelectedIndexChanged+=(s,e)=>RefreshJobs();find.Controls.Add(search,0,0);find.Controls.Add(filter,1,0);find.Controls.Add(Theme.Label("بحث"),2,0);layout.Controls.Add(find,0,3);
   grid=new DataGridView{Dock=DockStyle.Fill,BackgroundColor=Theme.Panel,BorderStyle=BorderStyle.None,AllowUserToAddRows=false,AllowUserToDeleteRows=false,ReadOnly=true,MultiSelect=false,SelectionMode=DataGridViewSelectionMode.FullRowSelect,RowHeadersVisible=false,EnableHeadersVisualStyles=false,AutoSizeColumnsMode=DataGridViewAutoSizeColumnsMode.Fill,GridColor=Theme.Line,CellBorderStyle=DataGridViewCellBorderStyle.SingleHorizontal,ColumnHeadersHeight=40,RowTemplate={Height=46}};
   grid.ColumnHeadersDefaultCellStyle=new DataGridViewCellStyle{BackColor=Theme.Bg,ForeColor=Theme.Muted,Font=new Font("Segoe UI",10)};
   grid.DefaultCellStyle=new DataGridViewCellStyle{BackColor=Theme.Panel,ForeColor=Theme.Text,SelectionBackColor=Color.FromArgb(36,68,72),SelectionForeColor=Color.White,Padding=new Padding(6)};
   string[] titles={"اسم الملف","الحجم","التقدم","السرعة","المتبقي","الاتصالات","الحالة"};foreach(var title in titles)grid.Columns.Add(title,title);
   grid.Columns[0].FillWeight=270;grid.Columns[6].FillWeight=150;grid.SelectionChanged+=(s,e)=>ShowDetail();grid.CellDoubleClick+=(s,e)=>OpenFolder();grid.CellPainting+=PaintProgress;layout.Controls.Add(grid,0,4);
   detail=new Label{Dock=DockStyle.Fill,BackColor=Theme.Panel,Padding=new Padding(12),ForeColor=Theme.Muted,AutoEllipsis=true,Font=new Font("Segoe UI",9)};layout.Controls.Add(detail,0,5);
   banner=new Label{Dock=DockStyle.Fill,ForeColor=Theme.Muted,TextAlign=ContentAlignment.MiddleRight,Text="السرعة مرتبطة بالخط والخادم. الاستئناف يتطلب دعم الخادم وثبات الملف."};layout.Controls.Add(banner,0,6);
   tray=new NotifyIcon{Text="AWR Download Next",Icon=SystemIcons.Application,Visible=true};tray.DoubleClick+=(s,e)=>{Show();WindowState=FormWindowState.Normal;Activate();};
   var menu=new ContextMenuStrip();menu.Items.Add("فتح",null,(s,e)=>{Show();WindowState=FormWindowState.Normal;Activate();});menu.Items.Add("خروج وحفظ",null,(s,e)=>Close());tray.ContextMenuStrip=menu;
   timer.Tick+=async(s,e)=>{if(!ready||closing)return;try{await manager.Tick();}catch(Exception x){banner.Text=x.Message;}};
   Shown+=async(s,e)=>await Initialize();FormClosing+=OnClosingRequested;
  }
  async Task Initialize(){
   try{
    string dir=Environment.GetEnvironmentVariable("AWR_TEST_DATA")??Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"AWR","DownloadNext");
    manager=new Manager(dir);foreach(var j in manager.State.Jobs.Where(x=>x.Status=="Complete"))seenComplete.Add(j.Id);
    manager.Changed+=RefreshJobs;
    await manager.Engine.Start(Program.EnginePath,dir);await manager.ApplySettings();ready=true;timer.Start();RefreshJobs();
    if(startup.Contains("--smoke")){await Task.Delay(1800);string shot=Environment.GetEnvironmentVariable("AWR_TEST_SCREENSHOT");if(shot!=null){using(var bmp=new Bitmap(Width,Height)){DrawToBitmap(bmp,new Rectangle(0,0,Width,Height));bmp.Save(shot);}
      using(var settings=new SettingsDialog(manager.State.Settings)){settings.Show(this);await Task.Delay(200);using(var bitmap=new Bitmap(settings.Width,settings.Height)){settings.DrawToBitmap(bitmap,new Rectangle(0,0,settings.Width,settings.Height));bitmap.Save(Path.Combine(Path.GetDirectoryName(shot),"ui-settings.png"));}settings.Close();}
      using(var add=new AddDialog(manager.State.Settings)){add.Show(this);await Task.Delay(200);using(var bitmap=new Bitmap(add.Width,add.Height)){add.DrawToBitmap(bitmap,new Rectangle(0,0,add.Width,add.Height));bitmap.Save(Path.Combine(Path.GetDirectoryName(shot),"ui-add.png"));}add.Close();}
     }Close();}
   }catch(Exception e){banner.Text="تعذر التشغيل: "+e.Message;MessageBox.Show(this,e.Message,"تعذر تشغيل البرنامج");}
  }
  void Run(Func<Task> action){if(!ready||closing)return;RunCore(action);}
  async void RunCore(Func<Task> action){try{await action();}catch(Exception e){MessageBox.Show(this,e.Message,"AWR Download Next",MessageBoxButtons.OK,MessageBoxIcon.Warning);}}
  DownloadJob Selected(){return grid.SelectedRows.Count==0?null:grid.SelectedRows[0].Tag as DownloadJob;}
  void SelectedRun(Func<DownloadJob,Task> a){var j=Selected();if(j!=null)Run(()=>a(j));}
  void Add(){if(!ready)return;using(var d=new AddDialog(manager.State.Settings)){if(d.ShowDialog(this)==DialogResult.OK)Run(async()=>{manager.Enqueue(d.Url,d.Folder,d.FileName,d.Connections,d.Hash,d.Referer,d.Cookie);await manager.Tick();});}}
  void Batch(){
   if(!ready)return;
   using(var f=new Form{Text="إضافة عدة روابط",Width=720,Height=460}){
    Theme.Style(f);var box=Theme.Input();box.Multiline=true;box.ScrollBars=ScrollBars.Vertical;box.Dock=DockStyle.Fill;box.AcceptsReturn=true;
    var b=Theme.Button("إضافة الروابط",()=>{f.DialogResult=DialogResult.OK;},true);b.Dock=DockStyle.Bottom;
    var label=Theme.Label("ضع رابط تنزيل مباشر واحدًا في كل سطر.");label.Dock=DockStyle.Top;f.Controls.Add(box);f.Controls.Add(b);f.Controls.Add(label);
    if(f.ShowDialog(this)==DialogResult.OK)Run(async()=>{
     var urls=box.Text.Split(new[]{'\r','\n'},StringSplitOptions.RemoveEmptyEntries).Select(x=>x.Trim()).Where(x=>x!="").ToArray();
     if(urls.Length>200)throw new Exception("Maximum 200 links per batch.");
     if(urls.Any(x=>!Util.ValidUrl(x)))throw new Exception("One or more URLs are invalid. No links were added.");
     foreach(var url in urls)manager.Enqueue(url,manager.State.Settings.Folder,"",manager.State.Settings.Connections);await manager.Tick();
    });
   }
  }
  void UpdateLink(){var j=Selected();if(j==null)return;string value=Prompt("تحديث رابط نفس الملف",j.Url);if(value!=null)Run(()=>manager.UpdateUrl(j,value));}
  void Restart(){var j=Selected();if(j!=null&&MessageBox.Show(this,"سيتم حذف الأجزاء المؤقتة لهذا التحميل والبدء من الصفر. متابعة؟","إعادة التحميل",MessageBoxButtons.YesNo)==DialogResult.Yes)Run(()=>manager.Restart(j));}
  void Remove(){var j=Selected();if(j!=null&&MessageBox.Show(this,"حذف التحميل وأجزائه المؤقتة؟ الملفات المكتملة ستبقى محفوظة.","حذف",MessageBoxButtons.YesNo)==DialogResult.Yes)Run(()=>manager.Remove(j));}
  void OpenFolder(){var j=Selected();if(j==null)return;Run(async()=>{Process.Start(new ProcessStartInfo{FileName=j.Folder,UseShellExecute=true});await Task.CompletedTask;});}
  void Settings(){
   if(!ready)return;
   using(var d=new SettingsDialog(manager.State.Settings)){if(d.ShowDialog(this)==DialogResult.OK)Run(async()=>{manager.State.Settings=d.Result;await manager.ApplySettings();RefreshJobs();});}
  }
  void Diagnostics(){
   var j=Selected();if(j==null){MessageBox.Show(this,"اختر تحميلًا أولًا.");return;}
   using(var d=new SaveFileDialog{Filter="Text report|*.txt",FileName="AWR-speed-report.txt"})if(d.ShowDialog(this)==DialogResult.OK){
    var report="AWR Download Next 2.1\r\nTime UTC: "+DateTime.UtcNow.ToString("o")+"\r\nHost: "+new Uri(j.Url).Host+"\r\nStatus: "+j.Status+"\r\nEngine / mode: "+j.Backend+" / "+j.EngineMode+"\r\nRange support: "+j.Range+"\r\nActive / adaptive target / ceiling: "+j.ActiveConnections+" / "+j.TargetConnections+" / "+j.Connections+"\r\nPeak active connections: "+j.PeakConnections+"\r\nRetries: "+j.RetryCount+"\r\nCurrent speed bytes/s: "+j.Speed+"\r\nCurrent speed Mbps: "+(j.Speed*8/1000000.0).ToString("0.00")+"\r\nConfigured line Mbps (not a speed test): "+j.LineMbps+"\r\nApplication limit KiB/s: "+manager.State.Settings.LimitKiB+"\r\nSystem proxy: "+j.SystemProxy+"\r\nCompleted / total bytes: "+j.Done+" / "+j.Size+"\r\nExpected SHA256 supplied: "+(j.ExpectedHash!="")+"\r\nEngine note: "+j.EngineNote+"\r\n";
    File.WriteAllText(d.FileName,report,new System.Text.UTF8Encoding(true));MessageBox.Show(this,"تم حفظ التقرير دون الرابط الكامل أو كلمات المرور.");
   }
  }
  public static string Prompt(string title,string value){
   using(var f=new Form{Text=title,Width=750,Height=180}){Theme.Style(f);var t=Theme.Input(value);t.Dock=DockStyle.Top;var b=Theme.Button("حفظ",()=>f.DialogResult=DialogResult.OK,true);b.Dock=DockStyle.Bottom;f.Controls.Add(t);f.Controls.Add(b);return f.ShowDialog()==DialogResult.OK?t.Text.Trim():null;}
  }
  string Status(string s){switch(s){case"Queued":return"في الانتظار";case"Checking":return"فحص الرابط";case"Downloading":return"جارٍ التنزيل";case"Paused":return"متوقف مؤقتًا";case"Verifying":return"فحص الملف";case"Complete":return"مكتمل";case"Error":return"تحتاج مراجعة";default:return s;}}
  bool VisibleJob(DownloadJob j){
   if(search.Text!=""&&j.Name.IndexOf(search.Text,StringComparison.OrdinalIgnoreCase)<0)return false;
   switch(filter.SelectedIndex){case 1:return j.Status=="Downloading"||j.Status=="Checking"||j.Status=="Verifying";case 2:return j.Status=="Queued"||j.Status=="Paused";case 3:return j.Status=="Complete";case 4:return j.Status=="Error";default:return true;}
  }
  void RefreshJobs(){
   if(grid==null||manager==null||refreshing||IsDisposed)return;
   if(InvokeRequired){BeginInvoke((Action)RefreshJobs);return;}refreshing=true;
   try{
    string selected=Selected()==null?"":Selected().Id;var list=manager.State.Jobs.Where(VisibleJob).ToArray();
    bool rebuild=grid.Rows.Count!=list.Length;
    if(!rebuild)for(int i=0;i<list.Length;i++)if(((DownloadJob)grid.Rows[i].Tag).Id!=list[i].Id){rebuild=true;break;}
    if(rebuild){grid.Rows.Clear();foreach(var j in list){int ix=grid.Rows.Add();grid.Rows[ix].Tag=j;if(j.Id==selected)grid.Rows[ix].Selected=true;}}
    for(int i=0;i<list.Length;i++){
     var j=list[i];string eta=j.Speed>0&&j.Size>0?TimeSpan.FromSeconds(Math.Max(0,(j.Size-j.Done)/j.Speed)).ToString(@"hh\:mm\:ss"):"—";
     var row=grid.Rows[i];row.Cells[0].Value=j.Name;row.Cells[1].Value=Util.Bytes(j.Size);row.Cells[2].Value=j.Size>0?Math.Min(100,100.0*j.Done/j.Size).ToString("0.0")+"%":(j.Status=="Complete"?"100%":"—");
     row.Cells[3].Value=j.Speed>0?Util.Bytes(j.Speed)+"/s":"—";row.Cells[4].Value=eta;row.Cells[5].Value=j.ActiveConnections+" / "+(j.Backend=="aria2"?Math.Min(16,j.Connections):j.Connections);row.Cells[6].Value=Status(j.Status);
     row.Cells[6].Style.ForeColor=j.Status=="Complete"?Theme.Accent:(j.Status=="Error"?Color.Salmon:Theme.Text);
    }
    long speed=manager.State.Jobs.Sum(j=>j.Speed);int active=manager.State.Jobs.Count(j=>j.Status=="Downloading");
    double mbps=speed*8/1000000.0;stats.Text=Util.Bytes(speed)+"/s  •  "+mbps.ToString("0.0")+" Mbps\n"+(manager.State.Settings.LineMbps>0?(100*mbps/manager.State.Settings.LineMbps).ToString("0")+"% من خط "+manager.State.Settings.LineMbps+" Mbps  •  ":"")+active+" تحميل نشط";
    foreach(var j in manager.State.Jobs.Where(j=>j.Status=="Complete"&&!seenComplete.Contains(j.Id))){seenComplete.Add(j.Id);tray.ShowBalloonTip(3000,"اكتمل التنزيل",j.Name,ToolTipIcon.Info);}
    banner.Text=manager.State.Settings.StartAt>DateTime.Now?"بدء الطابور: "+manager.State.Settings.StartAt.ToString("yyyy-MM-dd HH:mm")+" — أبقِ البرنامج مفتوحًا":"الاستئناف يتطلب دعم الخادم. فحص البصمة المرجعية متاح عند إضافة الملف.";
    ShowDetail();
   }finally{refreshing=false;}
  }
  void ShowDetail(){
   if(detail==null)return;var j=Selected();detail.Text=j==null?"Turbo يزيد الاتصالات تدريجيًا بحسب الأداء حتى السقف المحدد. أضف رابطًا مباشرًا للبدء.":j.Name+"  •  "+j.Backend+" / "+j.EngineMode+"\n"+Util.Bytes(j.Done)+" / "+Util.Bytes(j.Size)+"    •    "+(j.Range?"يدعم التنزيل المجزأ والاستئناف":"دعم الاستئناف غير متاح / لم يفحص بعد")+"  •  إعادة المحاولة: "+j.RetryCount+"\n"+(j.Message!=""?j.Message:j.EngineNote)+"\n"+(j.ActualHash!=""?"SHA-256: "+j.ActualHash:j.Target);
  }
  void PaintProgress(object sender,DataGridViewCellPaintingEventArgs e){
   if(e.RowIndex<0||e.ColumnIndex!=2)return;var j=grid.Rows[e.RowIndex].Tag as DownloadJob;if(j==null)return;
   e.PaintBackground(e.CellBounds,true);float ratio=j.Size>0?(float)Math.Min(1,(double)j.Done/j.Size):0;
   var rect=new Rectangle(e.CellBounds.X+8,e.CellBounds.Bottom-9,e.CellBounds.Width-16,4);
   using(var b=new SolidBrush(Theme.Line))e.Graphics.FillRectangle(b,rect);
   rect.Width=(int)(rect.Width*ratio);using(var b=new SolidBrush(Theme.Accent))e.Graphics.FillRectangle(b,rect);
   TextRenderer.DrawText(e.Graphics,Convert.ToString(e.FormattedValue),grid.Font,new Rectangle(e.CellBounds.X+7,e.CellBounds.Y+4,e.CellBounds.Width-14,28),Theme.Text,TextFormatFlags.VerticalCenter|TextFormatFlags.HorizontalCenter);e.Handled=true;
  }
  async void OnClosingRequested(object sender,FormClosingEventArgs e){
   if(finishedClosing)return;e.Cancel=true;if(closing)return;closing=true;timer.Stop();Enabled=false;stats.Text="حفظ التحميلات…";
   try{if(manager!=null)await manager.Close();}catch(Exception x){MessageBox.Show(x.Message,"تعذر حفظ بعض البيانات");}
   tray.Visible=false;tray.Dispose();finishedClosing=true;Close();
  }
 }
 public class AddDialog:Form {
  TextBox url,name,folder,hash,referrer,cookie;NumericUpDown connections;
  public string Url{get{return url.Text.Trim();}}public string FileName{get{return name.Text.Trim();}}public string Folder{get{return folder.Text.Trim();}}public string Hash{get{return hash.Text.Trim();}}public string Referer{get{return referrer.Text.Trim();}}public string Cookie{get{return cookie.Text.Trim();}}public int Connections{get{return (int)connections.Value;}}
  public AddDialog(Settings settings){
   Theme.Style(this);Text="إضافة تحميل جديد";Width=850;Height=590;MinimumSize=new Size(750,590);MaximizeBox=false;MinimizeBox=false;
   var t=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2,RowCount=10,Padding=new Padding(18)};t.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,75));t.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,25));
   for(int i=0;i<8;i++)t.RowStyles.Add(new RowStyle(SizeType.Absolute,45));t.RowStyles.Add(new RowStyle(SizeType.Percent,100));t.RowStyles.Add(new RowStyle(SizeType.Absolute,50));Controls.Add(t);
   url=Theme.Input();name=Theme.Input();folder=Theme.Input(settings.Folder);hash=Theme.Input();referrer=Theme.Input();cookie=Theme.Input();cookie.UseSystemPasswordChar=true;connections=Theme.Number(settings.Connections,1,128);
   try{if(Clipboard.ContainsText()){string s=Clipboard.GetText().Trim();if(Util.ValidUrl(s))url.Text=s;}}catch{}
   Theme.Field(t,"رابط الملف المباشر",url,0);Theme.Field(t,"اسم الملف (اختياري)",name,1);
   var dest=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2};dest.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));dest.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,55));dest.Controls.Add(folder,0,0);var browse=Theme.Button("…",()=>{using(var d=new FolderBrowserDialog{SelectedPath=folder.Text})if(d.ShowDialog()==DialogResult.OK)folder.Text=d.SelectedPath;});browse.Width=42;dest.Controls.Add(browse,1,0);Theme.Field(t,"مجلد الحفظ",dest,2);
   Theme.Field(t,"سقف الاتصالات",connections,3);Theme.Field(t,"SHA-256 (اختياري)",hash,4);Theme.Field(t,"Referer (اختياري)",referrer,5);Theme.Field(t,"Cookie (اختياري)",cookie,6);
   var hint=new Label{Dock=DockStyle.Fill,ForeColor=Theme.Muted,Text="الوضع: "+settings.EngineMode+". Turbo يبدأ بعدد قليل ويزيد عندما تتحسن السرعة.\n128 سقف للاتصالات، وليس عددًا يفتح دائمًا. وضع التوافق أقصاه 16.\nالبصمة المرجعية تؤكد المطابقة للأصل. استخدم رابط الملف المباشر.",Padding=new Padding(6)};t.Controls.Add(hint,0,7);t.SetColumnSpan(hint,2);t.SetRowSpan(hint,2);
   var buttons=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.RightToLeft};buttons.Controls.Add(Theme.Button("بدء التنزيل",()=>{if(!Util.ValidUrl(Url)){MessageBox.Show("أدخل رابط HTTP أو HTTPS مباشرًا.");return;}if(Folder==""){MessageBox.Show("اختر مجلد الحفظ.");return;}DialogResult=DialogResult.OK;},true));buttons.Controls.Add(Theme.Button("إلغاء",()=>DialogResult=DialogResult.Cancel));t.Controls.Add(buttons,0,9);t.SetColumnSpan(buttons,2);
  }
 }
 public class SettingsDialog:Form {
  public Settings Result;public SettingsDialog(Settings old){
   Theme.Style(this);Text="إعدادات Turbo • تطبق على التحميلات الجديدة";Width=850;Height=660;MaximizeBox=false;MinimizeBox=false;
   var t=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2,RowCount=11,Padding=new Padding(20)};t.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,60));t.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,40));for(int i=0;i<11;i++)t.RowStyles.Add(new RowStyle(SizeType.Absolute,51));Controls.Add(t);
   var folder=Theme.Input(old.Folder);var con=Theme.Number(old.Connections,1,128);var simultaneous=Theme.Number(old.Concurrent,1,8);var limit=Theme.Number(old.LimitKiB,0,2000000);var line=Theme.Number(old.LineMbps,0,100000);
   var mode=new ComboBox{DropDownStyle=ComboBoxStyle.DropDownList,Dock=DockStyle.Fill,Margin=new Padding(7)};mode.Items.AddRange(new object[]{"Turbo • تكيفي (موصى به)","Turbo • سقف ثابت","aria2 • توافق"});mode.SelectedIndex=old.EngineMode=="Fixed"?1:old.EngineMode=="Compatibility"?2:0;
   var proxy=new CheckBox{Text="استخدام إعدادات بروكسي Windows",Checked=old.SystemProxy,AutoSize=true,ForeColor=Theme.Text};
   var schedule=new CheckBox{Text="تفعيل وقت بدء الطابور",Checked=old.StartAt>DateTime.Now,AutoSize=true,ForeColor=Theme.Text};var at=new DateTimePicker{Format=DateTimePickerFormat.Custom,CustomFormat="yyyy-MM-dd HH:mm",Value=old.StartAt>DateTime.Now?old.StartAt:DateTime.Now.AddHours(1),Width=230};
   Theme.Field(t,"مجلد الحفظ الافتراضي",folder,0);Theme.Field(t,"اتصالات لكل ملف جديد",con,1);Theme.Field(t,"الملفات المتزامنة",simultaneous,2);Theme.Field(t,"حد السرعة KB/s (0 = مفتوح)",limit,3);t.Controls.Add(schedule,0,4);Theme.Field(t,"بدء الملفات المنتظرة",at,5);
   Theme.Field(t,"المحرك",mode,6);Theme.Field(t,"سرعة خطك Mbps (للمقارنة)",line,7);t.Controls.Add(proxy,0,8);t.SetColumnSpan(proxy,2);
   var fast=Theme.Button("إعداد خط 180 Mbps",()=>{mode.SelectedIndex=0;con.Value=128;simultaneous.Value=1;limit.Value=0;line.Value=180;},true);fast.Width=230;t.Controls.Add(fast,0,9);
   var b=Theme.Button("حفظ الإعدادات",()=>{try{Directory.CreateDirectory(folder.Text);Result=new Settings{Folder=Path.GetFullPath(folder.Text),Connections=(int)con.Value,Concurrent=(int)simultaneous.Value,LimitKiB=(int)limit.Value,StartAt=schedule.Checked?at.Value:DateTime.MinValue,LineMbps=(int)line.Value,EngineMode=mode.SelectedIndex==1?"Fixed":mode.SelectedIndex==2?"Compatibility":"Adaptive",SystemProxy=proxy.Checked};DialogResult=DialogResult.OK;}catch(Exception e){MessageBox.Show(e.Message);}},true);t.Controls.Add(b,0,10);
  }
 }
}

