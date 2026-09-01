package com.awr.vpn

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.VpnStatus
import java.text.DecimalFormat
import java.util.Locale

class UltraMainActivity : Activity(), VpnStatus.StateListener, VpnStatus.ByteCountListener, UltraSurface.Actions {
    private lateinit var surface: UltraSurface
    private lateinit var vip: VipStore
    private lateinit var engine: VpnEngine
    private lateinit var store: ConnectionStore

    private var servers: List<ServerInfo> = emptyList()
    private var selected: ServerInfo? = null
    private var protocol = ProtocolMode.AUTO
    private var dns = DnsMode.CLOUDFLARE
    private var autoBest = true
    private var phase = ConnPhase.OFF
    private var lastError = ""
    private var downloadBytes = 0L
    private var uploadBytes = 0L
    private var reconnectRequested = false
    private val handler = Handler(Looper.getMainLooper())

    private val clock = object : Runnable {
        override fun run() {
            if (::surface.isInitialized) syncUi()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(3, 9, 16)
        window.navigationBarColor = Color.rgb(3, 9, 16)
        window.decorView.systemUiVisibility = 0

        vip = VipStore(this)
        engine = VpnEngine(this)
        store = ConnectionStore(this)
        protocol = store.protocol()
        dns = store.dns()
        autoBest = store.autoBest()
        selected = store.loadServer()
        reconnectRequested = intent?.getBooleanExtra(EXTRA_RECONNECT, false) == true || intent?.action == ACTION_AWR_RECONNECT

        phase = if (store.wasConnected() || VpnStatus.isVPNActive()) ConnPhase.ON else ConnPhase.OFF
        surface = UltraSurface(this, this)
        setContentView(surface)
        AwrNotifier.ensureChannel(this)
        requestNotificationPermission()
        syncUi()
        handler.post(clock)

        if (phase == ConnPhase.ON) AwrNotifier.showConnected(this, selected)
        if (vip.isVip()) loadCatalog(false, reconnectRequested)

        // Validate a restored visual state after the VPN service has had time to publish state.
        if (store.wasConnected()) {
            handler.postDelayed({
                if (!VpnStatus.isVPNActive() && phase == ConnPhase.ON && !reconnectRequested) {
                    phase = ConnPhase.OFF
                    store.setConnected(false)
                    AwrNotifier.cancel(this)
                    syncUi()
                }
            }, 2500L)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.action == ACTION_AWR_RECONNECT || intent?.getBooleanExtra(EXTRA_RECONNECT, false) == true) {
            reconnectRequested = true
            if (vip.isVip()) {
                try { engine.disconnect() } catch (_: Exception) { }
                handler.postDelayed({
                    if (servers.isEmpty()) loadCatalog(false, true) else reconnectFromSaved()
                }, 650L)
            } else showVipDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        VpnStatus.addStateListener(this)
        VpnStatus.addByteCountListener(this)
        if (VpnStatus.isVPNActive()) {
            phase = ConnPhase.ON
            store.setConnected(true)
            AwrNotifier.showConnected(this, selected)
            syncUi()
        }
    }

    override fun onPause() {
        VpnStatus.removeStateListener(this)
        VpnStatus.removeByteCountListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(clock)
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 991)
        }
    }

    private fun syncUi() {
        val since = store.connectedAt()
        val elapsed = if (phase == ConnPhase.ON && since > 0L) ((System.currentTimeMillis() - since) / 1000L).coerceAtLeast(0L) else 0L
        surface.state = UltraSurface.UiState(
            vip = vip.isVip(),
            phase = phase,
            flag = selected?.flag ?: "🌐",
            server = selected?.country ?: if (vip.isVip()) "Smart route ready" else "VIP server vault locked",
            ping = selected?.ping ?: 0,
            protocol = protocol.label,
            dns = dns.label,
            serverCount = servers.size,
            error = lastError,
            autoBest = autoBest,
            downloadBytes = downloadBytes,
            uploadBytes = uploadBytes,
            connectedSeconds = elapsed
        )
    }

