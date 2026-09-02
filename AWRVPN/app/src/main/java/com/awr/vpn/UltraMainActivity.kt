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

    private var proServers: List<ServerInfo> = emptyList()
    private var freeServers: List<ServerInfo> = emptyList()
    private var selected: ServerInfo? = null
    private var selectedTier = ServerTier.FREE
    private var protocol = ProtocolMode.AUTO
    private var dns = DnsMode.CLOUDFLARE
    private var autoBest = true
    private var phase = ConnPhase.OFF
    private var lastError = ""
    private var downloadBytes = 0L
    private var uploadBytes = 0L
    private var reconnectRequested = false
    private var userDisconnecting = false
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
        selectedTier = selected?.tier ?: ServerTier.FREE
        reconnectRequested = intent?.getBooleanExtra(EXTRA_RECONNECT, false) == true || intent?.action == ACTION_AWR_RECONNECT

        phase = if (engine.isActive()) ConnPhase.ON else ConnPhase.OFF
        if (phase == ConnPhase.OFF && store.wasConnected()) store.setConnected(false)

        surface = UltraSurface(this, this)
        setContentView(surface)
        AwrNotifier.ensureChannel(this)
        requestNotificationPermission()
        syncUi()
        handler.post(clock)

        if (phase == ConnPhase.ON) {
            store.setConnected(true)
            AwrNotifier.showConnected(this, selected)
        }
        loadCatalog(ServerTier.FREE, false, reconnectRequested && selectedTier == ServerTier.FREE)
        if (vip.isVip()) loadCatalog(ServerTier.PRO, false, reconnectRequested && selectedTier == ServerTier.PRO)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.action == ACTION_AWR_RECONNECT || intent?.getBooleanExtra(EXTRA_RECONNECT, false) == true) {
            reconnectRequested = true
            if (selectedTier == ServerTier.PRO && !vip.isVip()) {
                showVipDialog()
                return
            }
            userDisconnecting = false
            runCatching { engine.disconnect() }
            handler.postDelayed({
                if (currentServers().isEmpty()) loadCatalog(selectedTier, false, true) else connectBestAsync()
            }, 650L)
        }
    }

    override fun onResume() {
        super.onResume()
        VpnStatus.addStateListener(this)
        VpnStatus.addByteCountListener(this)
        if (engine.isActive()) {
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
            server = selected?.country ?: if (selectedTier == ServerTier.PRO) "VPN PRO route ready" else "Free global route ready",
            ping = selected?.ping ?: 0,
            protocol = protocol.label,
            dns = dns.label,
            serverCount = currentServers().size,
            proCount = proServers.size,
            freeCount = freeServers.size,
            tier = selectedTier,
            error = lastError,
            autoBest = autoBest,
            downloadBytes = downloadBytes,
            uploadBytes = uploadBytes,
            connectedSeconds = elapsed,
            quality = selected?.quality ?: 0,
            verified = selected?.verified ?: false,
            source = selected?.source ?: "AWR Secure Repository"
        )
    }

    override fun onConnect() {
        if (phase == ConnPhase.ON || phase == ConnPhase.CONNECTING || phase == ConnPhase.AUTH || phase == ConnPhase.FINDING) {
            userDisconnecting = true
            phase = ConnPhase.OFF
            store.setConnected(false)
            runCatching { engine.disconnect() }
            AwrNotifier.cancel(this)
            syncUi()
            return
        }
        if (selectedTier == ServerTier.PRO && !vip.isVip()) {
            showVipDialog()
            return
        }
        userDisconnecting = false
        downloadBytes = 0L
        uploadBytes = 0L
        if (currentServers().isEmpty()) loadCatalog(selectedTier, false, true)
        else if (autoBest) connectBestAsync()
        else if (selected != null) connectSelected()
        else {
            selected = BestServerSelector.pick(currentServers(), protocol)
            connectSelected()
        }
    }

    private fun connectBestAsync() {
        if (selectedTier == ServerTier.PRO && !vip.isVip()) return
        val available = currentServers()
        if (available.isEmpty()) {
            loadCatalog(selectedTier, false, true)
            return
        }
        phase = ConnPhase.FINDING
        lastError = ""
        syncUi()
        Thread {
            val smart = runCatching { SmartRoute.pick(available, protocol)?.server }.getOrNull()
                ?: BestServerSelector.pick(available, protocol)
            runOnUiThread {
                if (smart == null) {
                    fail("NO_LIVE_SERVER")
                    return@runOnUiThread
                }
                selected = smart
                autoBest = true
                store.saveServer(smart, true)
                connectSelected()
            }
        }.start()
    }

    private fun connectSelected() {
        val requested = selected ?: return
        if (requested.tier == ServerTier.PRO && !vip.isVip()) { showVipDialog(); return }
        selectedTier = requested.tier
        phase = ConnPhase.FINDING
        lastError = ""
        syncUi()
        Thread {
            try {
                val data = resilientProfile(requested)
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
                        fail("PROFILE_ERROR • ${e.message ?: "invalid profile"}")
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "VPN_REPOSITORY_UNAVAILABLE"
                runOnUiThread {
                    if (msg == "VIP_REQUIRED") {
                        vip.clear(); proServers = emptyList(); if (selectedTier == ServerTier.PRO) selected = null
                        store.setConnected(false)
                        fail("VIP session expired")
                        showVipDialog()
                    } else fail(msg)
                }
            }
        }.start()
    }

    private fun resilientProfile(requested: ServerInfo): VpnProfileData {
        return try {
            ServerRepository.profile(vip.key(), requested, protocol, dns)
        } catch (first: Exception) {
            if (first.message == "VIP_REQUIRED") throw first
            try {
                // Same country first. The backend returns the strongest live replacement if the exact endpoint vanished.
                ServerRepository.best(vip.key(), protocol, dns, requested.tier, requested.code)
            } catch (second: Exception) {
                if (second.message == "VIP_REQUIRED") throw second
                // Last-resort global Smart Route so a stale volunteer endpoint never becomes SERVER_NOT_FOUND.
                ServerRepository.best(vip.key(), protocol, dns, requested.tier, null)
            }
        }
    }

    private fun loadCatalog(tier: ServerTier, showAfter: Boolean, connectAfter: Boolean = false) {
        if (tier == ServerTier.PRO && !vip.isVip()) { if (showAfter) showVipDialog(); return }
        surface.loadingRepository = true
        Thread {
            try {
                val list = ServerRepository.list(vip.key(), protocol, tier)
                runOnUiThread {
                    surface.loadingRepository = false
                    setServers(tier, list)
                    if (tier == selectedTier) {
                        if (autoBest) {
                            selected = BestServerSelector.pick(list, protocol) ?: selected?.takeIf { it.tier == tier }
                        } else {
                            val old = selected?.takeIf { it.tier == tier }
                            selected = list.firstOrNull { it.id == old?.id }
                                ?: list.firstOrNull { it.code == old?.code && it.protocol == old?.protocol }
                                ?: old
                                ?: list.firstOrNull()
                        }
                    }
                    selected?.let { store.saveServer(it, autoBest) }
                    syncUi()
                    when {
                        connectAfter && list.isNotEmpty() -> if (autoBest) connectBestAsync() else connectSelected()
                        showAfter && list.isNotEmpty() -> showTierServers(tier)
                        showAfter -> fail("NO_LIVE_SERVER")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    surface.loadingRepository = false
                    val msg = e.message ?: "REPOSITORY_UNAVAILABLE"
                    if (msg == "VIP_REQUIRED" && tier == ServerTier.PRO) {
                        vip.clear(); proServers = emptyList(); if (selectedTier == ServerTier.PRO) selected = null
                        store.setConnected(false); syncUi(); showVipDialog()
                    } else fail(msg)
                }
            }
        }.start()
    }

    private fun currentServers(): List<ServerInfo> = if (selectedTier == ServerTier.PRO) proServers else freeServers

    private fun serversFor(tier: ServerTier): List<ServerInfo> = if (tier == ServerTier.PRO) proServers else freeServers

    private fun setServers(tier: ServerTier, list: List<ServerInfo>) {
        if (tier == ServerTier.PRO) proServers = list else freeServers = list
    }

    private fun fail(message: String) {
        phase = ConnPhase.ERROR
        lastError = when (message) {
            "SERVER_NOT_FOUND" -> "Route changed • refreshing live replacement"
            "NO_LIVE_SERVER" -> "No live route available • refresh and retry"
            else -> message.replace('_', ' ')
        }
        syncUi()
        handler.postDelayed({
            if (phase == ConnPhase.ERROR) { phase = ConnPhase.OFF; syncUi() }
        }, 4500L)
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
        showServerDialog()
    }

    override fun onVip() = showVipDialog()

    override fun onProtocol() {
        val pair = styledDialog("CONNECTION ENGINE", "Choose how AWR negotiates the encrypted route")
        val d = pair.first; val box = pair.second
        ProtocolMode.values().forEach { mode ->
            val sub = when (mode) {
                ProtocolMode.AUTO -> "Smart selection • recommended"
                ProtocolMode.UDP -> "Lowest latency where UDP is available"
                ProtocolMode.TCP -> "More resilient on restricted networks"
            }
            box.addView(optionRow(mode.label, sub, protocol == mode) {
                protocol = mode
                store.saveProtocol(mode)
                proServers = emptyList()
                freeServers = emptyList()
                d.dismiss(); syncUi()
                loadCatalog(ServerTier.FREE, false)
                if (vip.isVip()) loadCatalog(ServerTier.PRO, false)
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
            text = if (vip.isVip()) "Private server vault unlocked. Server profiles remain inaccessible without this entitlement." else "Unlock the private multi-source VPN repository. No server profile is returned before VIP verification."
            textSize = 13.5f; setTextColor(Color.rgb(151, 177, 192)); setPadding(0, dp(8), 0, dp(18))
        })
        if (vip.isVip()) {
            shell.addView(glassInfo("VIP STATUS", "ACTIVE", Color.rgb(71, 243, 198)))
            shell.addView(glassInfo("VPN PRO ROUTES", proServers.size.toString(), Color.rgb(111, 219, 244)))
            vip.expires()?.let { shell.addView(glassInfo("EXPIRES", it.take(10), Color.WHITE)) }
            shell.addView(actionButton("CONTINUE SECURELY", Color.rgb(71, 243, 198), Color.rgb(3, 17, 22)) { d.dismiss() })
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
                                vip.save(code, result.expiresAt)
                                status.text = "VIP VERIFIED • SERVER VAULT UNLOCKED"; status.setTextColor(Color.rgb(71, 243, 198)); syncUi()
                                handler.postDelayed({ d.dismiss(); loadCatalog(ServerTier.PRO, false) }, 650L)
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
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(16))
            background = rounded(Color.rgb(7, 17, 29), 30f, Color.rgb(42, 76, 90), 1)
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "AWR GLOBAL NETWORK"; textSize = 21f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(TextView(this).apply {
            text = "${proServers.size + freeServers.size} LIVE"; textSize = 10.5f; setTextColor(Color.rgb(71, 243, 198)); setPadding(dp(11), dp(7), dp(11), dp(7)); background = rounded(Color.argb(40,71,243,198), 50f, Color.argb(95,71,243,198), 1)
        })
        outer.addView(titleRow)
        outer.addView(TextView(this).apply {
            text = "Two independent networks. PRO stays protected by AWR VIP; FREE is open to everyone and refreshed from public live feeds."; textSize = 12f; setTextColor(Color.rgb(137, 163, 178)); setPadding(0, dp(5), 0, dp(14))
        })
        outer.addView(tierCard(
            title = "✦  VPN PRO",
            subtitle = if (vip.isVip()) "Private AWR routes • priority Smart Route • VIP protected" else "Private AWR routes • enter VIP code to unlock",
            count = proServers.size,
            accent = Color.rgb(164, 122, 255),
            locked = !vip.isVip(),
            active = selectedTier == ServerTier.PRO
        ) {
            d.dismiss()
            if (!vip.isVip()) showVipDialog()
            else if (proServers.isEmpty()) loadCatalog(ServerTier.PRO, true)
            else showTierServers(ServerTier.PRO)
        })
        outer.addView(TextView(this).apply {
            text = "PUBLIC NETWORK"; textSize = 9f; setTextColor(Color.rgb(87, 115, 135)); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(dp(4), dp(9), 0, dp(8))
        })
        outer.addView(tierCard(
            title = "◎  VPN FREE",
            subtitle = "VPN Gate + AutoOVPN mirror + PublicVPNList live checks",
            count = freeServers.size,
            accent = Color.rgb(71, 243, 198),
            locked = false,
            active = selectedTier == ServerTier.FREE
        ) {
            d.dismiss()
            if (freeServers.isEmpty()) loadCatalog(ServerTier.FREE, true)
            else showTierServers(ServerTier.FREE)
        })
        outer.addView(TextView(this).apply {
            text = "Public volunteer VPNs change continuously. AWR ranks recent speed, latency, load and live verification before selection."; textSize = 10.5f; setTextColor(Color.rgb(105, 132, 149)); setPadding(dp(4), dp(12), dp(4), 0)
        })
        d.setContentView(outer)
        styleBottomDialog(d, .68f)
        d.show()
    }

    private fun showTierServers(tier: ServerTier) {
        val list = serversFor(tier)
        if (tier == ServerTier.PRO && !vip.isVip()) { showVipDialog(); return }
        if (list.isEmpty()) { loadCatalog(tier, true); return }
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val accent = if (tier == ServerTier.PRO) Color.rgb(164, 122, 255) else Color.rgb(71, 243, 198)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(20), dp(18), dp(16)); background = rounded(Color.rgb(7, 17, 29), 30f, Color.rgb(42, 76, 90), 1)
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = tier.label; textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(TextView(this).apply {
            text = "${list.size} LIVE"; textSize = 10.5f; setTextColor(accent); setPadding(dp(11), dp(7), dp(11), dp(7)); background = rounded(Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent)), 50f, Color.argb(105, Color.red(accent), Color.green(accent), Color.blue(accent)), 1)
        })
        outer.addView(titleRow)
        outer.addView(TextView(this).apply {
            text = if (tier == ServerTier.PRO) "Priority private catalog with AWR VIP authorization." else "Free public catalog refreshed from multiple community feeds and live checks."
            textSize = 11.5f; setTextColor(Color.rgb(137, 163, 178)); setPadding(0, dp(5), 0, dp(12))
        })
        outer.addView(optionRow("⚡  SMART ROUTE", "Measure top routes on this phone and choose the strongest", selectedTier == tier && autoBest) {
            selectedTier = tier; autoBest = true; selected = BestServerSelector.pick(list, protocol); selected?.let { store.saveServer(it, true) }
            d.dismiss(); syncUi()
        })
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val groups = list.groupBy { it.country }.toList().sortedWith(compareByDescending<Pair<String, List<ServerInfo>>> { it.second.size }.thenBy { it.first })
        groups.forEach { (country, group) ->
            val best = BestServerSelector.pick(group, protocol) ?: group.first()
            box.addView(countryRow(country, group, best, group.count { it.verified }) {
                d.dismiss(); showCountryServers(country, group)
            })
        }
        scroll.addView(box)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        d.setContentView(outer); styleBottomDialog(d, .88f); d.show()
    }

    private fun showCountryServers(country: String, group: List<ServerInfo>) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(19), dp(18), dp(14)); background = rounded(Color.rgb(7, 17, 29), 30f, Color.rgb(42, 76, 90), 1)
        }
        val flag = group.firstOrNull()?.flag ?: "🌐"
        outer.addView(TextView(this).apply {
            text = "$flag  $country"; textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        outer.addView(TextView(this).apply {
            text = "${group.size} live endpoints • ${group.count { it.verified }} multi-source verified"; textSize = 11.5f; setTextColor(Color.rgb(137, 163, 178)); setPadding(0, dp(4), 0, dp(11))
        })
        outer.addView(optionRow("⚡ FASTEST IN $country", "Pick the highest-ranked live endpoint in this country", false) {
            autoBest = false
            selectedTier = group.firstOrNull()?.tier ?: selectedTier
            selected = BestServerSelector.pick(group, protocol) ?: group.firstOrNull()
            selected?.let { store.saveServer(it, false) }
            d.dismiss(); syncUi()
        })
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        group.sortedWith(compareByDescending<ServerInfo> { it.verified }.thenByDescending { it.quality }.thenBy { if (it.ping > 0) it.ping else 9999 })
            .take(180)
            .forEach { s ->
                box.addView(serverRow(s, !autoBest && selected?.id == s.id) {
                    autoBest = false; selectedTier = s.tier; selected = s; store.saveServer(s, false); d.dismiss(); syncUi()
                })
            }
        scroll.addView(box)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        d.setContentView(outer)
        styleBottomDialog(d, .86f)
        d.show()
    }

    private fun countryRow(country: String, group: List<ServerInfo>, best: ServerInfo, verifiedCount: Int, click: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(Color.rgb(9, 23, 37), 18f, Color.rgb(31, 57, 70), 1)
            addView(TextView(this@UltraMainActivity).apply { text = best.flag; textSize = 28f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(LinearLayout(this@UltraMainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@UltraMainActivity).apply { text = country; textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
                addView(TextView(this@UltraMainActivity).apply {
                    text = "${group.size} endpoints • $verifiedCount verified • best Q${best.quality}"
                    textSize = 10.5f; setTextColor(Color.rgb(118, 146, 163))
                })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(9) })
            addView(TextView(this@UltraMainActivity).apply {
                text = if (best.ping > 0) "${best.ping} ms  ›" else "OPEN  ›"; textSize = 10.5f; setTextColor(Color.rgb(71, 243, 198))
            })
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        }
    }

    private fun showSettingsDialog() {
        val pair = styledDialog("AWR CONTROL CENTER", "Security, routing and device behavior")
        val d = pair.first; val box = pair.second
        box.addView(optionRow("SMART ROUTE", if (autoBest) "Enabled • measures and ranks candidates automatically" else "Disabled • manual server locked", autoBest) {
            autoBest = !autoBest
            if (autoBest && currentServers().isNotEmpty()) selected = BestServerSelector.pick(currentServers(), protocol)
            store.saveServer(selected, autoBest); d.dismiss(); syncUi()
        })
        box.addView(optionRow("SECURE DNS", "${dns.label} • protected inside tunnel", false) { d.dismiss(); showDnsDialog() })
        box.addView(optionRow("ANDROID KILL SWITCH", "Always-on VPN + Block connections without VPN", false) { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) })
        box.addView(optionRow("REFRESH LIVE ROUTES", "Refresh VPN FREE and the unlocked VPN PRO catalog", false) {
            d.dismiss(); loadCatalog(ServerTier.FREE, false); if (vip.isVip()) loadCatalog(ServerTier.PRO, false)
        })
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

    private fun styleBottomDialog(d: Dialog, heightFactor: Float) {
        d.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); attributes = attributes.apply { dimAmount = .84f }; setGravity(Gravity.BOTTOM)
        }
        d.setOnShowListener { d.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * heightFactor).toInt()) }
    }

    private fun optionRow(title: String, sub: String, selectedRow: Boolean, click: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(if (selectedRow) Color.argb(34,71,243,198) else Color.rgb(10,25,39), 18f, if (selectedRow) Color.argb(105,71,243,198) else Color.rgb(34,61,75), 1)
        addView(TextView(this@UltraMainActivity).apply { text = if (selectedRow) "✓  $title" else title; textSize = 14.5f; setTextColor(if (selectedRow) Color.rgb(71,243,198) else Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        addView(TextView(this@UltraMainActivity).apply { text = sub; textSize = 11.5f; setTextColor(Color.rgb(122,149,165)); setPadding(0, dp(4), 0, 0) })
        setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
    }

    private fun tierCard(title: String, subtitle: String, count: Int, accent: Int, locked: Boolean, active: Boolean, click: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(17), dp(17), dp(15), dp(17))
        background = rounded(
            if (active) Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent)) else Color.rgb(9, 23, 38),
            23f,
            if (active) Color.argb(145, Color.red(accent), Color.green(accent), Color.blue(accent)) else Color.rgb(35, 61, 78), 1
        )
        addView(TextView(this@UltraMainActivity).apply {
            text = if (locked) "◇" else if (title.contains("PRO")) "✦" else "◎"; textSize = 27f; gravity = Gravity.CENTER; setTextColor(accent)
            background = rounded(Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)), 18f)
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        addView(LinearLayout(this@UltraMainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@UltraMainActivity).apply { text = title.replace("✦  ", "").replace("◎  ", ""); textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
            addView(TextView(this@UltraMainActivity).apply { text = subtitle; textSize = 10.5f; setTextColor(Color.rgb(119, 147, 165)); maxLines = 2; setPadding(0, dp(4), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        addView(LinearLayout(this@UltraMainActivity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            addView(TextView(this@UltraMainActivity).apply { text = if (locked) "LOCKED" else if (count > 0) count.toString() else "SYNC"; textSize = 12f; setTextColor(accent); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); gravity = Gravity.END })
            addView(TextView(this@UltraMainActivity).apply { text = if (locked) "VIP  ›" else "LIVE  ›"; textSize = 8.5f; setTextColor(Color.rgb(103, 132, 150)); gravity = Gravity.END; setPadding(0, dp(4), 0, 0) })
        })
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    private fun serverRow(server: ServerInfo, selectedRow: Boolean, click: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(13), dp(12), dp(13), dp(12))
        background = rounded(if (selectedRow) Color.argb(34,71,243,198) else Color.rgb(9,23,37), 18f, if (selectedRow) Color.argb(105,71,243,198) else Color.rgb(31,57,70), 1)
        addView(TextView(this@UltraMainActivity).apply { text = server.flag; textSize = 27f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(45), dp(45)))
        addView(LinearLayout(this@UltraMainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@UltraMainActivity).apply { text = server.name; textSize = 14.5f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            addView(TextView(this@UltraMainActivity).apply {
                text = "${if (server.verified) "✓ VERIFIED • " else ""}${server.protocol.uppercase(Locale.US)} • Q${server.quality} • ${speed(server.speedBps)}"
                textSize = 10.2f; setTextColor(if (server.verified) Color.rgb(104, 203, 185) else Color.rgb(118,146,163))
            })
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

    override fun updateState(state: String?, logmessage: String?, localizedResId: Int, level: ConnectionStatus?, intent: Intent?) {
        runOnUiThread {
            val oldPhase = phase
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
                    userDisconnecting = false
                    lastError = ""
                    store.setConnected(true)
                    selected?.let { store.saveServer(it, autoBest) }
                    AwrNotifier.showConnected(this, selected)
                }
                ConnPhase.OFF -> {
                    store.setConnected(false)
                    AwrNotifier.cancel(this)
                    if (!userDisconnecting && oldPhase == ConnPhase.CONNECTING && vip.isVip()) {
                        lastError = "Endpoint dropped • tap reconnect to try the next live route"
                    }
                }
                ConnPhase.ERROR -> {
                    lastError = if (level == ConnectionStatus.LEVEL_AUTH_FAILED) "VPN authentication failed" else logmessage.orEmpty().ifBlank { "Connection interrupted" }
                    store.setConnected(false)
                    AwrNotifier.cancel(this)
                }
                else -> Unit
            }
            syncUi()
        }
    }

    override fun setConnectedVPN(uuid: String?) = Unit

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
        return if (mbps >= 1000) "${DecimalFormat("0.0").format(mbps / 1000)} Gbps" else "${DecimalFormat("0").format(mbps)} Mbps"
    }
}
