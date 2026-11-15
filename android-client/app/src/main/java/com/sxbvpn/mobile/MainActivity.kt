package com.sxbvpn.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import com.sxbvpn.mobile.network.SxbApiClient
import com.sxbvpn.mobile.security.CryptoVault
import com.sxbvpn.mobile.vpn.SxbVpnService
import kotlin.concurrent.thread

/**
 * Écran principal de l'application Android SXB VPN :
 * - Champ URL serveur modifiable (pré-rempli avec l'IP de votre PC : http://192.168.43.196:3847)
 * - Saisie du code d'accès (Coupon)
 * - Verrouillage de l'identifiant matériel (HWID)
 * - Stockage chiffré AES-256-GCM dans l'Android Keystore
 * - Bouton de connexion 1-Tap (VpnService) et jauge de quota
 */
class MainActivity : Activity() {

    private var isVpnConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("sxb_secure_prefs", MODE_PRIVATE)
        val savedServerUrl = prefs.getString("server_url", "http://192.168.43.196:3847") ?: "http://192.168.43.196:3847"
        val savedCode = prefs.getString("active_code", "SXB-DEMO-2026") ?: "SXB-DEMO-2026"

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.parseColor("#0B1120"))
        }

        val titleView = TextView(this).apply {
            text = "⚡ SXB VPN"
            textSize = 26f
            setTextColor(Color.parseColor("#06B6D4"))
        }
        val statusView = TextView(this).apply {
            text = "Statut : Déconnecté"
            textSize = 15f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 8, 0, 24)
        }
        val serverLabel = TextView(this).apply {
            text = "Serveur de contrôle (IP PC ou Domaine) :"
            setTextColor(Color.parseColor("#CBD5E1"))
        }
        val serverInput = EditText(this).apply {
            setText(savedServerUrl)
            setTextColor(Color.WHITE)
        }
        val couponLabel = TextView(this).apply {
            text = "Code d'accès (Coupon SXB) :"
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 16, 0, 0)
        }
        val couponInput = EditText(this).apply {
            hint = "Ex: SXB-DEMO-2026"
            setText(savedCode)
            setTextColor(Color.WHITE)
        }
        val quotaView = TextView(this).apply {
            text = "Quota : En attente d'activation"
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 24, 0, 24)
        }
        val connectBtn = Button(this).apply {
            text = "ACTIVER & CONNECTER LE VPN"
        }

        connectBtn.setOnClickListener {
            if (isVpnConnected) {
                stopVpnService()
                isVpnConnected = false
                statusView.text = "Statut : Déconnecté"
                connectBtn.text = "ACTIVER & CONNECTER LE VPN"
                return@setOnClickListener
            }

            val serverUrl = serverInput.text.toString().trim().removeSuffix("/")
            val code = couponInput.text.toString().trim()
            val hwid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "ANDROID-HWID"
            val model = "${Build.MANUFACTURER} ${Build.MODEL}"

            prefs.edit().putString("server_url", serverUrl).putString("active_code", code).apply()
            val apiClient = SxbApiClient(baseUrl = serverUrl)

            statusView.text = "Connexion au serveur et déchiffrement AES-256-GCM..."
            thread {
                try {
                    val res = apiClient.activateCoupon(code, hwid, model, "Android ${Build.VERSION.RELEASE}")
                    runOnUiThread {
                        if (!res.ok) {
                            statusView.text = "Refusé : ${res.error}"
                            Toast.makeText(this, res.error, Toast.LENGTH_LONG).show()
                        } else {
                            val encryptedForKeystore = CryptoVault.encryptToLocalStore(res.decryptedProfilesJson)
                            prefs.edit().putString("vault_profiles", encryptedForKeystore).apply()

                            val remMb = res.remainingBytes / (1024 * 1024)
                            quotaView.text = "Forfait : ${res.planName}\nQuota restant : $remMb Mo\nExpire le : ${res.expiresAt}"
                            requestAndStartVpn()
                            isVpnConnected = true
                            statusView.text = "Statut : VPN ACTIF (Chiffré AES-256-GCM)"
                            connectBtn.text = "DÉCONNECTER LE VPN"
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        statusView.text = "Erreur connexion : ${e.message}"
                    }
                }
            }
        }

        layout.addView(titleView)
        layout.addView(statusView)
        layout.addView(serverLabel)
        layout.addView(serverInput)
        layout.addView(couponLabel)
        layout.addView(couponInput)
        layout.addView(quotaView)
        layout.addView(connectBtn)
        setContentView(layout)
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
            val vpnIntent = Intent(this, SxbVpnService::class.java).apply {
                action = SxbVpnService.ACTION_CONNECT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(vpnIntent)
            } else {
                startService(vpnIntent)
            }
        }
    }

    private fun stopVpnService() {
        val vpnIntent = Intent(this, SxbVpnService::class.java).apply {
            action = SxbVpnService.ACTION_DISCONNECT
        }
        startService(vpnIntent)
    }
}