    override fun onConnect() {
        if (phase == ConnPhase.ON || phase == ConnPhase.CONNECTING || phase == ConnPhase.AUTH || phase == ConnPhase.FINDING) {
            phase = ConnPhase.OFF
            store.setConnected(false)
            try { engine.disconnect() } catch (_: Exception) { }
            AwrNotifier.cancel(this)
            syncUi()
            return
        }
        if (!vip.isVip()) {
            showVipDialog()
            return
        }
        downloadBytes = 0L
        uploadBytes = 0L
        if (servers.isEmpty()) loadCatalog(false, true)
        else {
            if (autoBest) selected = BestServerSelector.pick(servers, protocol) ?: selected
            if (selected == null) selected = BestServerSelector.pick(servers, protocol)
            selected?.let { store.saveServer(it, autoBest) }
            connectSelected()
        }
    }

    private fun reconnectFromSaved() {
        if (!vip.isVip()) return
        if (autoBest && servers.isNotEmpty()) selected = BestServerSelector.pick(servers, protocol) ?: selected
        if (selected == null && servers.isNotEmpty()) selected = BestServerSelector.pick(servers, protocol)
        selected?.let {
            store.saveServer(it, autoBest)
            connectSelected()
        } ?: loadCatalog(false, true)
        reconnectRequested = false
    }

