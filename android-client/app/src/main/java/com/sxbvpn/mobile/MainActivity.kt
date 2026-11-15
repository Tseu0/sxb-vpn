package com.sxbvpn.mobile

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import com.sxbvpn.mobile.network.SxbApiClient
import com.sxbvpn.mobile.security.CryptoVault
import com.sxbvpn.mobile.vpn.SxbVpnService
import kotlin.concurrent.thread

/**
 * Interface Moderne TSEU VPN :
 * - Bouton circulaire central avec chronomètre en direct
 * - Sélecteur des profils déchiffrés (AES-256-GCM)
 * - Carte "Quota du forfait" avec barre de progression et date d'expiration
 * - Écoute réelle du statut TUN (garantit l'affichage de l'icône clé 🔑)
 */
class MainActivity : Activity() {

    private var isVpnConnected = false
    private var secondsElapsed = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private var loadedProfiles: List<SxbApiClient.VpnProfile> = emptyList()

    private lateinit var statusPill: TextView
    private lateinit var powerCircle: LinearLayout
    private lateinit var timerText: TextView
    private lateinit var connectHintText: TextView
    private lateinit var profileSpinner: Spinner
    private lateinit var quotaCardText: TextView
    private lateinit var quotaProgress: ProgressBar

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isVpnConnected) {
                secondsElapsed++
                val h = secondsElapsed / 3600
                val m = (secondsElapsed % 3600) / 60
                val s = secondsElapsed % 60
                timerText.text = String.format("%02d:%02d:%02d", h, m, s)
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val connected = intent?.getBooleanExtra("connected", false) ?: false
            val msg = intent?.getStringExtra("message") ?: ""
            updateConnectionUi(connected, msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("tseu_secure_prefs", MODE_PRIVATE)
        val savedServerUrl = prefs.getString("server_url", "https://elliott-karl-methods-still.trycloudflare.com")
            ?: "https://elliott-karl-methods-still.trycloudflare.com"
        val savedCode = prefs.getString("active_code", "SXB-DEMO-2026") ?: "SXB-DEMO-2026"

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#070E1A"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 56, 44, 48)
        }

        // En-tête
        val header = TextView(this).apply {
            text = "⚡ TSEU VPN"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#06B6D4"))
        }
        val subHeader = TextView(this).apply {
            text = "Tunnel Multi-Protocole Chiffré (AES-256-GCM)"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 4, 0, 28)
        }

        // Carte Sélecteur de Profil
        val profileCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            background = roundedBox("#101B2E", "#1E2F4A", 28f)
        }
        val profileLabel = TextView(this).apply {
            text = "CONFIGURATION ACTIVE"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
        }
        profileSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Activez un code pour charger les serveurs...")
            )
        }
        profileCard.addView(profileLabel)
        profileCard.addView(profileSpinner)

        // Pastille de statut
        statusPill = TextView(this).apply {
            text = "● Déconnecté"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(32, 14, 32, 14)
            background = roundedBox("#101B2E", "#1E2F4A", 50f)
        }
        val pillParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 36
            bottomMargin = 24
        }

        // Gros bouton circulaire central
        powerCircle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = circleBox("#0E1D33", "#1E3A5F")
        }
        val circleParams = LinearLayout.LayoutParams(420, 420).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = 32
        }
        timerText = TextView(this).apply {
            text = "00:00:00"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        connectHintText = TextView(this).apply {
            text = "APPUYER POUR CONNECTER"
            textSize = 11f
            setTextColor(Color.parseColor("#06B6D4"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }
        powerCircle.addView(timerText)
        powerCircle.addView(connectHintText)

        // Carte Quota du forfait
        val quotaCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            background = roundedBox("#101B2E", "#1E2F4A", 28f)
        }
        val quotaTitle = TextView(this).apply {
            text = "QUOTA DU FORFAIT"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
        }
        quotaCardText = TextView(this).apply {
            text = "En attente d'activation du code d'accès"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 10, 0, 14)
        }
        quotaProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        quotaCard.addView(quotaTitle)
        quotaCard.addView(quotaCardText)
        quotaCard.addView(quotaProgress)

        // Carte Configuration Serveur & Code
        val configCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            background = roundedBox("#101B2E", "#1E2F4A", 28f)
        }
        val configParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 24 }

        val serverLabel = TextView(this).apply {
            text = "Serveur TSEU VPN :"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        val serverInput = EditText(this).apply {
            setText(savedServerUrl)
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        val couponLabel = TextView(this).apply {
            text = "Code d'accès (Coupon) :"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 12, 0, 0)
        }
        val couponInput = EditText(this).apply {
            setText(savedCode)
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        configCard.addView(serverLabel)
        configCard.addView(serverInput)
        configCard.addView(couponLabel)
        configCard.addView(couponInput)

        // Action du bouton circulaire central
        powerCircle.setOnClickListener {
            if (isVpnConnected) {
                stopVpnService()
                return@setOnClickListener
            }

            val serverUrl = serverInput.text.toString().trim().removeSuffix("/")
            val code = couponInput.text.toString().trim()
            val hwid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "ANDROID-HWID"
            val model = "${Build.MANUFACTURER} ${Build.MODEL}"

            prefs.edit().putString("server_url", serverUrl).putString("active_code", code).apply()
            val apiClient = SxbApiClient(baseUrl = serverUrl)

            statusPill.text = "● Activation en cours..."
            statusPill.setTextColor(Color.parseColor("#F59E0B"))

            thread {
                try {
                    val res = apiClient.activateCoupon(code, hwid, model, "Android ${Build.VERSION.RELEASE}")
                    runOnUiThread {
                        if (!res.ok) {
                            updateConnectionUi(false, res.error ?: "Code refusé")
                            Toast.makeText(this, res.error, Toast.LENGTH_LONG).show()
                        } else {
                            CryptoVault.encryptToLocalStore(res.decryptedProfilesJson)
                            loadedProfiles = res.profiles
                            if (loadedProfiles.isNotEmpty()) {
                                val names = loadedProfiles.map { "[${it.protocol.uppercase()}] ${it.name}" }
                                profileSpinner.adapter = ArrayAdapter(
                                    this@MainActivity,
                                    android.R.layout.simple_spinner_dropdown_item,
                                    names
                                )
                            }

                            val remMb = res.remainingBytes / (1024 * 1024)
                            val totalMb = (res.quotaBytes / (1024 * 1024)).coerceAtLeast(1)
                            val pct = ((res.usedBytes * 100) / res.quotaBytes).toInt().coerceIn(0, 100)
                            quotaProgress.progress = pct
                            quotaCardText.text = "${res.planName} • Restant : $remMb Mo / $totalMb Mo"

                            requestAndStartVpn()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        updateConnectionUi(false, "Erreur réseau : ${e.message}")
                    }
                }
            }
        }

        root.addView(header)
        root.addView(subHeader)
        root.addView(profileCard)
        root.addView(statusPill, pillParams)
        root.addView(powerCircle, circleParams)
        root.addView(quotaCard)
        root.addView(configCard, configParams)
        scroll.addView(root)
        setContentView(scroll)

        val filter = IntentFilter(SxbVpnService.ACTION_VPN_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(vpnStateReceiver, filter)
        }
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        try { unregisterReceiver(vpnStateReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun updateConnectionUi(connected: Boolean, message: String) {
        isVpnConnected = connected
        if (connected) {
            statusPill.text = "● $message"
            statusPill.setTextColor(Color.parseColor("#10B981"))
            powerCircle.background = circleBox("#064E3B", "#10B981")
            connectHintText.text = "CONNECTÉ (CLÉ 🔑 ACTIVE)"
            connectHintText.setTextColor(Color.parseColor("#6EE7B7"))
            secondsElapsed = 0
            timerHandler.removeCallbacks(timerRunnable)
            timerHandler.postDelayed(timerRunnable, 1000)
        } else {
            timerHandler.removeCallbacks(timerRunnable)
            statusPill.text = "● $message"
            statusPill.setTextColor(Color.parseColor("#94A3B8"))
            powerCircle.background = circleBox("#0E1D33", "#1E3A5F")
            timerText.text = "00:00:00"
            connectHintText.text = "APPUYER POUR CONNECTER"
            connectHintText.setTextColor(Color.parseColor("#06B6D4"))
        }
    }

    private fun requestAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, 100)
        } else {
            onActivityResult(100, RESULT_OK, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            val selectedProfile = loadedProfiles.getOrNull(profileSpinner.selectedItemPosition)?.name ?: "Serveur TSEU"
            val vpnIntent = Intent(this, SxbVpnService::class.java).apply {
                action = SxbVpnService.ACTION_CONNECT
                putExtra("profile_name", selectedProfile)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(vpnIntent)
            } else {
                startService(vpnIntent)
            }
        } else if (requestCode == 100) {
            updateConnectionUi(false, "Permission VPN refusée par l'utilisateur")
        }
    }

    private fun stopVpnService() {
        val vpnIntent = Intent(this, SxbVpnService::class.java).apply {
            action = SxbVpnService.ACTION_DISCONNECT
        }
        startService(vpnIntent)
    }

    private fun roundedBox(bgHex: String, strokeHex: String, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            setStroke(2, Color.parseColor(strokeHex))
            cornerRadius = radius
        }
    }

    private fun circleBox(bgHex: String, strokeHex: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(bgHex))
            setStroke(10, Color.parseColor(strokeHex))
        }
    }
}
