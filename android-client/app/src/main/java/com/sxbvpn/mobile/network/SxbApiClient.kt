package com.sxbvpn.mobile.network

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Client API Mobile TSEU VPN avec gestion robuste des erreurs réseau/JSON.
 */
class SxbApiClient(private val baseUrl: String) {

    data class VpnProfile(
        val id: Int,
        val name: String,
        val protocol: String,
        val serverHost: String,
        val serverPort: Int
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
        val profilesArray = bundle.optJSONObject("_simulator_preview")?.optJSONArray("profiles") ?: JSONArray()

        val profileList = mutableListOf<VpnProfile>()
        for (i in 0 until profilesArray.length()) {
            val obj = profilesArray.getJSONObject(i)
            profileList.add(
                VpnProfile(
                    id = obj.optInt("id"),
                    name = obj.optString("name", "Serveur #${i + 1}"),
                    protocol = obj.optString("protocol", "vless"),
                    serverHost = obj.optString("server_host", ""),
                    serverPort = obj.optInt("server_port", 443)
                )
            )
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

    private fun postJson(endpoint: String, body: JSONObject): JSONObject {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "TSEU-VPN-Android/1.0.4")
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
