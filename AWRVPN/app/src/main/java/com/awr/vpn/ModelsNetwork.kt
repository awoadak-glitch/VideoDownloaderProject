package com.awr.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import org.json.JSONObject
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

internal const val API_BASE = "https://awr-license-vercel.vercel.app"
internal const val VPN_REQUEST = 7201

data class VipResult(val valid: Boolean, val expiresAt: String? = null, val message: String = "")

data class ServerInfo(
    val id: String,
    val name: String,
    val country: String,
    val code: String,
    val flag: String,
    val ping: Int,
    val speedBps: Long,
    val sessions: Int,
    val protocol: String,
    val score: Long = 0L,
    val pingHost: String = ""
)

data class VpnProfileData(val server: ServerInfo, val ovpn: String)

enum class ProtocolMode(val label: String) { AUTO("AUTO"), UDP("UDP"), TCP("TCP") }
enum class DnsMode(val label: String, val d1: String, val d2: String) {
    CLOUDFLARE("1.1.1.1", "1.1.1.1", "1.0.0.1"),
    GOOGLE("8.8.8.8", "8.8.8.8", "8.8.4.4"),
    ADGUARD("ADGUARD", "94.140.14.14", "94.140.15.15")
}
enum class ConnPhase { OFF, FINDING, AUTH, CONNECTING, ON, ERROR }

class VipStore(context: Context) {
    private val prefs = context.getSharedPreferences("awr_vip_ultra", Context.MODE_PRIVATE)
    fun key(): String = prefs.getString("key", "") ?: ""
    fun expires(): String? = prefs.getString("expires", null)
    fun isVip(): Boolean {
        if (!prefs.getBoolean("active", false) || key().isBlank()) return false
        val e = expires() ?: return true
        return try { java.time.Instant.now().isBefore(java.time.Instant.parse(e)) } catch (_: Exception) { true }
    }
    fun save(key: String, expiresAt: String?) = prefs.edit().putBoolean("active", true).putString("key", key.trim()).putString("expires", expiresAt).apply()
    fun clear() = prefs.edit().clear().apply()
}

object AwrApi {
    fun verifyVip(key: String): VipResult = try {
        val conn = URL("$API_BASE/api/verify").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json")
        conn.outputStream.use { it.write(JSONObject().put("key", key.trim()).toString().toByteArray(Charsets.UTF_8)) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val obj = JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty().ifBlank { "{}" })
        if (obj.optBoolean("success") && obj.optString("auth") == "AWR_OK_2026") {
            VipResult(true, obj.optString("expires_at").takeIf { it.isNotBlank() && it != "null" }, "VIP activated")
        } else VipResult(false, null, obj.optString("code", "Invalid VIP code"))
    } catch (e: Exception) { VipResult(false, null, "Network error: ${e.message ?: "unknown"}") }
}

object ServerSelector {
    private fun scoreOf(s: ServerInfo, maxScore: Double, maxSpeed: Double, maxSessions: Double, localPing: Int?): Double {
        val quality = if (maxScore > 0) s.score / maxScore else 0.0
        val speed = if (maxSpeed > 0) ln(1.0 + s.speedBps) / ln(1.0 + maxSpeed) else 0.0
        val p = (localPing ?: s.ping).takeIf { it > 0 }
        val latency = if (p != null) 1.0 / (1.0 + p / 75.0) else 0.34
        val load = if (maxSessions > 0) 1.0 - min(1.0, s.sessions / maxSessions) else 0.55
        val udpBoost = if (s.protocol.equals("udp", true)) 0.018 else 0.0
        return quality * .44 + speed * .30 + latency * .20 + load * .06 + udpBoost
    }

    fun best(list: List<ServerInfo>): ServerInfo? {
        if (list.isEmpty()) return null
        val maxScore = max(1.0, list.maxOf { it.score }.toDouble())
        val maxSpeed = max(1.0, list.maxOf { it.speedBps }.toDouble())
        val maxSessions = max(1.0, list.maxOf { it.sessions }.toDouble())
        val candidates = list.sortedWith(compareByDescending<ServerInfo> { it.score }.thenByDescending { it.speedBps }).take(14)
        var best: ServerInfo? = null
        var bestValue = -1.0
        for (s in candidates) {
            val local = quickReachability(s.pingHost)
            val value = scoreOf(s, maxScore, maxSpeed, maxSessions, local)
            if (value > bestValue) { bestValue = value; best = if (local != null) s.copy(ping = local) else s }
        }
        return best ?: list.first()
    }

