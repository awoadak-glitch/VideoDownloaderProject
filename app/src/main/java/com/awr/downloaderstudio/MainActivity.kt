package com.awr.downloaderstudio

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var urlBox: EditText
    private lateinit var mediaCounter: TextView
    private lateinit var detectedText: TextView
    private lateinit var downloadsText: TextView
    private lateinit var downloader: Downloader
    private val media = linkedMapOf<String, MediaItem>()
    private val logs = mutableListOf<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(resources.getIdentifier("activity_main", "layout", packageName))

        webView = findViewById(resources.getIdentifier("webView", "id", packageName))
        urlBox = findViewById(resources.getIdentifier("urlBox", "id", packageName))
        mediaCounter = findViewById(resources.getIdentifier("mediaCounter", "id", packageName))
        detectedText = findViewById(resources.getIdentifier("detectedText", "id", packageName))
        downloadsText = findViewById(resources.getIdentifier("downloadsText", "id", packageName))
        downloader = Downloader(this) { log ->
            logs.add(0, log)
            refreshLogs()
        }

        setupNav()
        setupWebView()

        findViewById<Button>(resources.getIdentifier("goBtn", "id", packageName)).setOnClickListener {
            val raw = urlBox.text.toString().trim()
            val url = if (raw.startsWith("http")) raw else "https://$raw"
            webView.loadUrl(url)
            showPage("browser")
        }

        findViewById<Button>(resources.getIdentifier("downloadFloat", "id", packageName)).setOnClickListener {
            showMediaDialog()
        }

        findViewById<Button>(resources.getIdentifier("startBrowserBtn", "id", packageName)).setOnClickListener {
            showPage("browser")
        }

        findViewById<Button>(resources.getIdentifier("homeToDownloadsBtn", "id", packageName)).setOnClickListener {
            showPage("downloads")
        }

        findViewById<Button>(resources.getIdentifier("homeToDetectedBtn", "id", packageName)).setOnClickListener {
            showPage("detected")
        }

        urlBox.setText("https://example.com")
        showPage("home")
    }

    private fun setupNav() {
        findViewById<Button>(resources.getIdentifier("navHome", "id", packageName)).setOnClickListener { showPage("home") }
        findViewById<Button>(resources.getIdentifier("navBrowser", "id", packageName)).setOnClickListener { showPage("browser") }
        findViewById<Button>(resources.getIdentifier("navDetected", "id", packageName)).setOnClickListener { showPage("detected") }
        findViewById<Button>(resources.getIdentifier("navDownloads", "id", packageName)).setOnClickListener { showPage("downloads") }
    }

    private fun showPage(page: String) {
        val home = findViewById<View>(resources.getIdentifier("homePage", "id", packageName))
        val browser = findViewById<View>(resources.getIdentifier("browserPage", "id", packageName))
        val detected = findViewById<View>(resources.getIdentifier("detectedPage", "id", packageName))
        val downloads = findViewById<View>(resources.getIdentifier("downloadsPage", "id", packageName))
        home.visibility = if (page == "home") View.VISIBLE else View.GONE
        browser.visibility = if (page == "browser") View.VISIBLE else View.GONE
        detected.visibility = if (page == "detected") View.VISIBLE else View.GONE
        downloads.visibility = if (page == "downloads") View.VISIBLE else View.GONE
        refreshDetectedText()
        refreshLogs()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.userAgentString = webView.settings.userAgentString + " AWRDownloaderStudio/1.0"

        webView.addJavascriptInterface(AwrBridge { item ->
            runOnUiThread { addMedia(item) }
        }, "AWRBridge")

        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                injectLoader()
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: ""
                if (MediaDetector.isMedia(url)) {
                    runOnUiThread {
                        addMedia(
                            MediaItem(
                                url = url,
                                title = webView.title ?: "video",
                                page = webView.url ?: "",
                                source = "request",
                                quality = MediaDetector.quality(url),
                                type = MediaDetector.type(url)
                            )
                        )
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.setDownloadListener { url, _, _, mimeType, _ ->
            if (MediaDetector.isMedia(url) || mimeType.startsWith("video")) {
                addMedia(
                    MediaItem(
                        url = url,
                        title = webView.title ?: "video",
                        page = webView.url ?: "",
                        source = "download-listener",
                        quality = MediaDetector.quality(url),
                        type = MediaDetector.type(url)
                    )
                )
            }
        }
    }

    private fun injectLoader() {
        try {
            val js = assets.open("loader.js").bufferedReader().use { it.readText() }
            webView.evaluateJavascript(js, null)
        } catch (_: Exception) {}
    }

    private fun addMedia(item: MediaItem) {
        if (media.containsKey(item.url)) return
        media[item.url] = item
        mediaCounter.text = "${media.size} found"
        findViewById<Button>(resources.getIdentifier("downloadFloat", "id", packageName)).text = "⬇ ${media.size}"
        refreshDetectedText()
    }

    private fun refreshDetectedText() {
        detectedText.text = if (media.isEmpty()) {
            "Nothing detected yet. Open the WebView and play your video."
        } else {
            media.values.joinToString("\n\n") {
                "${it.quality} | ${it.type} | ${it.source}\n${it.url}"
            }
        }
    }

    private fun refreshLogs() {
        downloadsText.text = if (logs.isEmpty()) "No downloads yet." else logs.joinToString("\n\n")
    }

    private fun showMediaDialog() {
        if (media.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("AWR Downloader")
                .setMessage("No media found yet. Play the video for a few seconds.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val items = media.values.toList()
        val labels = items.map {
            "${it.quality} | ${it.type} | ${it.source}\n${it.url.take(100)}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Choose quality / stream")
            .setItems(labels) { _, which ->
                downloader.download(items[which])
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
