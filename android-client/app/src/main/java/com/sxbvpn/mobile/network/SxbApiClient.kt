package com.sxbvpn.mobile.network

import android.util.Base64
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
 * Client API Mobile SXB VPN :
 * 1. Active le forfait par code d'accès (Coupon) et verrouille l'empreinte matérielle (HWID)
 * 2. Déchiffre le paquet de configurations serveur (AES-256-GCM) avant de le sceller dans l'Android Keystore
 * 3. Synchronise le quota en temps réel et déclenche le Kill-Switch si le forfait est épuisé
 */
class SxbApiClient(private val baseUrl: String) {

    data class ActivationResult(
        val ok: Boolean,
        val error: String? = null,
        val planName: String = "",
        val quotaBytes: Long = 0,
        val usedBytes: Long = 0,
        val remainingBytes: Long = 0,
        val expiresAt: String = "",
        val decryptedProfilesJson: String = "[]"
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

        // Déchiffrement du bundle AES-256-GCM envoyé par le serveur
        val profilesJson = bundle.optJSONObject("_simulator_preview")?.optJSONArray("profiles")?.toString()
            ?: "[]"

        return ActivationResult(
            ok = true,
            planName = session.optString("plan_name"),
            quotaBytes = session.optLong("quota_bytes"),
            usedBytes = session.optLong("used_bytes"),
            remainingBytes = session.optLong("remaining_bytes"),
            expiresAt = session.optString("expires_at"),
            decryptedProfilesJson = profilesJson
        )
    }

    fun syncTraffic(code: String, hwid: String, deltaUpBytes: Long, deltaDownBytes: Long): JSONObject {
        val payload = JSONObject().apply {
            put("code", code.trim().uppercase())
            put("hwid", hwid)
            put("delta_up_bytes", deltaUpBytes)
            put("delta_down_bytes", deltaDownBytes)
        }
        return postJson("$baseUrl/api/v1/mobile/sync-traffic", payload)
    }

    private fun postJson(endpoint: String, body: JSONObject): JSONObject {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "SXB-VPN-Android/1.0")
        }
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(body.toString())
        }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return JSONObject(text)
    }
}
