package com.awr.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
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
    val host: String,
    val port: Int,
    val ping: Int,
    val speedBps: Long,
    val sessions: Int,
    val protocol: String,
    val quality: Int = 0,
    val verified: Boolean = false,
    val source: String = "VPN Gate"
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("country", country).put("code", code).put("flag", flag)
        .put("host", host).put("port", port).put("ping", ping).put("speed", speedBps)
        .put("sessions", sessions).put("protocol", protocol).put("quality", quality)
        .put("verified", verified).put("source", source)

    companion object {
        fun fromJson(obj: JSONObject): ServerInfo = ServerInfo(
            id = obj.optString("id"), name = obj.optString("name"), country = obj.optString("country"),
            code = obj.optString("code"), flag = obj.optString("flag", "🌐"), host = obj.optString("host"),
            port = obj.optInt("port"), ping = obj.optInt("ping"), speedBps = obj.optLong("speed"),
            sessions = obj.optInt("sessions"), protocol = obj.optString("protocol", "auto"),
            quality = obj.optInt("quality"), verified = obj.optBoolean("verified"), source = obj.optString("source", "VPN Gate")
        )
    }
}

data class VpnProfileData(val server: ServerInfo, val ovpn: String, val fallback: Boolean = false)

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

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("awr_vpn_session_v2", Context.MODE_PRIVATE)
    fun saveServer(server: ServerInfo?) {
        prefs.edit().putString("server", server?.toJson()?.toString()).apply()
    }
    fun server(): ServerInfo? = try {
        prefs.getString("server", null)?.let { ServerInfo.fromJson(JSONObject(it)) }
    } catch (_: Exception) { null }
    fun setProtocol(mode: ProtocolMode) = prefs.edit().putString("protocol", mode.name).apply()
    fun protocol(): ProtocolMode = runCatching { ProtocolMode.valueOf(prefs.getString("protocol", ProtocolMode.AUTO.name)!!) }.getOrDefault(ProtocolMode.AUTO)
    fun setDns(mode: DnsMode) = prefs.edit().putString("dns", mode.name).apply()
    fun dns(): DnsMode = runCatching { DnsMode.valueOf(prefs.getString("dns", DnsMode.CLOUDFLARE.name)!!) }.getOrDefault(DnsMode.CLOUDFLARE)
    fun markConnected(value: Boolean) {
        val e = prefs.edit().putBoolean("connected", value)
        if (value && prefs.getLong("connected_at", 0L) == 0L) e.putLong("connected_at", System.currentTimeMillis())
        if (!value) e.putLong("connected_at", 0L)
        e.apply()
    }
    fun wasConnected(): Boolean = prefs.getBoolean("connected", false)
    fun connectedAt(): Long = prefs.getLong("connected_at", 0L)
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

object ServerRepository {
    private fun open(url: String, vipKey: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15000
        readTimeout = 25000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("X-AWR-VIP", vipKey)
        setRequestProperty("User-Agent", "AWR-VPN-Android/2.1")
    }

