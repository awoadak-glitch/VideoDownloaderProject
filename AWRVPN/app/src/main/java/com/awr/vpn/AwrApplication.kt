package com.awr.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import de.blinkt.openvpn.core.GlobalPreferences
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.PrivycsStatusListenerBridge

class AwrApplication : Application() {
    private var statusBridge: Any? = null

    override fun attachBaseContext(base: Context) {
        GlobalPreferences.setInstance(false, false, false)
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        GlobalPreferences.setInstance(false, false, false)
        statusBridge = PrivycsStatusListenerBridge.install(applicationContext)
        createVpnChannels()
    }

    private fun createVpnChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(OpenVPNService.NOTIFICATION_CHANNEL_BG_ID, "AWR VPN tunnel", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(OpenVPNService.NOTIFICATION_CHANNEL_NEWSTATUS_ID, "AWR VPN status", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(OpenVPNService.NOTIFICATION_CHANNEL_USERREQ_ID, "AWR VPN action required", NotificationManager.IMPORTANCE_HIGH)
        ).forEach {
            it.enableLights(true)
            it.lightColor = Color.rgb(71, 243, 198)
            manager.createNotificationChannel(it)
        }
    }
}
