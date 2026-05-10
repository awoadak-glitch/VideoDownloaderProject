(function() {
  if (window.__AWR_STUDIO_LOADER__) return;
  window.__AWR_STUDIO_LOADER__ = true;

  function isMedia(u) {
    if (!u) return false;
    u = String(u);
    return u.includes(".mp4") || u.includes(".m3u8") || u.includes(".mpd") ||
           u.includes(".webm") || u.includes("videoplayback") || u.includes("mime=video");
  }

  function send(url, source) {
    try {
      if (!isMedia(url)) return;
      window.AWRBridge.onMediaFound(JSON.stringify({
        url: String(url),
        source: source || "js",
        title: document.title || "video",
        page: location.href
      }));
    } catch(e) {}
  }

  function scan() {
    try {
      document.querySelectorAll("video,source").forEach(function(v) {
        send(v.currentSrc, "video.currentSrc");
        send(v.src, "video.src");
      });
      document.querySelectorAll("a[href]").forEach(function(a) { send(a.href, "link"); });
      performance.getEntriesByType("resource").forEach(function(r) { send(r.name, "performance"); });
    } catch(e) {}
  }

  var oldFetch = window.fetch;
  if (oldFetch) {
    window.fetch = function(input, init) {
      var url = typeof input === "string" ? input : (input && input.url);
      send(url, "fetch");
      return oldFetch.apply(this, arguments).then(function(res) {
        try { send(res.url, "fetch.response"); } catch(e) {}
        return res;
      });
    };
  }

  var oldOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url) {
    send(url, "xhr");
    return oldOpen.apply(this, arguments);
  };

  scan();
  setInterval(scan, 1800);
})();
