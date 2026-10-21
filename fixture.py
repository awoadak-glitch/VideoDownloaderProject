import http.server, socketserver, time, re, socket, json, hashlib, pathlib, threading
DATA=bytes(range(256))*98304
VERSION=1
DROPPED=False
RANGES=0
BUSY=0
BIG=bytes(range(256))*1048576
LOCK=threading.Lock()
class Server(socketserver.ThreadingMixIn,http.server.HTTPServer): daemon_threads=True; request_queue_size=256
class Handler(http.server.BaseHTTPRequestHandler):
 protocol_version="HTTP/1.1"
 def log_message(self,*args): pass
 def do_GET(self):
  global VERSION,DROPPED,RANGES,BUSY
  path=self.path.split("?")[0]
  if path=="/redirect":
   self.send_response(302);self.send_header("Location","/cookie-data");self.send_header("Set-Cookie","downloadsid=test; Path=/");self.send_header("Content-Length","0");self.end_headers();return
  if path=="/cookie-data" and "downloadsid=test" not in self.headers.get("Cookie",""):
   self.send_response(403);self.send_header("Content-Length","0");self.end_headers();return
  if path=="/mutate":
   VERSION+=1;self.send_response(200);self.send_header("Content-Length","2");self.end_headers();self.wfile.write(b"OK");return
  if path=="/stats":
   b=json.dumps({"ranges":RANGES,"dropped":DROPPED}).encode();self.send_response(200);self.send_header("Content-Length",str(len(b)));self.end_headers();self.wfile.write(b);return
  if path=="/html":
   b=b"<html>login required</html>";self.send_response(200);self.send_header("Content-Type","text/html");self.send_header("Content-Length",str(len(b)));self.end_headers();self.wfile.write(b);return
  data=BIG if path in ("/benchmark","/adaptive","/many") else DATA if path not in ("/small","/bad") else DATA[:1048576]
  if path=="/mutable" and VERSION>1:data=bytes([5])*len(data)
  etag='"v%d"'%(VERSION if path=="/mutable" else 1)
  if self.headers.get("If-Match") not in (None,etag):
   self.send_response(412);self.send_header("Content-Length","0");self.end_headers();return
  start,end=0,len(data)-1
  rang=self.headers.get("Range") if path!="/norange" else None
  if rang:
   m=re.match(r"bytes=(\d+)-(\d*)",rang)
   if m:
    start=int(m[1]);end=min(end,int(m[2])) if m[2] else end
    if start>=len(data):
     self.send_response(416);self.send_header("Content-Range","bytes */%d"%len(data));self.send_header("Content-Length","0");self.end_headers();return
    RANGES+=1
  if path=="/busy" and rang!="bytes=0-0":
   with LOCK:
    limited=BUSY<2
    BUSY+=1
   if limited:
    self.send_response(429);self.send_header("Content-Length","0");self.send_header("Retry-After","1");self.end_headers();return
  if path=="/flip" and rang!="bytes=0-0":etag='"changed"'
  self.send_response(206 if rang else 200);self.send_header("Content-Type","application/octet-stream");self.send_header("Content-Length",str(end-start+1));self.send_header("ETag",etag);self.send_header("Accept-Ranges","bytes" if rang else "none")
  if rang:self.send_header("Content-Range","bytes %d-%d/%d"%(start+(1 if path=="/wrong" and end-start>0 else 0),end,len(data)))
  self.send_header("Content-Disposition",'attachment; filename="original.bin"')
  self.end_headers()
  should_drop=path=="/drop" and end-start>1024 and not DROPPED
  if should_drop:DROPPED=True
  try:
   for pos in range(start,end+1,65536):
    self.wfile.write(data[pos:min(pos+65536,end+1)]);self.wfile.flush()
    if should_drop and pos-start>=262144:self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
    if end-start>1024:time.sleep((min(65536,end+1-pos)/(300*1024)) if path in ("/benchmark","/adaptive","/many") else .008)
  except (BrokenPipeError,ConnectionResetError,OSError):pass
pathlib.Path("/vercel/awr/test-payload.sha256").write_text(hashlib.sha256(DATA).hexdigest())
pathlib.Path("/vercel/awr/test-small.sha256").write_text(hashlib.sha256(DATA[:1048576]).hexdigest())
Server(("127.0.0.1",18941),Handler).serve_forever()