    private fun connectSelected() {
        val server = selected ?: return
        phase = ConnPhase.FINDING
        lastError = ""
        syncUi()
        Thread {
            try {
                val data = ServerRepository.profile(vip.key(), server, protocol, dns)
                runOnUiThread {
                    try {
                        selected = data.server
                        store.saveServer(selected, autoBest)
                        phase = ConnPhase.AUTH
                        syncUi()
                        val permission = engine.prepare(data)
                        if (permission != null) startActivityForResult(permission, VPN_REQUEST)
                        else {
                            phase = ConnPhase.CONNECTING
                            syncUi()
                            engine.startPrepared()
                        }
                    } catch (e: Exception) {
                        fail("Profile error: ${e.message ?: "invalid configuration"}")
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "VPN repository unavailable"
                runOnUiThread {
                    if (msg == "VIP_REQUIRED") {
                        vip.clear(); servers = emptyList(); selected = null
                        store.setConnected(false)
                        fail("VIP session expired")
                        showVipDialog()
                    } else fail(msg)
                }
            }
        }.start()
    }

    private fun loadCatalog(showAfter: Boolean, connectAfter: Boolean = false) {
        if (!vip.isVip()) return
        surface.loadingRepository = true
        Thread {
            try {
                val list = ServerRepository.list(vip.key(), protocol)
                runOnUiThread {
                    surface.loadingRepository = false
                    servers = list
                    if (autoBest) {
                        selected = BestServerSelector.pick(list, protocol) ?: selected
                    } else {
                        val old = selected
                        selected = list.firstOrNull { it.id == old?.id } ?: old ?: list.firstOrNull()
                    }
                    selected?.let { store.saveServer(it, autoBest) }
                    syncUi()
                    when {
                        connectAfter && selected != null -> connectSelected()
                        showAfter && list.isNotEmpty() -> showServerDialog()
                        showAfter -> fail("No ${protocol.label} servers are available")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    surface.loadingRepository = false
                    val msg = e.message ?: "Repository unavailable"
                    if (msg == "VIP_REQUIRED") {
                        vip.clear(); servers = emptyList(); selected = null
                        store.setConnected(false); syncUi(); showVipDialog()
                    } else fail(msg)
                }
            }
        }.start()
    }

    private fun fail(message: String) {
        phase = ConnPhase.ERROR
        lastError = message
        syncUi()
        handler.postDelayed({
            if (phase == ConnPhase.ERROR) { phase = ConnPhase.OFF; syncUi() }
        }, 4000L)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) {
                try {
                    phase = ConnPhase.CONNECTING
                    syncUi()
                    engine.startPrepared()
                } catch (e: Exception) { fail(e.message ?: "Unable to start VPN") }
            } else {
                phase = ConnPhase.OFF
                syncUi()
            }
        }
    }

    override fun onServer() {
        if (!vip.isVip()) { showVipDialog(); return }
        if (servers.isEmpty()) loadCatalog(true) else showServerDialog()
    }

    override fun onVip() = showVipDialog()

    override fun onProtocol() {
        val pair = styledDialog("CONNECTION ENGINE", "Choose how the encrypted tunnel negotiates its route")
        val d = pair.first; val box = pair.second
        ProtocolMode.values().forEach { mode ->
            val sub = when (mode) {
                ProtocolMode.AUTO -> "Smart selection • recommended"
                ProtocolMode.UDP -> "Lowest latency when available"
                ProtocolMode.TCP -> "More resilient on restricted networks"
            }
            box.addView(optionRow(mode.label, sub, protocol == mode) {
                protocol = mode
                store.saveProtocol(mode)
                servers = emptyList()
                d.dismiss(); syncUi()
                if (vip.isVip()) loadCatalog(false)
            })
        }
        d.show()
    }

    override fun onSettings() = showSettingsDialog()

    private fun showVipDialog() {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(22))
            background = rounded(Color.rgb(8, 20, 31), 30f, Color.rgb(48, 85, 98), 1)
        }
        shell.addView(TextView(this).apply {
            text = "✦  AWR VIP ACCESS"
            textSize = 25f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        shell.addView(TextView(this).apply {
            text = if (vip.isVip()) "The private server vault is unlocked on this device." else "A valid VIP code is required before the app can request any server profile from the secure repository."
            textSize = 13.5f; setTextColor(Color.rgb(151, 177, 192)); setPadding(0, dp(8), 0, dp(18))
        })
        if (vip.isVip()) {
            shell.addView(glassInfo("VIP STATUS", "ACTIVE", Color.rgb(71, 243, 198)))
            vip.expires()?.let { shell.addView(glassInfo("EXPIRES", it.take(10), Color.WHITE)) }
            shell.addView(actionButton("CONTINUE SECURELY", Color.rgb(71, 243, 198), Color.rgb(3, 17, 22)) { d.dismiss() })
            shell.addView(TextView(this).apply {
                text = "Deactivate VIP on this device"; gravity = Gravity.CENTER; textSize = 11.5f
                setTextColor(Color.rgb(112, 138, 154)); setPadding(0, dp(16), 0, 0)
                setOnClickListener {
                    if (phase == ConnPhase.ON) try { engine.disconnect() } catch (_: Exception) { }
                    vip.clear(); servers = emptyList(); selected = null; store.setConnected(false); AwrNotifier.cancel(this@UltraMainActivity)
                    d.dismiss(); syncUi()
                }
            })
        } else {
            val input = EditText(this).apply {
                hint = "AWR-XXXX-XXXX-XXXX"; setHintTextColor(Color.rgb(87, 113, 130)); setTextColor(Color.WHITE)
                textSize = 16f; isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT
                setPadding(dp(18), dp(15), dp(18), dp(15)); background = rounded(Color.rgb(5, 14, 24), 17f, Color.rgb(37, 68, 82), 1)
            }
            shell.addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            val status = TextView(this).apply {
                textSize = 12.5f; setTextColor(Color.rgb(151, 177, 192)); visibility = View.GONE; setPadding(dp(3), 0, dp(3), dp(12))
            }
            shell.addView(status)
            shell.addView(actionButton("UNLOCK AWR VIP", Color.rgb(71, 243, 198), Color.rgb(3, 17, 22)) {
                val code = input.text.toString().trim()
                if (code.isBlank()) {
                    status.visibility = View.VISIBLE; status.text = "Enter your AWR VIP code."; status.setTextColor(Color.rgb(255, 122, 132))
                } else {
                    status.visibility = View.VISIBLE; status.text = "Verifying encrypted entitlement…"; status.setTextColor(Color.rgb(151, 177, 192)); input.isEnabled = false
                    Thread {
                        val result = AwrApi.verifyVip(code)
                        runOnUiThread {
                            input.isEnabled = true
                            if (result.valid) {
                                vip.save(code, result.expiresAt); status.text = "VIP VERIFIED • SERVER VAULT UNLOCKED"; status.setTextColor(Color.rgb(71, 243, 198)); syncUi()
                                handler.postDelayed({ d.dismiss(); loadCatalog(false) }, 650L)
                            } else {
                                status.text = result.message; status.setTextColor(Color.rgb(255, 122, 132))
                            }
                        }
                    }.start()
                }
            })
        }
        d.setContentView(shell)
        styleDialogWindow(d, .92f, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        d.show()
    }

    private fun showServerDialog() {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(20), dp(18), dp(16)); background = rounded(Color.rgb(7, 17, 29), 30f, Color.rgb(42, 76, 90), 1)
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "SERVER VAULT"; textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(TextView(this).apply {
            text = "${servers.size} LIVE"; textSize = 10.5f; setTextColor(Color.rgb(71, 243, 198)); setPadding(dp(11), dp(7), dp(11), dp(7)); background = rounded(Color.argb(40,71,243,198), 50f, Color.argb(95,71,243,198), 1)
        })
        outer.addView(titleRow)
        outer.addView(TextView(this).apply {
            text = "Smart Route scores quality, speed, latency and server load."; textSize = 12.5f; setTextColor(Color.rgb(137, 163, 178)); setPadding(0, dp(5), 0, dp(12))
        })
        outer.addView(optionRow("⚡  SMART ROUTE", "Automatically choose the strongest endpoint now", autoBest) {
            autoBest = true
            selected = BestServerSelector.pick(servers, protocol)
            store.saveServer(selected, true)
            d.dismiss(); syncUi()
        })
        val scroll = ScrollView(this)
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        servers.groupBy { it.country }.toList().sortedBy { it.first }.forEach { (country, group) ->
            listBox.addView(TextView(this).apply {
                text = "${group.firstOrNull()?.flag ?: "🌐"}  ${country.uppercase(Locale.US)}"; textSize = 11.5f; setTextColor(Color.rgb(105, 136, 154)); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(dp(4), dp(13), dp(4), dp(6))
            })
            group.take(6).forEach { s ->
                listBox.addView(serverRow(s, !autoBest && selected?.id == s.id) {
                    autoBest = false; selected = s; store.saveServer(s, false); d.dismiss(); syncUi()
                })
            }
        }
        scroll.addView(listBox); outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        d.setContentView(outer)
        d.window?.apply { setBackgroundDrawableResource(android.R.color.transparent); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); attributes = attributes.apply { dimAmount = .84f }; setGravity(Gravity.BOTTOM) }
        d.setOnShowListener { d.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * .84f).toInt()) }
        d.show()
    }

    private fun showSettingsDialog() {
        val pair = styledDialog("AWR CONTROL CENTER", "Security, routing and device behavior")
        val d = pair.first; val box = pair.second
        box.addView(optionRow("SMART ROUTE", if (autoBest) "Enabled • strongest endpoint selected automatically" else "Disabled • manual server locked", autoBest) {
            autoBest = !autoBest
            if (autoBest && servers.isNotEmpty()) selected = BestServerSelector.pick(servers, protocol)
            store.saveServer(selected, autoBest); d.dismiss(); syncUi()
        })
        box.addView(optionRow("SECURE DNS", "${dns.label} • protected inside tunnel", false) { d.dismiss(); showDnsDialog() })
        box.addView(optionRow("ANDROID KILL SWITCH", "Always-on VPN + Block connections without VPN", false) { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) })
        box.addView(optionRow("REFRESH SERVER VAULT", "Fetch fresh VIP-authorized routes", false) { d.dismiss(); if (vip.isVip()) loadCatalog(false) else showVipDialog() })
        box.addView(optionRow("AWR VIP", if (vip.isVip()) "Active on this device" else "Required for repository access", vip.isVip()) { d.dismiss(); showVipDialog() })
        d.show()
    }

    private fun showDnsDialog() {
        val pair = styledDialog("SECURE DNS", "DNS is injected directly into the protected profile")
        val d = pair.first; val box = pair.second
        DnsMode.values().forEach { mode ->
            box.addView(optionRow(mode.label, "${mode.d1}  •  ${mode.d2}", mode == dns) {
                dns = mode; store.saveDns(mode); d.dismiss(); syncUi()
            })
        }
        d.show()
    }

    private fun styledDialog(title: String, subtitle: String): Pair<Dialog, LinearLayout> {
        val d = Dialog(this); d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(22), dp(22), dp(22)); background = rounded(Color.rgb(7, 18, 30), 29f, Color.rgb(42, 75, 89), 1)
        }
        shell.addView(TextView(this).apply { text = title; textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
        shell.addView(TextView(this).apply { text = subtitle; textSize = 12.5f; setTextColor(Color.rgb(137, 163, 178)); setPadding(0, dp(5), 0, dp(13)) })
        d.setContentView(shell); styleDialogWindow(d, .92f, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        return d to shell
    }

    private fun styleDialogWindow(d: Dialog, widthFactor: Float, height: Int, gravity: Int) {
        d.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); attributes = attributes.apply { dimAmount = .80f }; setGravity(gravity)
        }
        d.setOnShowListener { d.window?.setLayout((resources.displayMetrics.widthPixels * widthFactor).toInt(), height) }
    }

    private fun optionRow(title: String, sub: String, selectedRow: Boolean, click: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(if (selectedRow) Color.argb(34,71,243,198) else Color.rgb(10,25,39), 18f, if (selectedRow) Color.argb(105,71,243,198) else Color.rgb(34,61,75), 1)
        addView(TextView(this@UltraMainActivity).apply { text = if (selectedRow) "✓  $title" else title; textSize = 14.5f; setTextColor(if (selectedRow) Color.rgb(71,243,198) else Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        addView(TextView(this@UltraMainActivity).apply { text = sub; textSize = 11.5f; setTextColor(Color.rgb(122,149,165)); setPadding(0, dp(4), 0, 0) })
        setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
    }

    private fun serverRow(server: ServerInfo, selectedRow: Boolean, click: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(13), dp(12), dp(13), dp(12))
        background = rounded(if (selectedRow) Color.argb(34,71,243,198) else Color.rgb(9,23,37), 18f, if (selectedRow) Color.argb(105,71,243,198) else Color.rgb(31,57,70), 1)
        addView(TextView(this@UltraMainActivity).apply { text = server.flag; textSize = 27f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(45), dp(45)))
        addView(LinearLayout(this@UltraMainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@UltraMainActivity).apply { text = server.name; textSize = 14.5f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            addView(TextView(this@UltraMainActivity).apply { text = "${server.protocol.uppercase(Locale.US)}  •  ${speed(server.speedBps)}  •  ${server.sessions} sessions"; textSize = 10.5f; setTextColor(Color.rgb(118,146,163)) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(9) })
        addView(TextView(this@UltraMainActivity).apply { text = if (server.ping > 0) "${server.ping} ms" else "LIVE"; textSize = 10.5f; setTextColor(if (server.ping in 1..99) Color.rgb(71,243,198) else Color.rgb(160,181,193)) })
        setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    private fun actionButton(text: String, bg: Int, fg: Int, click: () -> Unit): TextView = TextView(this).apply {
        this.text = text; gravity = Gravity.CENTER; textSize = 13.5f; setTextColor(fg); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(dp(16), dp(15), dp(16), dp(15)); background = rounded(bg, 18f); setOnClickListener { click() }
    }

    private fun glassInfo(label: String, value: String, accent: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(15), dp(13), dp(15), dp(13)); background = rounded(Color.rgb(10,26,40), 17f, Color.rgb(35,66,79), 1)
        addView(TextView(this@UltraMainActivity).apply { text = label; textSize = 11.5f; setTextColor(Color.rgb(124,151,166)) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@UltraMainActivity).apply { text = value; textSize = 12.5f; setTextColor(accent); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    override fun updateState(state: String?, logmessage: String?, localizedResId: Int, level: ConnectionStatus?) {
        runOnUiThread {
            phase = when (level) {
                ConnectionStatus.LEVEL_CONNECTED -> ConnPhase.ON
                ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
                ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED -> ConnPhase.CONNECTING
                ConnectionStatus.LEVEL_AUTH_FAILED -> ConnPhase.ERROR
                ConnectionStatus.LEVEL_NOTCONNECTED -> ConnPhase.OFF
                else -> phase
            }
            when (phase) {
                ConnPhase.ON -> {
                    lastError = ""; store.setConnected(true); selected?.let { store.saveServer(it, autoBest) }; AwrNotifier.showConnected(this, selected)
                }
                ConnPhase.OFF -> { store.setConnected(false); AwrNotifier.cancel(this) }
                ConnPhase.ERROR -> { lastError = if (level == ConnectionStatus.LEVEL_AUTH_FAILED) "VPN authentication failed" else logmessage.orEmpty(); store.setConnected(false); AwrNotifier.cancel(this) }
                else -> Unit
            }
            syncUi()
        }
    }

    override fun setConnectedVPN(uuid: String?) { }

    override fun updateByteCount(inBytes: Long, outBytes: Long, diffIn: Long, diffOut: Long) {
        downloadBytes = inBytes.coerceAtLeast(0L)
        uploadBytes = outBytes.coerceAtLeast(0L)
        if (::surface.isInitialized) runOnUiThread { syncUi() }
    }

    private fun rounded(color: Int, radius: Float, strokeColor: Int? = null, strokeDp: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (strokeColor != null && strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun speed(bps: Long): String {
        if (bps <= 0L) return "secure"
        val mbps = bps / 1_000_000.0
        return if (mbps >= 1000.0) "${DecimalFormat("0.0").format(mbps/1000)} Gbps" else "${DecimalFormat("0").format(mbps)} Mbps"
    }
}
