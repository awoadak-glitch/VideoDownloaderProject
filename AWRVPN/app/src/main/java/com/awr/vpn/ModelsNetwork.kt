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
import org.json.JSONObject
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import android.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.ln

internal const val API_BASE = "https://awr-license-vercel.vercel.app"
internal const val VPN_REQUEST = 7201

data class VipResult(
    val valid: Boolean,
    val expiresAt: String? = null,
    val message: String = ""
)

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
    val source: String = "AWR",
    val quality: Double = 0.0
)

data class VpnProfileData(
    val server: ServerInfo,
    val ovpn: String
)

enum class ProtocolMode(val label: String) {
    AUTO("AUTO"), UDP("UDP"), TCP("TCP")
}

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
        return try {
            val fmt = java.time.Instant.parse(e)
            java.time.Instant.now().isBefore(fmt)
        } catch (_: Exception) {
            true
        }
    }

    fun save(key: String, expiresAt: String?) {
        prefs.edit()
            .putBoolean("active", true)
            .putString("key", key.trim())
            .putString("expires", expiresAt)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()
}

object AwrApi {
    fun verifyVip(key: String): VipResult {
        return try {
            val conn = URL("$API_BASE/api/verify").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            val body = JSONObject().put("key", key.trim()).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val obj = JSONObject(text.ifBlank { "{}" })
            if (obj.optBoolean("success", false) && obj.optString("auth") == "AWR_OK_2026") {
                VipResult(
                    true,
                    obj.optString("expires_at").takeIf { it.isNotBlank() && it != "null" },
                    "VIP activated"
                )
            } else {
                VipResult(false, null, obj.optString("code", "Invalid VIP code"))
            }
        } catch (e: Exception) {
            VipResult(false, null, "Network error: ${e.message ?: "unknown"}")
        }
    }
}

object ServerRepository {
    private const val VPN_GATE_CSV = "https://www.vpngate.net/api/iphone/"
    private val embeddedProfiles = ConcurrentHashMap<String, String>()

    private fun open(url: String, vipKey: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 22000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("X-AWR-VIP", vipKey)
        conn.setRequestProperty("User-Agent", "AWR-VPN-Android/1.0")
        return conn
    }

    private fun response(conn: HttpURLConnection): JSONObject {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val obj = JSONObject(text.ifBlank { "{}" })
        if (conn.responseCode == 401) error("VIP_REQUIRED")
        if (conn.responseCode !in 200..299 || !obj.optBoolean("success", false)) {
            error(obj.optString("message", obj.optString("code", "Repository unavailable")))
        }
        return obj
    }

    fun list(vipKey: String, protocol: ProtocolMode): List<ServerInfo> {
        val awr = try { listAwr(vipKey, protocol) } catch (_: Exception) { emptyList() }
        val gate = try { listVpnGate(protocol) } catch (_: Exception) { emptyList() }
        val merged = (awr + gate).distinctBy { it.id }
            .sortedWith(compareByDescending<ServerInfo> { it.quality }.thenBy { if (it.ping > 0) it.ping else Int.MAX_VALUE })
        if (merged.isEmpty()) error("No verified VPN endpoints are reachable right now")
        return merged
    }

