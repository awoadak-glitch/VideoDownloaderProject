package com.awr.vpn

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.VpnStatus
import org.json.JSONObject

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("awr_vpn_session", Context.MODE_PRIVATE)

    fun saveSelection(server: ServerInfo?, protocol: ProtocolMode, dns: DnsMode) {
        val edit = prefs.edit().putString("protocol", protocol.name).putString("dns", dns.name)
        if (server == null) edit.remove("server") else edit.putString("server", JSONObject().apply {
            put("id", server.id); put("name", server.name); put("country", server.country); put("code", server.code)
            put("flag", server.flag); put("ping", server.ping); put("speed", server.speedBps); put("sessions", server.sessions)
            put("protocol", server.protocol); put("score", server.score); put("pingHost", server.pingHost)
        }.toString())
        edit.apply()
    }

    fun loadServer(): ServerInfo? = try {
        val raw = prefs.getString("server", null) ?: return null
        val x = JSONObject(raw)
        ServerInfo(
            id = x.optString("id"), name = x.optString("name"), country = x.optString("country"), code = x.optString("code"),
            flag = x.optString("flag", "🌐"), ping = x.optInt("ping"), speedBps = x.optLong("speed"), sessions = x.optInt("sessions"),
            protocol = x.optString("protocol", "auto"), score = x.optLong("score"), pingHost = x.optString("pingHost")
        )
    } catch (_: Exception) { null }

    fun protocol(): ProtocolMode = try { ProtocolMode.valueOf(prefs.getString("protocol", ProtocolMode.AUTO.name) ?: ProtocolMode.AUTO.name) } catch (_: Exception) { ProtocolMode.AUTO }
    fun dns(): DnsMode = try { DnsMode.valueOf(prefs.getString("dns", DnsMode.CLOUDFLARE.name) ?: DnsMode.CLOUDFLARE.name) } catch (_: Exception) { DnsMode.CLOUDFLARE }

    fun markConnected(value: Boolean) {
        val e = prefs.edit().putBoolean("connected", value)
        if (value) e.putLong("connected_since", System.currentTimeMillis())
        e.apply()
    }
    fun connected(): Boolean = prefs.getBoolean("connected", false)
    fun connectedSince(): Long = prefs.getLong("connected_since", 0L)
}

object AwrNotification {
    private const val CHANNEL = "awr_vpn_control"
    private const val ID = 7227
    const val ACTION_CONNECT = "com.awr.vpn.CONNECT_LAST"
    const val ACTION_DISCONNECT = "com.awr.vpn.DISCONNECT"

    fun init(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "AWR VPN Controls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Reconnect controls and AWR VPN status"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }

    fun showReconnect(context: Context, server: ServerInfo?) {
        init(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val connect = PendingIntent.getBroadcast(context, 10, Intent(context, VpnActionReceiver::class.java).setAction(ACTION_CONNECT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = "AWR VPN • Disconnected"
        val text = if (server != null) "${server.flag} ${server.country} • tap Connect to restore your secure route" else "Tap Connect to restore your secure route"
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn_status)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOngoing(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "CONNECT", connect).build())
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID, n)
    }

    fun showReconnecting(context: Context, server: ServerInfo?) {
        init(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (server != null) "${server.flag} ${server.country} • restoring tunnel…" else "Restoring secure tunnel…"
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn_status)
            .setContentTitle("AWR VPN • Reconnecting")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID, n)
    }

    fun cancel(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID)
}

class AwrVpnApp : Application(), VpnStatus.StateListener {
    private lateinit var session: SessionStore
    override fun onCreate() {
        super.onCreate()
        session = SessionStore(this)
        AwrNotification.init(this)
        VpnStatus.addStateListener(this)
    }

    override fun updateState(state: String?, logmessage: String?, localizedResId: Int, level: ConnectionStatus?) {
        when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> {
                session.markConnected(true)
                AwrNotification.cancel(this)
            }
            ConnectionStatus.LEVEL_AUTH_FAILED, ConnectionStatus.LEVEL_NOTCONNECTED -> {
                val hadSession = session.connected()
                session.markConnected(false)
                if (hadSession || session.loadServer() != null) AwrNotification.showReconnect(this, session.loadServer())
            }
            else -> Unit
        }
    }

    override fun setConnectedVPN(uuid: String?) = Unit
}

class VpnActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AwrNotification.ACTION_DISCONNECT -> VpnEngine(context.applicationContext).disconnect()
            AwrNotification.ACTION_CONNECT -> reconnect(context)
        }
    }

    private fun reconnect(context: Context) {
        val pending = goAsync()
        Thread {
            try {
                val vip = VipStore(context)
                val session = SessionStore(context)
                val server = session.loadServer()
                if (!vip.isVip() || server == null) {
                    val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("from_notification", true)
                    context.startActivity(i)
                    return@Thread
                }
                AwrNotification.showReconnecting(context, server)
                val data = ServerRepository.profile(vip.key(), server, session.protocol(), session.dns())
                session.saveSelection(data.server, session.protocol(), session.dns())
                val engine = VpnEngine(context.applicationContext)
                val permission = engine.prepare(data)
                if (permission == null) {
                    engine.startPrepared()
                } else {
                    val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("connect_last", true)
                    context.startActivity(i)
                }
            } catch (_: Exception) {
                AwrNotification.showReconnect(context, SessionStore(context).loadServer())
            } finally {
                pending.finish()
            }
        }.start()
    }
}
