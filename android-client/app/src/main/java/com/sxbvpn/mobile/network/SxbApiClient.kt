package com.sxbvpn.mobile.network

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client API Mobile TSEU VPN (Sécurité Boîte Noire SXB) :
 * - Aucune configuration n'est transmise en clair sur le réseau
 * - Déchiffrement authentifié AES-256-GCM (PBKDF2WithHmacSHA256) lié au HWID de l'appareil
 * - Synchronisation des quotas et gestion du Kill-Switch serveur
 */
class SxbApiClient(private val baseUrl: String) {

    companion object {
        // Clé partagée pour la dérivation PBKDF2-HMAC-SHA256 liée au HWID
        private const val MASTER_SECRET = "sxb-ultra-secret-master-key-2026-aes256gcm"
    }

    data class VpnProfile(
        val id: Int,
        val name: String,
        val protocol: String,
        val xrayJson: String
    )

    data class ActivationResult(
        val ok: Boolean,
        val error: String? = null,
        val planName: String = "",
        val quotaBytes: Long = 0,
        val usedBytes: Long = 0,
        val remainingBytes: Long = 0,
        val expiresAt: String = "",
        val decryptedProfilesJson: String = "[]",
        val profiles: List<VpnProfile> = emptyList()
    )

    data class SyncResult(
        val ok: Boolean,
        val killSwitch: Boolean,
        val reason: String? = null,
        val quotaBytes: Long = 0,
        val usedBytes: Long = 0,
        val remainingBytes: Long = 0
    )

    fun activateCoupon(code: String, hwid: String, deviceModel: String, androidVersion: String): ActivationResult {
        val payload = JSONObject().apply {
            put("code", code.trim().uppercase())
            put("hwid", hwid)
            put("device_model", deviceModel)
            put("android_version", androidVersion)
        }

        val response = postJson("$baseUrl/api/v1/mobile/activate", payload)
        if (!response.optBoolean("ok", false)) {
            return ActivationResult(ok = false, error = response.optString("error", "Erreur d'activation"))
        }

        val session = response.getJSONObject("session")
        val bundle = response.getJSONObject("encrypted_profiles_bundle")

        // Déchiffrement réel AES-256-GCM du payload envoyé par le serveur
        val decryptedPayload = decryptServerBundle(bundle, hwid)
        val profilesArray = decryptedPayload.optJSONArray("profiles") ?: JSONArray()

        val profileList = mutableListOf<VpnProfile>()
        for (i in 0 until profilesArray.length()) {
            val obj = profilesArray.getJSONObject(i)
            val xrayJson = obj.optString("xray_json", "")
            if (xrayJson.isNotBlank()) {
                profileList.add(
                    VpnProfile(
                        id = obj.optInt("id"),
                        name = obj.optString("name", "Serveur #${i + 1}"),
                        protocol = obj.optString("protocol", "vmess"),
                        xrayJson = xrayJson
                    )
                )
            }
        }

        return ActivationResult(
            ok = true,
            planName = session.optString("plan_name"),
            quotaBytes = session.optLong("quota_bytes"),
            usedBytes = session.optLong("used_bytes"),
            remainingBytes = session.optLong("remaining_bytes"),
            expiresAt = session.optString("expires_at"),
            decryptedProfilesJson = profilesArray.toString(),
            profiles = profileList
        )
    }

    fun syncTraffic(code: String, hwid: String, deltaUpBytes: Long, deltaDownBytes: Long): SyncResult {
        val payload = JSONObject().apply {
            put("code", code.trim().uppercase())
            put("hwid", hwid)
            put("delta_up_bytes", deltaUpBytes)
            put("delta_down_bytes", deltaDownBytes)
        }
        val res = postJson("$baseUrl/api/v1/mobile/sync-traffic", payload)
        val session = res.optJSONObject("session")
        return SyncResult(
            ok = res.optBoolean("ok", false),
            killSwitch = res.optBoolean("kill_switch", false),
            reason = res.optString("reason", null),
            quotaBytes = session?.optLong("quota_bytes") ?: 0L,
            usedBytes = session?.optLong("used_bytes") ?: 0L,
            remainingBytes = session?.optLong("remaining_bytes") ?: 0L
        )
    }

    private fun decryptServerBundle(bundle: JSONObject, hwid: String): JSONObject {
        val iv = Base64.decode(bundle.getString("iv_base64"), Base64.DEFAULT)
        val tag = Base64.decode(bundle.getString("tag_base64"), Base64.DEFAULT)
        val ciphertext = Base64.decode(bundle.getString("ciphertext_base64"), Base64.DEFAULT)

        // En Java/Android AES/GCM/NoPadding attend ciphertext + tag concaténés
        val combined = ByteArray(ciphertext.size + tag.size)
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
        System.arraycopy(tag, 0, combined, ciphertext.size, tag.size)

        val salt = "tseu-salt-$hwid".toByteArray(StandardCharsets.UTF_8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(MASTER_SECRET.toCharArray(), salt, 10000, 256)
        val keyBytes = factory.generateSecret(spec).encoded
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val plaintext = String(cipher.doFinal(combined), StandardCharsets.UTF_8)
        return JSONObject(plaintext)
    }

    private fun postJson(endpoint: String, body: JSONObject): JSONObject {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "TSEU-VPN-Android/1.1.0")
        }
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(body.toString())
        }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val text = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", "Réponse serveur invalide (HTTP ${conn.responseCode}) : ${text.take(80)}")
            }
        }
    }
}
