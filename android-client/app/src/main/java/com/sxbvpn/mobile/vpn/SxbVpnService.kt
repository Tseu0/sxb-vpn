package com.sxbvpn.mobile.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import com.sxbvpn.mobile.network.SxbApiClient
import com.sxbvpn.mobile.security.CryptoVault
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Service VPN natif Android TSEU VPN avec moteur Xray/V2Ray (libv2ray.aar) :
 * - Déchiffre les profils exclusivement depuis l'Android Keystore (CryptoVault)
 * - Route le trafic réel via CoreController.startLoop(xrayJson, tunFd)
 * - Synchronise les octets réels consommés toutes les 15s + Kill-Switch automatique
 * - Reconnexion automatique lors des changements réseau (Wi-Fi <-> 4G)
 */
class SxbVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var statsTask: ScheduledFuture<*>? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var activeServerUrl: String = ""
    private var activeCode: String = ""
    private var activeHwid: String = ""
    private var activeProfileId: Int = -1
    private var lastReportedUp: Long = 0L
    private var lastReportedDown: Long = 0L
    @Volatile private var shouldAutoReconnect: Boolean = false
    @Volatile private var isStartingTunnel: Boolean = false

    override fun onCreate() {
        super.onCreate()
        try {
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                activeServerUrl = intent.getStringExtra("server_url") ?: ""
                activeCode = intent.getStringExtra("coupon_code") ?: ""
                activeHwid = intent.getStringExtra("hwid") ?: ""
                activeProfileId = intent.getIntExtra("profile_id", -1)
                val profileName = intent.getStringExtra("profile_name") ?: "Serveur TSEU VPN"
                shouldAutoReconnect = true

                startForeground(NOTIFICATION_ID, buildStatusNotification("Connexion à $profileName..."))
                startXrayTunnel(profileName)
                registerAutoReconnectCallback()
            }
            ACTION_DISCONNECT -> {
                stopTunnel("Déconnecté par l'utilisateur")
            }
        }
        return START_STICKY
    }

    private fun startXrayTunnel(profileName: String) {
        if (isStartingTunnel) return
        isStartingTunnel = true
        Thread {
            try {
                // Lecture sécurisée des profils chiffrés dans l'Android Keystore
                val prefs = getSharedPreferences("tseu_secure_prefs", MODE_PRIVATE)
                val encryptedVault = prefs.getString("vault_profiles", null)
                if (encryptedVault.isNullOrBlank()) {
                    broadcastState(false, "Aucun profil chiffré dans le coffre-fort")
                    stopTunnel("Aucun profil")
                    return@Thread
                }

                val profilesJson = CryptoVault.decryptFromLocalStore(encryptedVault)
                val arr = JSONArray(profilesJson)
                var selectedXrayJson: String? = null
                var selectedName = profileName

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val xj = obj.optString("xray_json", "")
                    if (xj.isNotBlank()) {
                        if (obj.optInt("id") == activeProfileId || selectedXrayJson == null) {
                            selectedXrayJson = xj
                            selectedName = obj.optString("name", profileName)
                            if (obj.optInt("id") == activeProfileId) break
                        }
                    }
                }

                if (selectedXrayJson.isNullOrBlank()) {
                    broadcastState(false, "Profil sélectionné incompatible avec Xray")
                    stopTunnel("Profil incompatible")
                    return@Thread
                }

                // Création de l'interface TUN Android
                val builder = Builder()
                    .setSession("TSEU VPN - $selectedName")
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
                    broadcastState(false, "Autorisation VPN refusée ou autre VPN actif")
                    stopTunnel("Autorisation refusée")
                    return@Thread
                }
                tunInterface = pfd

                // Démarrage du moteur natif Xray-core (libv2ray) sur le descripteur TUN
                val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
                    override fun startup(): Long = 0L
                    override fun shutdown(): Long = 0L
                    override fun onEmitStatus(status: Long, msg: String?): Long = 0L
                })
                coreController = controller
                lastReportedUp = 0L
                lastReportedDown = 0L

                controller.startLoop(selectedXrayJson, pfd.fd)

                updateNotification("TSEU VPN Actif — $selectedName")
                broadcastState(true, "Protection active ($selectedName)")
                startQuotaSyncLoop()
            } catch (e: Exception) {
                broadcastState(false, "Erreur Xray : ${e.message}")
                stopTunnel("Erreur démarrage")
            } finally {
                isStartingTunnel = false
            }
        }.start()
    }

    private fun startQuotaSyncLoop() {
        statsTask?.cancel(false)
        if (activeServerUrl.isBlank() || activeCode.isBlank()) return

        val apiClient = SxbApiClient(activeServerUrl)
        statsTask = scheduler.scheduleWithFixedDelay({
            try {
                if (coreController == null) return@scheduleWithFixedDelay
                val uid = Process.myUid()
                val totalUp = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
                val totalDown = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
                if (lastReportedUp == 0L && lastReportedDown == 0L) {
                    lastReportedUp = totalUp
                    lastReportedDown = totalDown
                }
                val deltaUp = (totalUp - lastReportedUp).coerceAtLeast(0L)
                val deltaDown = (totalDown - lastReportedDown).coerceAtLeast(0L)
                lastReportedUp = totalUp
                lastReportedDown = totalDown

                val sync = apiClient.syncTraffic(activeCode, activeHwid, deltaUp, deltaDown)
                if (sync.quotaBytes > 0) {
                    sendBroadcast(Intent(ACTION_QUOTA_UPDATE).apply {
                        setPackage(packageName)
                        putExtra("quota_bytes", sync.quotaBytes)
                        putExtra("used_bytes", sync.usedBytes)
                        putExtra("remaining_bytes", sync.remainingBytes)
                    })
                }
                if (sync.killSwitch) {
                    stopTunnel(sync.reason ?: "Quota épuisé ou forfait expiré (Kill-Switch)")
                }
            } catch (_: Exception) {
                // Tolérance aux micro-coupures réseau lors du sync
            }
        }, 15, 15, TimeUnit.SECONDS)
    }

    private fun registerAutoReconnectCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        if (networkCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (shouldAutoReconnect && !isStartingTunnel && tunInterface == null && activeCode.isNotBlank()) {
                    startXrayTunnel("Reconnexion automatique")
                }
            }
        }
        networkCallback = cb
        try {
            cm.registerDefaultNetworkCallback(cb)
        } catch (_: Exception) {}
    }

    private fun unregisterAutoReconnectCallback() {
        networkCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    private fun broadcastState(connected: Boolean, message: String) {
        sendBroadcast(Intent(ACTION_VPN_STATE).apply {
            setPackage(packageName)
            putExtra("connected", connected)
            putExtra("message", message)
        })
    }

    private fun stopTunnel(reason: String) {
        shouldAutoReconnect = false
        isStartingTunnel = false
        activeCode = ""
        unregisterAutoReconnectCallback()
        statsTask?.cancel(false)
        statsTask = null
        try {
            coreController?.stopLoop()
        } catch (_: Exception) {}
        coreController = null
        try {
            tunInterface?.close()
        } catch (_: Exception) {}
        tunInterface = null

        broadcastState(false, reason)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onRevoke() {
        stopTunnel("VPN révoqué par le système")
        super.onRevoke()
    }

    override fun onDestroy() {
        shouldAutoReconnect = false
        unregisterAutoReconnectCallback()
        scheduler.shutdownNow()
        super.onDestroy()
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
        const val ACTION_QUOTA_UPDATE = "com.tseuvpn.mobile.QUOTA_UPDATE"
        private const val NOTIFICATION_ID = 1001
    }
}
