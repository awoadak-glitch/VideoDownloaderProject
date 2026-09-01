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
import kotlin.math.max

internal const val ACTION_AWR_DISCONNECT = "com.awr.vpn.action.DISCONNECT"
internal const val ACTION_AWR_RECONNECT = "com.awr.vpn.action.RECONNECT"
internal const val EXTRA_RECONNECT = "awr_reconnect"

class ConnectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("awr_connection_ultra", Context.MODE_PRIVATE)

    fun saveServer(server: ServerInfo?, autoBest: Boolean) {
        val e = prefs.edit().putBoolean("auto_best", autoBest)
        if (server == null) {
            e.remove("server_id").remove("server_name").remove("server_country")
                .remove("server_code").remove("server_flag").remove("server_ping")
                .remove("server_speed").remove("server_sessions").remove("server_protocol")
                .remove("server_score")
        } else {
            e.putString("server_id", server.id)
                .putString("server_name", server.name)
                .putString("server_country", server.country)
                .putString("server_code", server.code)
                .putString("server_flag", server.flag)
                .putInt("server_ping", server.ping)
                .putLong("server_speed", server.speedBps)
                .putInt("server_sessions", server.sessions)
                .putString("server_protocol", server.protocol)
                .putLong("server_score", server.score)
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
            ping = prefs.getInt("server_ping", 0),
            speedBps = prefs.getLong("server_speed", 0L),
            sessions = prefs.getInt("server_sessions", 0),
            protocol = prefs.getString("server_protocol", "auto") ?: "auto",
            score = prefs.getLong("server_score", 0L)
        )
    }

    fun autoBest(): Boolean = prefs.getBoolean("auto_best", true)
    fun setConnected(value: Boolean) = prefs.edit().putBoolean("connected", value).apply()
    fun wasConnected(): Boolean = prefs.getBoolean("connected", false)

    fun saveProtocol(value: ProtocolMode) = prefs.edit().putString("protocol", value.name).apply()
    fun protocol(): ProtocolMode = try {
        ProtocolMode.valueOf(prefs.getString("protocol", ProtocolMode.AUTO.name) ?: ProtocolMode.AUTO.name)
    } catch (_: Exception) { ProtocolMode.AUTO }

    fun saveDns(value: DnsMode) = prefs.edit().putString("dns", value.name).apply()
    fun dns(): DnsMode = try {
        DnsMode.valueOf(prefs.getString("dns", DnsMode.CLOUDFLARE.name) ?: DnsMode.CLOUDFLARE.name)
    } catch (_: Exception) { DnsMode.CLOUDFLARE }
}

object BestServerSelector {
    fun pick(servers: List<ServerInfo>, protocol: ProtocolMode): ServerInfo? {
        if (servers.isEmpty()) return null
        val candidates = when (protocol) {
            ProtocolMode.AUTO -> servers
            ProtocolMode.UDP -> servers.filter { it.protocol.equals("udp", true) }.ifEmpty { servers }
            ProtocolMode.TCP -> servers.filter { it.protocol.equals("tcp", true) }.ifEmpty { servers }
        }
        val maxScore = max(1L, candidates.maxOfOrNull { it.score } ?: 1L).toDouble()
        val maxSpeed = max(1L, candidates.maxOfOrNull { it.speedBps } ?: 1L).toDouble()
        return candidates.maxByOrNull { s ->
            val provider = (s.score.coerceAtLeast(0L) / maxScore).coerceIn(0.0, 1.0)
            val speed = (s.speedBps.coerceAtLeast(0L) / maxSpeed).coerceIn(0.0, 1.0)
            val latency = if (s.ping > 0) (1.0 - s.ping.coerceAtMost(600) / 600.0) else 0.38
            val load = (1.0 / (1.0 + s.sessions.coerceAtLeast(0) / 180.0)).coerceIn(0.0, 1.0)
            provider * 0.55 + speed * 0.24 + latency * 0.16 + load * 0.05
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

        val disconnectIntent = Intent(context, AwrVpnActionReceiver::class.java).apply { action = ACTION_AWR_DISCONNECT }
        val disconnectPi = PendingIntent.getBroadcast(context, 111, disconnectIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val reconnectIntent = Intent(context, UltraMainActivity::class.java).apply {
            action = ACTION_AWR_RECONNECT
            putExtra(EXTRA_RECONNECT, true)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val reconnectPi = PendingIntent.getActivity(context, 112, reconnectIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val title = "AWR VPN • Protected"
        val country = server?.let { "${it.flag} ${it.country}" } ?: "Secure tunnel"
        val ping = server?.ping?.takeIf { it > 0 }?.let { " • ${it} ms" }.orEmpty()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_awr_vpn_notify)
            .setContentTitle(title)
            .setContentText("$country$ping • Tap to open")
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
            try { VpnEngine(context.applicationContext).disconnect() } catch (_: Exception) { }
            ConnectionStore(context).setConnected(false)
            AwrNotifier.cancel(context)
        }
    }
}
