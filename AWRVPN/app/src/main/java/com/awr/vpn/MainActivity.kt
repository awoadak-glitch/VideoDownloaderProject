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

class MainActivity : Activity(), VpnStatus.StateListener, VpnStatus.ByteCountListener, AwrVpnSurface.Actions {
    private lateinit var surface: AwrVpnSurface
    private lateinit var vip: VipStore
    private lateinit var engine: VpnEngine
    private lateinit var session: SessionStore

    private var servers: List<ServerInfo> = emptyList()
    private var selected: ServerInfo? = null
    private var protocol = ProtocolMode.AUTO
    private var dns = DnsMode.CLOUDFLARE
    private var phase = ConnPhase.OFF
    private var lastError = ""
    private var rxRate = 0L
    private var txRate = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.rgb(3, 7, 14)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        vip = VipStore(this)
        engine = VpnEngine(this)
        session = SessionStore(this)
        selected = session.loadServer()
        protocol = session.protocol()
        dns = session.dns()
        phase = if (engine.isActive()) if (session.connected()) ConnPhase.ON else ConnPhase.CONNECTING else ConnPhase.OFF

        surface = AwrVpnSurface(this, this)
        setContentView(surface)
        syncUi()
        requestNotificationPermission()

        val fromNotification = intent.getBooleanExtra("connect_last", false)
        if (vip.isVip() && !fromNotification) loadCatalog(connectAfter = false, showAfter = false)
        if (fromNotification) Handler(Looper.getMainLooper()).postDelayed({ onConnect() }, 450)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("connect_last", false) == true) Handler(Looper.getMainLooper()).postDelayed({ onConnect() }, 250)
    }

    override fun onResume() {
        super.onResume()
        VpnStatus.addStateListener(this)
        VpnStatus.addByteCountListener(this)
        if (engine.isActive()) phase = if (session.connected()) ConnPhase.ON else ConnPhase.CONNECTING
        else if (phase == ConnPhase.ON || phase == ConnPhase.CONNECTING) phase = ConnPhase.OFF
        selected = session.loadServer() ?: selected
        syncUi()
    }

    override fun onPause() {
        VpnStatus.removeStateListener(this)
        VpnStatus.removeByteCountListener(this)
        super.onPause()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 811)
        }
    }

    private fun syncUi() {
        val s = selected
        val quality = if (s != null) ServerSelector.quality(s, if (servers.isEmpty()) listOf(s) else servers) else 0
        surface.state = AwrVpnSurface.UiState(
            vip = vip.isVip(), phase = phase, flag = s?.flag ?: "🌐",
            server = s?.country ?: if (vip.isVip()) "Smart Route • Auto" else "VIP server vault locked",
            ping = s?.ping ?: 0, protocol = protocol.label, dns = dns.label, serverCount = servers.size,
            error = lastError, rxRate = rxRate, txRate = txRate, quality = quality,
            connectedSince = if (phase == ConnPhase.ON) session.connectedSince() else 0L
        )
    }

    override fun onConnect() {
        surface.triggerConnectBurst()
        if (phase == ConnPhase.ON || phase == ConnPhase.CONNECTING || phase == ConnPhase.AUTH || phase == ConnPhase.FINDING) {
            engine.disconnect()
            return
        }
        if (!vip.isVip()) { showVipDialog(); return }
        if (selected == null) loadCatalog(connectAfter = true, showAfter = false) else connectSelected()
    }

    private fun connectSelected() {
        val server = selected ?: return
        session.saveSelection(server, protocol, dns)
        phase = ConnPhase.FINDING
        lastError = ""
        syncUi()
        Thread {
            try {
                val data = ServerRepository.profile(vip.key(), server, protocol, dns)
                runOnUiThread {
                    try {
                        selected = data.server
                        session.saveSelection(data.server, protocol, dns)
                        phase = ConnPhase.AUTH
                        syncUi()
                        val permission = engine.prepare(data)
                        if (permission != null) startActivityForResult(permission, VPN_REQUEST)
                        else {
                            phase = ConnPhase.CONNECTING
                            syncUi()
                            engine.startPrepared()
                        }
                    } catch (e: Exception) { fail("Profile error: ${e.message ?: "invalid configuration"}") }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    val msg = e.message ?: "VPN repository unavailable"
                    if (msg == "VIP_REQUIRED") {
                        vip.clear(); servers = emptyList(); selected = null; session.saveSelection(null, protocol, dns)
                        fail("VIP session expired"); showVipDialog()
                    } else fail(msg)
                }
            }
        }.start()
    }

    private fun loadCatalog(connectAfter: Boolean, showAfter: Boolean) {
        if (!vip.isVip()) return
        surface.loadingRepository = true
        surface.invalidate()
        Thread {
            try {
                val list = ServerRepository.list(vip.key(), protocol)
                val best = ServerSelector.best(list)
                runOnUiThread {
                    surface.loadingRepository = false
                    servers = list.sortedByDescending { ServerSelector.quality(it, list) }
                    val current = selected
                    selected = if (current != null) servers.firstOrNull { it.id == current.id } ?: best else best
                    session.saveSelection(selected, protocol, dns)
                    syncUi()
                    when {
                        list.isEmpty() -> fail("No servers are available for ${protocol.label}")
                        connectAfter -> connectSelected()
                        showAfter -> showServerDialog()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    surface.loadingRepository = false
                    val msg = e.message ?: "Repository unavailable"
                    if (msg == "VIP_REQUIRED") {
                        vip.clear(); servers = emptyList(); selected = null; session.saveSelection(null, protocol, dns); syncUi(); showVipDialog()
                    } else fail(msg)
                }
            }
        }.start()
    }

    private fun fail(message: String) {
        phase = ConnPhase.ERROR
        lastError = message
        syncUi()
        Handler(Looper.getMainLooper()).postDelayed({ if (phase == ConnPhase.ERROR) { phase = ConnPhase.OFF; syncUi() } }, 3600)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) {
                try { phase = ConnPhase.CONNECTING; syncUi(); engine.startPrepared() }
                catch (e: Exception) { fail(e.message ?: "Unable to start VPN") }
            } else { phase = ConnPhase.OFF; syncUi() }
        }
    }

    override fun onServer() {
        if (!vip.isVip()) { showVipDialog(); return }
        if (servers.isEmpty()) loadCatalog(connectAfter = false, showAfter = true) else showServerDialog()
    }

    override fun onVip() = showVipDialog()

    override fun onProtocol() {
        val pair = styledDialog("CONNECTION PROTOCOL", "Tune the tunnel for speed or difficult networks")
        ProtocolMode.values().forEach { mode ->
            pair.second.addView(optionRow(mode.label, when (mode) {
                ProtocolMode.AUTO -> "Smart selection • recommended"
                ProtocolMode.UDP -> "Lowest latency when available"
                ProtocolMode.TCP -> "More resilient on restrictive networks"
            }, mode == protocol) {
                protocol = mode; servers = emptyList(); selected = null; session.saveSelection(null, protocol, dns)
                pair.first.dismiss(); syncUi(); if (vip.isVip()) loadCatalog(false, false)
            })
        }
        pair.first.show()
    }

    override fun onSettings() = showSettingsDialog()

    private fun showVipDialog() {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(22))
            background = rounded(Color.rgb(7, 16, 29), 30f, Color.rgb(55, 94, 106), 1)
        }
        shell.addView(TextView(this).apply {
            text = "✦  AWR VIP"
            textSize = 28f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        shell.addView(TextView(this).apply {
            text = if (vip.isVip()) "Private repository unlocked on this device." else "Unlock AWR Secure Repository. No VPN server profile is delivered before VIP authorization."
            textSize = 14f; setTextColor(Color.rgb(152, 176, 193)); setPadding(0, dp(9), 0, dp(18))
        })
        if (vip.isVip()) {
            shell.addView(glassInfo("VIP STATUS", "ACTIVE", Color.rgb(70, 255, 205)))
            vip.expires()?.let { shell.addView(glassInfo("EXPIRES", it.take(10), Color.WHITE)) }
            shell.addView(actionButton("DONE", Color.rgb(70, 255, 205), Color.rgb(3, 13, 19)) { d.dismiss() })
        } else {
            val input = EditText(this).apply {
                hint = "AWR-XXXX-XXXX-XXXX"; setHintTextColor(Color.rgb(83, 108, 127)); setTextColor(Color.WHITE); textSize = 16f
                isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT; setPadding(dp(18), dp(15), dp(18), dp(15))
                background = rounded(Color.rgb(5, 12, 22), 17f, Color.rgb(39, 71, 86), 1)
            }
            val status = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(150, 174, 191)); visibility = View.GONE; setPadding(dp(3), dp(8), dp(3), dp(12)) }
            shell.addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            shell.addView(status)
            shell.addView(actionButton("ACTIVATE AWR VIP", Color.rgb(70, 255, 205), Color.rgb(3, 13, 19)) {
                val code = input.text.toString().trim()
                if (code.isBlank()) { status.visibility = View.VISIBLE; status.text = "Enter your VIP code"; status.setTextColor(Color.rgb(255, 120, 130)) }
                else {
                    status.visibility = View.VISIBLE; status.text = "Verifying encrypted access…"; status.setTextColor(Color.rgb(150, 174, 191)); input.isEnabled = false
                    Thread {
                        val result = AwrApi.verifyVip(code)
                        runOnUiThread {
                            input.isEnabled = true
                            if (result.valid) {
                                vip.save(code, result.expiresAt); status.text = "VIP VERIFIED • VAULT UNLOCKED"; status.setTextColor(Color.rgb(70, 255, 205)); syncUi()
                                Handler(Looper.getMainLooper()).postDelayed({ d.dismiss(); loadCatalog(false, false) }, 600)
                            } else { status.text = result.message; status.setTextColor(Color.rgb(255, 120, 130)) }
                        }
                    }.start()
                }
            })
        }
        d.setContentView(shell)
        d.window?.apply { setBackgroundDrawableResource(android.R.color.transparent); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); attributes = attributes.apply { dimAmount = .82f } }
        d.setOnShowListener { d.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT) }
        d.show()
    }

    private fun showServerDialog() {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(22), dp(18), dp(16)); background = rounded(Color.rgb(5, 13, 25), 30f, Color.rgb(45, 78, 94), 1)
        }
        outer.addView(TextView(this).apply { text = "SMART SERVER VAULT"; textSize = 23f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
        outer.addView(TextView(this).apply { text = "AWR ranks quality, speed, latency and server load to choose the fastest route."; textSize = 12.5f; setTextColor(Color.rgb(137, 164, 181)); setPadding(0, dp(5), 0, dp(12)) })

        val best = ServerSelector.best(servers)
        if (best != null) outer.addView(optionRow("⚡  AUTO • BEST ROUTE", "${best.flag} ${best.country}  •  ${best.ping.takeIf { it > 0 }?.let { "$it ms" } ?: "live"}  •  ${ServerSelector.quality(best, servers)}% quality", selected?.id == best.id) {
            selected = best; session.saveSelection(best, protocol, dns); d.dismiss(); syncUi()
        })

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        servers.take(50).forEachIndexed { index, server ->
            list.addView(serverRow(server, selected?.id == server.id, index == 0) {
                selected = server; session.saveSelection(server, protocol, dns); d.dismiss(); syncUi()
            })
        }
        scroll.addView(list)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        d.setContentView(outer)
        d.window?.apply { setBackgroundDrawableResource(android.R.color.transparent); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); attributes = attributes.apply { dimAmount = .86f }; setGravity(Gravity.BOTTOM) }
        d.setOnShowListener { d.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * .86f).toInt()) }
        d.show()
    }

    private fun showSettingsDialog() {
        val pair = styledDialog("AWR CONTROL CENTER", "Live controls for privacy and performance")
        val d = pair.first; val box = pair.second
        box.addView(optionRow("SECURE DNS", dns.label, false) { d.dismiss(); showDnsDialog() })
        box.addView(optionRow("ANDROID KILL SWITCH", "Always-on VPN + block traffic without VPN", false) { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) })
        box.addView(optionRow("NOTIFICATION CONTROLS", "Disconnect from the VPN notification and reconnect instantly", false) { requestNotificationPermission() })
        box.addView(optionRow("SMART ROUTE ENGINE", "Quality + speed + latency + load weighted selection", true) { d.dismiss(); if (vip.isVip()) loadCatalog(false, false) })
        box.addView(optionRow("REFRESH SERVER VAULT", "Request a fresh VIP-authorized catalog", false) { d.dismiss(); if (vip.isVip()) loadCatalog(false, false) else showVipDialog() })
        box.addView(optionRow("AWR VIP", if (vip.isVip()) "Active on this device" else "Required for VPN access", vip.isVip()) { d.dismiss(); showVipDialog() })
        d.show()
    }

    private fun showDnsDialog() {
        val pair = styledDialog("SECURE DNS", "DNS is injected inside the protected tunnel")
        DnsMode.values().forEach { mode ->
            pair.second.addView(optionRow(mode.label, "${mode.d1}  •  ${mode.d2}", mode == dns) {
                dns = mode; session.saveSelection(selected, protocol, dns); pair.first.dismiss(); syncUi()
            })
        }
        pair.first.show()
    }

    private fun styledDialog(title: String, subtitle: String): Pair<Dialog, LinearLayout> {
        val d = Dialog(this); d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(22), dp(22), dp(22)); background = rounded(Color.rgb(6, 15, 27), 29f, Color.rgb(44, 78, 94), 1)
            addView(TextView(this@MainActivity).apply { text = title; textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
            addView(TextView(this@MainActivity).apply { text = subtitle; textSize = 12.5f; setTextColor(Color.rgb(139, 165, 181)); setPadding(0, dp(6), 0, dp(13)) })
        }
        d.setContentView(shell)
        d.window?.apply { setBackgroundDrawableResource(android.R.color.transparent); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); attributes = attributes.apply { dimAmount = .82f } }
        d.setOnShowListener { d.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT) }
        return d to shell
    }

    private fun optionRow(title: String, sub: String, chosen: Boolean, click: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(if (chosen) Color.argb(34, 70, 255, 205) else Color.rgb(9, 23, 38), 18f, if (chosen) Color.argb(125, 70, 255, 205) else Color.rgb(34, 62, 78), 1)
        addView(TextView(this@MainActivity).apply { text = if (chosen) "✓  $title" else title; textSize = 14.5f; setTextColor(if (chosen) Color.rgb(70, 255, 205) else Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        addView(TextView(this@MainActivity).apply { text = sub; textSize = 11.5f; setTextColor(Color.rgb(126, 151, 169)); setPadding(0, dp(4), 0, 0) })
        setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
    }

    private fun serverRow(server: ServerInfo, chosen: Boolean, best: Boolean, click: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(13), dp(11), dp(13), dp(11))
        background = rounded(if (chosen) Color.argb(33, 70, 255, 205) else Color.rgb(8, 21, 35), 18f, if (chosen) Color.argb(125, 70, 255, 205) else Color.rgb(31, 57, 73), 1)
        addView(TextView(this@MainActivity).apply { text = server.flag; textSize = 27f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply { text = (if (best) "⚡ " else "") + server.country; textSize = 14.5f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            addView(TextView(this@MainActivity).apply { text = "${server.protocol.uppercase(Locale.US)}  •  ${speed(server.speedBps)}  •  ${ServerSelector.quality(server, servers)}% quality"; textSize = 10.8f; setTextColor(Color.rgb(119, 146, 164)) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(9) })
        addView(TextView(this@MainActivity).apply { text = if (server.ping > 0) "${server.ping} ms" else "LIVE"; textSize = 10.5f; setTextColor(if (server.ping in 1..99) Color.rgb(70, 255, 205) else Color.rgb(158, 183, 197)) })
        setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    private fun actionButton(label: String, bg: Int, fg: Int, click: () -> Unit): TextView = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 13.5f; setTextColor(fg); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(dp(16), dp(15), dp(16), dp(15)); background = rounded(bg, 18f); setOnClickListener { click() }
    }

    private fun glassInfo(label: String, value: String, accent: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(15), dp(13), dp(15), dp(13)); background = rounded(Color.rgb(9, 23, 38), 17f, Color.rgb(34, 60, 77), 1)
        addView(TextView(this@MainActivity).apply { text = label; textSize = 11.5f; setTextColor(Color.rgb(126, 151, 169)) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@MainActivity).apply { text = value; textSize = 12.5f; setTextColor(accent); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    override fun updateState(state: String?, logmessage: String?, localizedResId: Int, level: ConnectionStatus?) {
        runOnUiThread {
            phase = when (level) {
                ConnectionStatus.LEVEL_CONNECTED -> ConnPhase.ON
                ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED -> ConnPhase.CONNECTING
                ConnectionStatus.LEVEL_AUTH_FAILED -> ConnPhase.ERROR
                ConnectionStatus.LEVEL_NOTCONNECTED -> ConnPhase.OFF
                else -> phase
            }
            when (level) {
                ConnectionStatus.LEVEL_CONNECTED -> { session.markConnected(true); lastError = "" }
                ConnectionStatus.LEVEL_NOTCONNECTED -> { session.markConnected(false); rxRate = 0; txRate = 0 }
                ConnectionStatus.LEVEL_AUTH_FAILED -> { session.markConnected(false); lastError = "VPN authentication failed" }
                else -> Unit
            }
            syncUi()
        }
    }

    override fun setConnectedVPN(uuid: String?) = Unit

    override fun updateByteCount(inBytes: Long, outBytes: Long, diffIn: Long, diffOut: Long) {
        runOnUiThread { rxRate = diffIn.coerceAtLeast(0); txRate = diffOut.coerceAtLeast(0); if (phase == ConnPhase.ON) syncUi() }
    }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null, strokeDp: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius); if (stroke != null && strokeDp > 0) setStroke(dp(strokeDp), stroke)
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun speed(bps: Long): String {
        if (bps <= 0) return "secure"
        val mbps = bps / 1_000_000.0
        return if (mbps >= 1000) "${DecimalFormat("0.0").format(mbps / 1000)} Gbps" else "${DecimalFormat("0").format(mbps)} Mbps"
    }
}