    private fun listAwr(vipKey: String, protocol: ProtocolMode): List<ServerInfo> {
        val p = protocol.name.lowercase(Locale.US)
        val conn = open("$API_BASE/api/vpn-servers?action=list&protocol=${URLEncoder.encode(p, "UTF-8")}", vipKey)
        val obj = response(conn)
        val arr = obj.optJSONArray("servers") ?: return emptyList()
        val out = ArrayList<ServerInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            out += ServerInfo(
                id = x.optString("id"),
                name = x.optString("name"),
                country = x.optString("country"),
                code = x.optString("country_code"),
                flag = x.optString("flag", "🌐"),
                ping = x.optInt("ping", 0),
                speedBps = x.optLong("speed_bps", 0L),
                sessions = x.optInt("sessions", 0),
                protocol = x.optString("protocol", "auto"),
                source = "AWR",
                quality = quality(x.optInt("ping", 0), x.optLong("speed_bps", 0L), x.optInt("sessions", 0))
            )
        }
        return out
    }

    private fun listVpnGate(protocol: ProtocolMode): List<ServerInfo> {
        if (protocol == ProtocolMode.UDP || protocol == ProtocolMode.TCP || protocol == ProtocolMode.AUTO) {
            val conn = URL(VPN_GATE_CSV).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Accept", "text/plain,text/csv,*/*")
            conn.setRequestProperty("User-Agent", "AWR-VPN-Android/2.1")
            if (conn.responseCode !in 200..299) error("Public relay directory unavailable")
            val rows = conn.inputStream.bufferedReader().use { it.readLines() }
            val out = ArrayList<ServerInfo>()
            for (line in rows) {
                if (line.isBlank() || line.startsWith("*") || line.startsWith("#HostName")) continue
                val c = csv(line)
                if (c.size < 15 || c[14].isBlank()) continue
                val raw = try { String(Base64.decode(c[14], Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { continue }
                val transport = Regex("(?m)^proto\\s+(udp|tcp(?:-client)?)\\s*$", RegexOption.IGNORE_CASE)
                    .find(raw)?.groupValues?.getOrNull(1)?.lowercase(Locale.US) ?: "openvpn"
                if (protocol == ProtocolMode.UDP && !transport.startsWith("udp")) continue
                if (protocol == ProtocolMode.TCP && !transport.startsWith("tcp")) continue
                val host = c[0].ifBlank { c[1] }
                val id = "gate:${host}:${transport}:${raw.hashCode().toUInt()}"
                embeddedProfiles[id] = raw
                val ping = c[3].toIntOrNull() ?: 0
                val speed = c[4].toLongOrNull() ?: 0L
                val sessions = c[7].toIntOrNull() ?: 0
                val country = c[5].ifBlank { "Unknown" }
                val code = c[6].uppercase(Locale.US)
                out += ServerInfo(
                    id = id,
                    name = host,
                    country = country,
                    code = code,
                    flag = flag(code),
                    ping = ping,
                    speedBps = speed,
                    sessions = sessions,
                    protocol = transport,
                    source = "VPN Gate",
                    quality = quality(ping, speed, sessions)
                )
            }
            return out
        }
        return emptyList()
    }

    fun profile(vipKey: String, server: ServerInfo, protocol: ProtocolMode, dns: DnsMode): VpnProfileData {
        embeddedProfiles[server.id]?.let { return finalized(server, it, dns) }
        val p = protocol.name.lowercase(Locale.US)
        val id = URLEncoder.encode(server.id, "UTF-8")
        val conn = open("$API_BASE/api/vpn-servers?action=get&id=$id&protocol=$p", vipKey)
        val obj = response(conn)
        val s = obj.optJSONObject("server") ?: JSONObject()
        val resolved = ServerInfo(
            id = s.optString("id", server.id),
            name = s.optString("name", server.name),
            country = s.optString("country", server.country),
            code = s.optString("country_code", server.code),
            flag = s.optString("flag", server.flag),
            ping = s.optInt("ping", server.ping),
            speedBps = s.optLong("speed_bps", server.speedBps),
            sessions = server.sessions,
            protocol = s.optString("protocol", server.protocol)
        )
        return finalized(resolved, obj.getString("ovpn"), dns)
    }

    fun best(candidates: List<ServerInfo>, limit: Int = 24): ServerInfo? {
        val shortlist = candidates.sortedByDescending { it.quality }.take(limit)
        val pool = Executors.newFixedThreadPool(8)
        val measured = try {
            pool.invokeAll(shortlist.map { server -> Callable { server to probe(server) } })
                .mapNotNull { runCatching { it.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
        return measured
            .filter { it.second in 1..2499 }
            .minByOrNull { (server, measured) -> measured - server.quality.coerceAtMost(500.0).toInt() }
            ?.first ?: shortlist.firstOrNull()
    }

    private fun finalized(server: ServerInfo, ovpn: String, dns: DnsMode): VpnProfileData {
        var cfg = ovpn.trim()
        cfg += "\ndhcp-option DNS ${dns.d1}\ndhcp-option DNS ${dns.d2}\n"
        cfg += "setenv opt block-outside-dns\n"
        return VpnProfileData(server, cfg)
    }

    private fun probe(server: ServerInfo): Int {
        val cfg = embeddedProfiles[server.id] ?: return if (server.ping > 0) server.ping else 2500
        val m = Regex("(?m)^remote\\s+([^\\s]+)\\s+(\\d+)").find(cfg) ?: return 2500
        val started = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(m.groupValues[1], m.groupValues[2].toInt()), 1800) }
            ((System.nanoTime() - started) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (_: Exception) { 2500 }
    }

    private fun quality(ping: Int, speed: Long, sessions: Int): Double {
        val latency = if (ping > 0) 700.0 / (ping + 25.0) else 0.0
        val throughput = if (speed > 0) ln(1.0 + speed / 1_000_000.0) * 18.0 else 0.0
        val load = (sessions.coerceAtLeast(0) * 0.35).coerceAtMost(30.0)
        return latency + throughput - load
    }

    private fun flag(code: String): String {
        if (code.length != 2 || code.any { it !in 'A'..'Z' }) return "🌐"
        return code.map { Character.toChars(0x1F1E6 + (it.code - 'A'.code)).concatToString() }.joinToString("")
    }

    private fun csv(line: String): List<String> {
        val out = ArrayList<String>()
        val value = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { value.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += value.toString(); value.setLength(0) }
                else -> value.append(ch)
            }
            i++
        }
        out += value.toString()
        return out
    }
}

class VpnEngine(private val context: Context) {
    private var pendingProfile: VpnProfile? = null

    fun prepare(data: VpnProfileData): Intent? {
        val parser = ConfigParser()
        parser.parseConfig(StringReader(data.ovpn))
        val profile = parser.convertProfile()
        profile.mName = "AWR • ${data.server.country}"
        profile.mUserEditable = false
        ProfileManager.setTemporaryProfile(context, profile)
        pendingProfile = profile
        return VpnService.prepare(context)
    }

    fun startPrepared() {
        val p = pendingProfile ?: error("VPN profile is not prepared")
        VPNLaunchHelper.startOpenVpn(p, context.applicationContext)
    }

    fun disconnect() {
        val i = Intent(context, OpenVPNService::class.java).apply {
            action = OpenVPNService.DISCONNECT_VPN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
        else context.startService(i)
    }
}