    fun quality(s: ServerInfo, list: List<ServerInfo> = listOf(s)): Int {
        val maxScore = max(1.0, list.maxOfOrNull { it.score }?.toDouble() ?: s.score.toDouble().coerceAtLeast(1.0))
        val maxSpeed = max(1.0, list.maxOfOrNull { it.speedBps }?.toDouble() ?: s.speedBps.toDouble().coerceAtLeast(1.0))
        val maxSessions = max(1.0, list.maxOfOrNull { it.sessions }?.toDouble() ?: s.sessions.toDouble().coerceAtLeast(1.0))
        return (scoreOf(s, maxScore, maxSpeed, maxSessions, null) * 100).toInt().coerceIn(1, 99)
    }

    private fun quickReachability(host: String): Int? {
        if (host.isBlank()) return null
        return try {
            val start = System.nanoTime()
            if (!InetAddress.getByName(host).isReachable(650)) null
            else ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (_: Exception) { null }
    }
}

object ServerRepository {
    private fun open(url: String, vipKey: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15000
        readTimeout = 22000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("X-AWR-VIP", vipKey)
        setRequestProperty("User-Agent", "AWR-VPN-Android/2.0")
    }

    private fun response(conn: HttpURLConnection): JSONObject {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val obj = JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty().ifBlank { "{}" })
        if (conn.responseCode == 401) error("VIP_REQUIRED")
        if (conn.responseCode !in 200..299 || !obj.optBoolean("success")) error(obj.optString("message", obj.optString("code", "Repository unavailable")))
        return obj
    }

    fun list(vipKey: String, protocol: ProtocolMode): List<ServerInfo> {
        val p = protocol.name.lowercase(Locale.US)
        val obj = response(open("$API_BASE/api/vpn-servers?action=list&protocol=${URLEncoder.encode(p, "UTF-8")}", vipKey))
        val arr = obj.optJSONArray("servers") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val x = arr.optJSONObject(i) ?: continue
                add(ServerInfo(
                    id = x.optString("id"), name = x.optString("name"), country = x.optString("country"),
                    code = x.optString("country_code"), flag = x.optString("flag", "🌐"), ping = x.optInt("ping"),
                    speedBps = x.optLong("speed_bps"), sessions = x.optInt("sessions"), protocol = x.optString("protocol", "auto"),
                    score = x.optLong("score"), pingHost = x.optString("ping_host")
                ))
            }
        }
    }

    fun profile(vipKey: String, server: ServerInfo, protocol: ProtocolMode, dns: DnsMode): VpnProfileData {
        val p = protocol.name.lowercase(Locale.US)
        val id = URLEncoder.encode(server.id, "UTF-8")
        val obj = response(open("$API_BASE/api/vpn-servers?action=get&id=$id&protocol=$p", vipKey))
        val s = obj.optJSONObject("server") ?: JSONObject()
        val resolved = ServerInfo(
            id = s.optString("id", server.id), name = s.optString("name", server.name), country = s.optString("country", server.country),
            code = s.optString("country_code", server.code), flag = s.optString("flag", server.flag), ping = s.optInt("ping", server.ping),
            speedBps = s.optLong("speed_bps", server.speedBps), sessions = s.optInt("sessions", server.sessions),
            protocol = s.optString("protocol", server.protocol), score = s.optLong("score", server.score), pingHost = s.optString("ping_host", server.pingHost)
        )
        var cfg = obj.getString("ovpn").trim()
        cfg += "\ndhcp-option DNS ${dns.d1}\ndhcp-option DNS ${dns.d2}\nsetenv opt block-outside-dns\n"
        return VpnProfileData(resolved, cfg)
    }
}

class VpnEngine(private val context: Context) {
    private var pendingProfile: VpnProfile? = null
    fun prepare(data: VpnProfileData): Intent? {
        val parser = ConfigParser()
        parser.parseConfig(StringReader(data.ovpn))
        val profile = parser.convertProfile().apply {
            mName = "AWR VPN • ${data.server.country}"
            mUserEditable = false
            mPersistTun = true
            mBlockUnusedAddressFamilies = true
        }
        ProfileManager.setTemporaryProfile(context, profile)
        pendingProfile = profile
        return VpnService.prepare(context)
    }
    fun startPrepared() {
        val p = pendingProfile ?: error("VPN profile is not prepared")
        VPNLaunchHelper.startOpenVpn(p, context.applicationContext, "AWR-VPN", false)
    }
    fun disconnect() {
        val i = Intent(context, OpenVPNService::class.java).apply { action = OpenVPNService.DISCONNECT_VPN }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
    }
    fun isActive(): Boolean = try { VpnStatus.isVPNActive() } catch (_: Exception) { false }
}
