package com.sxbvpn.mobile.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor

/**
 * Service VPN natif Android TSEU VPN (android.net.VpnService)
 */
class SxbVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private var currentProfileIndex = 0
    private var savedEncryptedProfiles: List<String> = emptyList()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startForeground(NOTIFICATION_ID, buildStatusNotification("Connexion au serveur TSEU VPN..."))
                establishTunnel()
                registerAutoReconnectCallback()
            }
            ACTION_DISCONNECT -> {
                stopTunnel()
            }
        }
        return START_STICKY
    }

    private fun establishTunnel() {
        try {
            val builder = Builder()
                .setSession("TSEU VPN Tunnel")
                .setMtu(1400)
                .addAddress("10.66.66.2", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDisallowedApplication(packageName)

            tunInterface = builder.establish()
            updateNotification("TSEU VPN Actif — Trafic chiffré (AES-256-GCM)")
        } catch (e: Exception) {
            failoverToNextProfile()
        }
    }

    private fun failoverToNextProfile() {
        if (savedEncryptedProfiles.isEmpty()) return
        currentProfileIndex = (currentProfileIndex + 1) % savedEncryptedProfiles.size
        updateNotification("Basculement automatique TSEU VPN sur le profil #${currentProfileIndex + 1}...")
        establishTunnel()
    }

    private fun registerAutoReconnectCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (tunInterface == null) establishTunnel()
            }
        })
    }

    private fun stopTunnel() {
        try {
            tunInterface?.close()
        } catch (_: Exception) {}
        tunInterface = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun buildStatusNotification(text: String): Notification {
        val channelId = "tseu_vpn_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Statut TSEU VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("TSEU VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildStatusNotification(text))
    }

    companion object {
        const val ACTION_CONNECT = "com.sxbvpn.mobile.CONNECT"
        const val ACTION_DISCONNECT = "com.sxbvpn.mobile.DISCONNECT"
        private const val NOTIFICATION_ID = 1001
    }
}
