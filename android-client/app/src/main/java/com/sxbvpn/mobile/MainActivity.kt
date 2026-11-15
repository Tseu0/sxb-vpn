package com.sxbvpn.mobile

import android.app.Activity
import android.content.Intent
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
 * - Saisie du code d'accès (Coupon)
 * - Verrouillage de l'identifiant matériel (HWID)
 * - Stockage chiffré AES-256-GCM dans l'Android Keystore
 * - Bouton de connexion 1-Tap (VpnService) et jauge de quota
 */
class MainActivity : Activity() {

    private lateinit var apiClient: SxbApiClient
    private var isVpnConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // URL de votre Backend SXB VPN (10.0.2.2 correspond au localhost PC depuis l'émulateur Android,
        // ou l'IP/domaine de votre serveur en production)
        apiClient = SxbApiClient(baseUrl = "http://10.0.2.2:3847")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        val titleView = TextView(this).apply {
            text = "SXB VPN"
            textSize = 26f
        }
        val statusView = TextView(this).apply {
            text = "Statut : Déconnecté"
            textSize = 16f
        }
        val couponInput = EditText(this).apply {
            hint = "Entrez votre code d'accès (ex: SXB-DEMO-2026)"
            setText("SXB-DEMO-2026")
        }
        val quotaView = TextView(this).apply {
            text = "Quota : Non activé"
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

            val code = couponInput.text.toString().trim()
            val hwid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "ANDROID-HWID"
            val model = "${Build.MANUFACTURER} ${Build.MODEL}"

            statusView.text = "Vérification du code et déchiffrement des profils..."
            thread {
                try {
                    val res = apiClient.activateCoupon(code, hwid, model, "Android ${Build.VERSION.RELEASE}")
                    runOnUiThread {
                        if (!res.ok) {
                            statusView.text = "Refusé : ${res.error}"
                            Toast.makeText(this, res.error, Toast.LENGTH_LONG).show()
                        } else {
                            // Chiffrement dans l'Android Keystore (AES-256-GCM)
                            val encryptedForKeystore = CryptoVault.encryptToLocalStore(res.decryptedProfilesJson)
                            getSharedPreferences("sxb_secure_prefs", MODE_PRIVATE)
                                .edit()
                                .putString("vault_profiles", encryptedForKeystore)
                                .putString("active_code", code)
                                .apply()

                            val remMb = res.remainingBytes / (1024 * 1024)
                            quotaView.text = "Forfait : ${res.planName}\nQuota restant : $remMb Mo\nExpire le : ${res.expiresAt}"
                            requestAndStartVpn()
                            isVpnConnected = true
                            statusView.text = "Statut : VPN ACTIF (Chiffré AES-256-GCM)"
                            connectBtn.text = "DÉCONNECTER"
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        statusView.text = "Erreur réseau : ${e.message}"
                    }
                }
            }
        }

        layout.addView(titleView)
        layout.addView(statusView)
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
