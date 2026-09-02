package com.awr.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlin.math.ln
import kotlin.math.max

internal const val ACTION_AWR_DISCONNECT = "com.awr.vpn.action.DISCONNECT"
internal const val ACTION_AWR_RECONNECT = "com.awr.vpn.action.RECONNECT"
internal const val EXTRA_RECONNECT = "awr_reconnect"

class ConnectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("awr_connection_ultra", Context.MODE_PRIVATE)

    fun saveServer(server: ServerInfo?, autoBest: Boolean) {
        val e = prefs.edit().putBoolean("auto_best", autoBest)
        if (server == null) {
            listOf(
                "server_id", "server_name", "server_country", "server_code", "server_flag", "server_host",
                "server_port", "server_ping", "server_speed", "server_sessions", "server_protocol",
                "server_quality", "server_verified", "server_source"
            ).forEach(e::remove)
        } else {
            e.putString("server_id", server.id)
                .putString("server_name", server.name)
                .putString("server_country", server.country)
                .putString("server_code", server.code)
                .putString("server_flag", server.flag)
                .putString("server_host", server.host)
                .putInt("server_port", server.port)
                .putInt("server_ping", server.ping)
                .putLong("server_speed", server.speedBps)
                .putInt("server_sessions", server.sessions)
                .putString("server_protocol", server.protocol)
                .putInt("server_quality", server.quality)
                .putBoolean("server_verified", server.verified)
                .putString("server_source", server.source)
        }
        e.apply()
    }

    fun loadServer(): ServerInfo? {
        val id = prefs.getString("server_id", null) ?: return null
        return ServerInfo(
            id = id,
            name = prefs.getString("server_name", "AWR Server") ?: "AWR Server",
            country = prefs.getString("server_country", "Secure route") ?: "Secure route",
            code = prefs.getString("server_code", "--") ?: "--",
            flag = prefs.getString("server_flag", "🌐") ?: "🌐",
            host = prefs.getString("server_host", "") ?: "",
            port = prefs.getInt("server_port", 0),
            ping = prefs.getInt("server_ping", 0),
            speedBps = prefs.getLong("server_speed", 0L),
            sessions = prefs.getInt("server_sessions", 0),
            protocol = prefs.getString("server_protocol", "auto") ?: "auto",
            quality = prefs.getInt("server_quality", 0),
            verified = prefs.getBoolean("server_verified", false),
            source = prefs.getString("server_source", "VPN Gate") ?: "VPN Gate"
        )
    }

    fun autoBest(): Boolean = prefs.getBoolean("auto_best", true)

    fun setConnected(value: Boolean) {
        val e = prefs.edit().putBoolean("connected", value)
        if (value && prefs.getLong("connected_at", 0L) <= 0L) e.putLong("connected_at", System.currentTimeMillis())
        if (!value) e.putLong("connected_at", 0L)
        e.apply()
    }

    fun wasConnected(): Boolean = prefs.getBoolean("connected", false)
    fun connectedAt(): Long = prefs.getLong("connected_at", 0L)

    fun saveProtocol(value: ProtocolMode) = prefs.edit().putString("protocol", value.name).apply()
    fun protocol(): ProtocolMode = runCatching {
        ProtocolMode.valueOf(prefs.getString("protocol", ProtocolMode.AUTO.name) ?: ProtocolMode.AUTO.name)
    }.getOrDefault(ProtocolMode.AUTO)

    fun saveDns(value: DnsMode) = prefs.edit().putString("dns", value.name).apply()
    fun dns(): DnsMode = runCatching {
        DnsMode.valueOf(prefs.getString("dns", DnsMode.CLOUDFLARE.name) ?: DnsMode.CLOUDFLARE.name)
    }.getOrDefault(DnsMode.CLOUDFLARE)
}

object BestServerSelector {
    fun pick(servers: List<ServerInfo>, protocol: ProtocolMode): ServerInfo? {
        if (servers.isEmpty()) return null
        val candidates = when (protocol) {
            ProtocolMode.AUTO -> servers
            ProtocolMode.UDP -> servers.filter { it.protocol.equals("udp", true) }.ifEmpty { servers }
            ProtocolMode.TCP -> servers.filter { it.protocol.equals("tcp", true) }.ifEmpty { servers }
        }
        val maxSpeed = max(1L, candidates.maxOfOrNull { it.speedBps } ?: 1L).toDouble()
        return candidates.maxByOrNull { s ->
            val quality = s.quality.coerceIn(0, 100) / 100.0
            val speed = ln(1.0 + s.speedBps.coerceAtLeast(0).toDouble()) / ln(1.0 + maxSpeed)
            val latency = if (s.ping > 0) 1.0 / (1.0 + s.ping / 85.0) else 0.32
            val load = 1.0 / (1.0 + s.sessions.coerceAtLeast(0) / 120.0)
            val verified = if (s.verified) 0.12 else 0.0
            quality * 0.44 + speed * 0.24 + latency * 0.22 + load * 0.10 + verified
        }
    }
}

object AwrNotifier {
    private const val CHANNEL = "awr_vpn_control"
    private const val ID = 8821

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "AWR VPN Control", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Connection status and secure tunnel controls"
                enableLights(true)
                lightColor = Color.rgb(71, 243, 198)
                setShowBadge(false)
            }
            manager(context).createNotificationChannel(channel)
        }
    }

    fun showConnected(context: Context, server: ServerInfo?) {
        ensureChannel(context)
        val openIntent = Intent(context, UltraMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(context, 110, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val disconnectPi = PendingIntent.getBroadcast(
            context,
            111,
            Intent(context, AwrVpnActionReceiver::class.java).apply { action = ACTION_AWR_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val reconnectPi = PendingIntent.getActivity(
            context,
            112,
            Intent(context, UltraMainActivity::class.java).apply {
                action = ACTION_AWR_RECONNECT
                putExtra(EXTRA_RECONNECT, true)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val country = server?.let { "${it.flag} ${it.country}" } ?: "Secure tunnel"
        val stats = buildString {
            append(country)
            server?.ping?.takeIf { it > 0 }?.let { append(" • ${it} ms") }
            server?.quality?.takeIf { it > 0 }?.let { append(" • Q$it") }
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_awr_vpn_notify)
            .setContentTitle("AWR VPN • PROTECTED")
            .setContentText(stats)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "DISCONNECT", disconnectPi)
            .addAction(0, "RECONNECT", reconnectPi)
            .build()
        manager(context).notify(ID, notification)
    }

    fun cancel(context: Context) = manager(context).cancel(ID)
}

class AwrVpnActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_AWR_DISCONNECT) {
            runCatching { VpnEngine(context.applicationContext).disconnect() }
            ConnectionStore(context).setConnected(false)
            AwrNotifier.cancel(context)
        }
    }
}
