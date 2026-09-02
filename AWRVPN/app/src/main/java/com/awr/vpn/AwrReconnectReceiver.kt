package com.awr.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AwrReconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DISCONNECT -> {
                runCatching { VpnEngine(context).disconnect() }
                SessionStore(context).markConnected(false)
                ReconnectNotifier.show(context, SessionStore(context).server(), "VPN disconnected")
            }
            ACTION_RECONNECT -> reconnect(context)
        }
    }

    private fun reconnect(context: Context) {
        val pending = goAsync()
        Thread {
            try {
                val vip = VipStore(context)
                val session = SessionStore(context)
                val server = session.server()
                if (!vip.isVip() || server == null) {
                    ReconnectNotifier.show(context, server, "Open AWR VPN to restore your session")
                    return@Thread
                }
                val data = ServerRepository.profile(vip.key(), server, session.protocol(), session.dns())
                session.saveServer(data.server)
                val engine = VpnEngine(context)
                val permission = engine.prepare(data)
                if (permission == null) {
                    engine.startPrepared()
                    ReconnectNotifier.show(context, data.server, "Reconnecting securely…")
                } else {
                    ReconnectNotifier.show(context, data.server, "Tap to grant VPN permission")
                }
            } catch (_: Exception) {
                ReconnectNotifier.show(context, SessionStore(context).server(), "Could not reconnect • tap to retry")
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_RECONNECT = "com.awr.vpn.RECONNECT"
        const val ACTION_DISCONNECT = "com.awr.vpn.DISCONNECT"
    }
}

object ReconnectNotifier {
    private const val CHANNEL = "awr_vpn_session"
    private const val ID = 9021

    private fun manager(context: Context): NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager(context).createNotificationChannel(
                NotificationChannel(CHANNEL, "AWR VPN session", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Reconnect and VPN session controls"
                    setShowBadge(false)
                }
            )
        }
    }

    fun show(context: Context, server: ServerInfo?, text: String) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            40,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val reconnect = PendingIntent.getBroadcast(
            context,
            41,
            Intent(context, AwrReconnectReceiver::class.java).setAction(AwrReconnectReceiver.ACTION_RECONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = server?.let { "${it.flag}  AWR VPN • ${it.country}" } ?: "AWR VPN"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
        builder
            .setSmallIcon(com.awr.vpn.R.drawable.ic_stat_awr_vpn)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "RECONNECT", reconnect).build())
        manager(context).notify(ID, builder.build())
    }

    fun cancel(context: Context) = manager(context).cancel(ID)
}