    private fun response(conn: HttpURLConnection): JSONObject {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val obj = JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty().ifBlank { "{}" })
        if (conn.responseCode == 401) error("VIP_REQUIRED")
        if (conn.responseCode !in 200..299 || !obj.optBoolean("success")) error(obj.optString("code", obj.optString("message", "Repository unavailable")))
        return obj
    }

    private fun server(x: JSONObject, fallback: ServerInfo? = null): ServerInfo = ServerInfo(
        id = x.optString("id", fallback?.id ?: ""),
        name = x.optString("name", fallback?.name ?: "AWR Route"),
        country = x.optString("country", fallback?.country ?: "Unknown"),
        code = x.optString("country_code", fallback?.code ?: "--"),
        flag = x.optString("flag", fallback?.flag ?: "🌐"),
        host = x.optString("host", fallback?.host ?: ""),
        port = x.optInt("port", fallback?.port ?: 0),
        ping = x.optInt("ping", fallback?.ping ?: 0),
        speedBps = x.optLong("speed_bps", fallback?.speedBps ?: 0L),
        sessions = x.optInt("sessions", fallback?.sessions ?: 0),
        protocol = x.optString("protocol", fallback?.protocol ?: "auto"),
        quality = x.optInt("quality_score", fallback?.quality ?: 0),
        verified = x.optBoolean("verified", fallback?.verified ?: false),
        source = x.optString("source", fallback?.source ?: "VPN Gate")
    )

    fun list(vipKey: String, protocol: ProtocolMode): List<ServerInfo> {
        val p = protocol.name.lowercase(Locale.US)
        val obj = response(open("$API_BASE/api/vpn-servers?action=list&protocol=${URLEncoder.encode(p, "UTF-8")}", vipKey))
        val arr = obj.optJSONArray("servers") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(server(it)) }
        }
    }

    fun profile(vipKey: String, selected: ServerInfo, protocol: ProtocolMode, dns: DnsMode): VpnProfileData {
        val p = protocol.name.lowercase(Locale.US)
        val id = URLEncoder.encode(selected.id, "UTF-8")
        val country = URLEncoder.encode(selected.code, "UTF-8")
        val obj = response(open("$API_BASE/api/vpn-servers?action=get&id=$id&country=$country&protocol=$p", vipKey))
        val resolved = server(obj.optJSONObject("server") ?: JSONObject(), selected)
        var cfg = obj.getString("ovpn").trim()
        cfg += "\ndhcp-option DNS ${dns.d1}\ndhcp-option DNS ${dns.d2}\nsetenv opt block-outside-dns\n"
        return VpnProfileData(resolved, cfg, obj.optBoolean("fallback", false))
    }

    fun best(vipKey: String, protocol: ProtocolMode, dns: DnsMode, countryCode: String? = null): VpnProfileData {
        val p = protocol.name.lowercase(Locale.US)
        val country = countryCode?.takeIf { it.isNotBlank() }?.let { "&country=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        val obj = response(open("$API_BASE/api/vpn-servers?action=best&protocol=$p$country", vipKey))
        val resolved = server(obj.optJSONObject("server") ?: JSONObject())
        var cfg = obj.getString("ovpn").trim()
        cfg += "\ndhcp-option DNS ${dns.d1}\ndhcp-option DNS ${dns.d2}\nsetenv opt block-outside-dns\n"
        return VpnProfileData(resolved, cfg, obj.optBoolean("fallback", false))
    }
}

object SmartRoute {
    data class Result(val server: ServerInfo, val measuredMs: Int, val score: Double)

    fun pick(list: List<ServerInfo>, mode: ProtocolMode): Result? {
        if (list.isEmpty()) return null
        val candidates = list
            .filter { mode == ProtocolMode.AUTO || it.protocol.equals(mode.name, true) }
            .sortedWith(compareByDescending<ServerInfo> { it.verified }.thenByDescending { it.quality }.thenByDescending { it.speedBps })
            .take(24)
        if (candidates.isEmpty()) return null

        val pool = Executors.newFixedThreadPool(8)
        return try {
            val futures = candidates.map { s -> pool.submit(Callable { measure(s, list) }) }
            futures.mapNotNull { runCatching { it.get() }.getOrNull() }.maxByOrNull { it.score }
        } finally { pool.shutdownNow() }
    }

    private fun measure(s: ServerInfo, all: List<ServerInfo>): Result {
        val measured = if (s.protocol.equals("tcp", true) && s.host.isNotBlank() && s.port > 0) tcpLatency(s.host, s.port) else null
        val latency = measured ?: s.ping.takeIf { it > 0 } ?: 350
        val maxSpeed = max(1.0, all.maxOfOrNull { it.speedBps }?.toDouble() ?: 1.0)
        val speedPart = ln(1.0 + s.speedBps.coerceAtLeast(0).toDouble()) / ln(1.0 + maxSpeed)
        val latencyPart = 1.0 / (1.0 + latency / 80.0)
        val qualityPart = s.quality.coerceIn(0, 100) / 100.0
        val loadPart = 1.0 / (1.0 + s.sessions / 70.0)
        val liveBonus = if (measured != null) .10 else 0.0
        val verifiedBonus = if (s.verified) .06 else 0.0
        val score = qualityPart * .42 + speedPart * .24 + latencyPart * .24 + loadPart * .10 + liveBonus + verifiedBonus
        return Result(if (measured != null) s.copy(ping = measured) else s, latency, score)
    }

    private fun tcpLatency(host: String, port: Int): Int? = try {
        val start = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(host, port), 1100) }
        ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
    } catch (_: Exception) { null }
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
    fun isActive(): Boolean {
        val library = try { VpnStatus.isVPNActive() } catch (_: Exception) { false }
        if (library) return true
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        } catch (_: Exception) { false }
    }
}
