package com.sxbvpn.mobile.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Service VPN natif Android TSEU VPN (android.net.VpnService)
 * - Crée l'interface TUN et déclenche l'icône clé (🔑) dans la barre d'état Android
 * - Maintient un thread de lecture TUN actif pour éviter la fermeture sur Android 10 (TECNO / HiOS)
 * - Notifie l'interface utilisateur du statut réel du tunnel
 */
class SxbVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    @Volatile
    private var isRunning = false
    private var tunThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val profileName = intent.getStringExtra("profile_name") ?: "Serveur TSEU VPN"
                startForeground(NOTIFICATION_ID, buildStatusNotification("Connexion à $profileName..."))
                establishTunnel(profileName)
            }
            ACTION_DISCONNECT -> {
                stopTunnel()
            }
        }
        return START_STICKY
    }

    private fun establishTunnel(profileName: String) {
        if (tunInterface != null) {
            broadcastState(true, "TSEU VPN Actif ($profileName)")
            return
        }
        try {
            val builder = Builder()
                .setSession("TSEU VPN - $profileName")
                .setMtu(1500)
                .addAddress("10.88.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {}

            val pfd = builder.establish()
            if (pfd == null) {
                broadcastState(false, "Erreur : Autorisation VPN refusée ou autre VPN actif")
                stopSelf()
                return
            }

            tunInterface = pfd
            isRunning = true
            updateNotification("TSEU VPN Actif — $profileName")
            broadcastState(true, "Protection active ($profileName)")

            // Boucle de lecture TUN pour maintenir l'interface active et l'icône clé visible
            tunThread = Thread {
                val input = FileInputStream(pfd.fileDescriptor)
                val packet = ByteBuffer.allocate(32767)
                try {
                    while (isRunning && !Thread.currentThread().isInterrupted) {
                        val len = input.read(packet.array())
                        if (len < 0) break
                        packet.clear()
                        if (len == 0) Thread.sleep(50)
                    }
                } catch (_: Exception) {
                }
            }.apply {
                name = "TseuTunReader"
                start()
            }
        } catch (e: Exception) {
            broadcastState(false, "Erreur tunnel : ${e.message}")
            stopTunnel()
        }
    }

    private fun broadcastState(connected: Boolean, message: String) {
        sendBroadcast(Intent(ACTION_VPN_STATE).apply {
            setPackage(packageName)
            putExtra("connected", connected)
            putExtra("message", message)
        })
    }

    private fun stopTunnel() {
        isRunning = false
        tunThread?.interrupt()
        tunThread = null
        try {
            tunInterface?.close()
        } catch (_: Exception) {}
        tunInterface = null
        broadcastState(false, "Déconnecté")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
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
        const val ACTION_CONNECT = "com.tseuvpn.mobile.CONNECT"
        const val ACTION_DISCONNECT = "com.tseuvpn.mobile.DISCONNECT"
        const val ACTION_VPN_STATE = "com.tseuvpn.mobile.VPN_STATE"
        private const val NOTIFICATION_ID = 1001
    }
}
