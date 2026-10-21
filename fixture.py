import http.server, socketserver, time, re, socket, json, hashlib, pathlib, threading, random
# Distinct bytes across every segment expose misplaced/overlapping range writes.
DATA=random.Random(20261021).randbytes(24*1024*1024)
VERSION=1
DROPPED=False
RANGES=0
BUSY=0
CAP_ACTIVE=0
CAP_PEAK=0
CAP_REJECT=0
BIG=bytes(range(256))*1048576
LOCK=threading.Lock()
class Server(socketserver.ThreadingMixIn,http.server.HTTPServer): daemon_threads=True; request_queue_size=256
class Handler(http.server.BaseHTTPRequestHandler):
 protocol_version="HTTP/1.1"
 def log_message(self,*args): pass
 def do_GET(self):
  global VERSION,DROPPED,RANGES,BUSY,CAP_ACTIVE,CAP_PEAK,CAP_REJECT
  path=self.path.split("?")[0]
  if path=="/redirect":
   self.send_response(302);self.send_header("Location","/cookie-data");self.send_header("Set-Cookie","downloadsid=test; Path=/");self.send_header("Content-Length","0");self.end_headers();return
  if path=="/cookie-data" and "downloadsid=test" not in self.headers.get("Cookie",""):
   self.send_response(403);self.send_header("Content-Length","0");self.end_headers();return
  if path=="/mutate":
   VERSION+=1;self.send_response(200);self.send_header("Content-Length","2");self.end_headers();self.wfile.write(b"OK");return
  if path=="/stats":
   b=json.dumps({"ranges":RANGES,"dropped":DROPPED,"cap_active":CAP_ACTIVE,"cap_peak":CAP_PEAK,"cap_rejected":CAP_REJECT}).encode();self.send_response(200);self.send_header("Content-Length",str(len(b)));self.end_headers();self.wfile.write(b);return
  if path=="/html":
   b=b"<html>login required</html>";self.send_response(200);self.send_header("Content-Type","text/html");self.send_header("Content-Length",str(len(b)));self.end_headers();self.wfile.write(b);return
  data=BIG if path in ("/benchmark","/adaptive","/many") else DATA if path not in ("/small","/bad","/repeated") else DATA[:1048576]
  if path=="/mutable" and VERSION>1:data=bytes([5])*len(data)
  etag='"v%d"'%(VERSION if path=="/mutable" else 1)
  if self.headers.get("If-Match") not in (None,etag):
   self.send_response(412);self.send_header("Content-Length","0");self.end_headers();return
  start,end=0,len(data)-1
  rang=self.headers.get("Range") if path!="/norange" else None
  if path=="/probe-denied" and rang:
   self.send_response(403);self.send_header("Content-Length","0");self.end_headers();return
  if path=="/ignore-after-probe" and rang!="bytes=0-0":rang=None
  if rang:
   m=re.match(r"bytes=(\d+)-(\d*)",rang)
   if m:
    start=int(m[1]);end=min(end,int(m[2])) if m[2] else end
    if start>=len(data):
     self.send_response(416);self.send_header("Content-Range","bytes */%d"%len(data));self.send_header("Content-Length","0");self.end_headers();return
    RANGES+=1
  if path=="/delayed-headers" and rang!="bytes=0-0":time.sleep(4)
  if path=="/busy" and rang!="bytes=0-0":
   with LOCK:
    limited=BUSY<2
    BUSY+=1
   if limited:
    self.send_response(429);self.send_header("Content-Length","0");self.send_header("Retry-After","1");self.end_headers();return
  if path=="/flip" and rang!="bytes=0-0":etag='"changed"'
  capped=path=="/cap8" and rang!="bytes=0-0"
  if capped:
   with LOCK:
    rejected=CAP_ACTIVE>=8
    if rejected:CAP_REJECT+=1
    else:CAP_ACTIVE+=1;CAP_PEAK=max(CAP_PEAK,CAP_ACTIVE)
   if rejected:
    self.send_response(403);self.send_header("Content-Length","0");self.end_headers();return
  should_drop=(path=="/drop" and end-start>1024 and not DROPPED) or (path=="/repeated" and end-start>=65536)
  if should_drop:DROPPED=True
  try:
   self.send_response(206 if rang else 200);self.send_header("Content-Type","application/octet-stream");self.send_header("Content-Length",str(end-start+1))
   if path!="/omit-validator" or rang=="bytes=0-0":self.send_header("ETag",etag)
   self.send_header("Accept-Ranges","bytes" if rang else "none")
   if rang:self.send_header("Content-Range","bytes %d-%d/%d"%(start+(1 if path=="/wrong" and end-start>0 else 0),end,len(data)))
   self.send_header("Content-Disposition",'attachment; filename="original.bin"');self.end_headers()
   for pos in range(start,end+1,65536):
    self.wfile.write(data[pos:min(pos+65536,end+1)]);self.wfile.flush()
    if should_drop and pos-start>=(0 if path=="/repeated" else 262144):self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
    if end-start>1024:time.sleep((min(65536,end+1-pos)/(300*1024)) if path in ("/benchmark","/adaptive","/many","/cap8") else (.2 if path=="/tail" and start==0 else .008))
  except (BrokenPipeError,ConnectionResetError,OSError):pass
  finally:
   if capped:
    with LOCK:CAP_ACTIVE-=1
pathlib.Path("/vercel/awr/test-payload.sha256").write_text(hashlib.sha256(DATA).hexdigest())
pathlib.Path("/vercel/awr/test-small.sha256").write_text(hashlib.sha256(DATA[:1048576]).hexdigest())
Server(("127.0.0.1",18941),Handler).serve_forever()

