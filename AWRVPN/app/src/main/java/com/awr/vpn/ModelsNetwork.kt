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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

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
    val protocol: String
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
                protocol = x.optString("protocol", "auto")
            )
        }
        return out
    }

    fun profile(vipKey: String, server: ServerInfo, protocol: ProtocolMode, dns: DnsMode): VpnProfileData {
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
        var cfg = obj.getString("ovpn").trim()
        cfg += "\ndhcp-option DNS ${dns.d1}\ndhcp-option DNS ${dns.d2}\n"
        cfg += "setenv opt block-outside-dns\n"
        return VpnProfileData(resolved, cfg)
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
