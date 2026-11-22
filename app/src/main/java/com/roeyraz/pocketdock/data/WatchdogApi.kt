package com.roeyraz.pocketdock.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class MonitorItem(
    val id: String,
    val name: String,
    val url: String,
    val kind: String,
    val intervalMinutes: Int,
    val enabled: Boolean,
    val state: String,
    val lastChecked: Long,
    val latencyMs: Int,
    val error: String,
)

data class MonitorEvent(val name: String, val state: String, val message: String, val timestamp: Long)
data class RunnerDashboard(
    val checks: List<MonitorItem>,
    val history: List<MonitorEvent>,
    val notifications: Boolean,
    val enabled: Int,
    val down: Int,
)

object RunnerPairing {
    private const val ALIAS = "pocketdock_runner_pairing_v03"
    private const val PREF = "pocketdock_runner_secure"
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    fun hasToken(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).contains("auth")

    fun save(context: Context, token: String) {
        require(token.length in 30..200 && !token.contains("\n")) { "Invalid token" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val buffer = cipher.iv + cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("auth", Base64.encodeToString(buffer, Base64.NO_WRAP)).apply()
    }

    fun read(context: Context): String {
        val encoded = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("auth", null) ?: return ""
        val buffer = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(),
            GCMParameterSpec(128, buffer.copyOfRange(0, 12)))
        return String(cipher.doFinal(buffer.copyOfRange(12, buffer.size)), Charsets.UTF_8)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove("auth").apply()
    }
}

object WatchdogApi {
    private const val BASE = "http://127.0.0.1:8799"

    private fun request(context: Context, path: String, method: String = "GET",
                        body: JSONObject? = null): JSONObject {
        val connection = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5500
            connection.readTimeout = 6500
            connection.setRequestProperty("Authorization", "Bearer " + RunnerPairing.read(context))
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (text.isNotBlank()) JSONObject(text) else JSONObject()
            if (code !in 200..299) throw IllegalStateException(json.optString("error", "HTTP $code"))
            return json
        } finally {
            connection.disconnect()
        }
    }

    suspend fun overview(context: Context): RunnerDashboard = withContext(Dispatchers.IO) {
        val result = request(context, "/api/overview")
        val checks = result.optJSONArray("checks") ?: JSONArray()
        val events = result.optJSONArray("history") ?: JSONArray()
        val monitors = buildList {
            for (i in 0 until checks.length()) {
                val obj = checks.optJSONObject(i) ?: continue
                add(MonitorItem(
                    id = obj.optString("id"),
                    name = obj.optString("name"),
                    url = obj.optString("url"),
                    kind = obj.optString("kind", "http"),
                    intervalMinutes = obj.optInt("interval_minutes", 15),
                    enabled = obj.optBoolean("enabled", true),
                    state = obj.optString("last_state").takeUnless { it == "null" }.orEmpty(),
                    lastChecked = obj.optDouble("last_checked", 0.0).toLong(),
                    latencyMs = obj.optInt("last_latency_ms", 0),
                    error = obj.optString("last_error"),
                ))
            }
        }
        val history = buildList {
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                add(MonitorEvent(
                    name = event.optString("name"),
                    state = event.optString("state"),
                    message = event.optString("message"),
                    timestamp = event.optDouble("timestamp", 0.0).toLong(),
                ))
            }
        }
        RunnerDashboard(
            checks = monitors,
            history = history,
            notifications = result.optJSONObject("settings")?.optBoolean("notifications") ?: false,
            enabled = result.optJSONObject("totals")?.optInt("enabled") ?: 0,
            down = result.optJSONObject("totals")?.optInt("down") ?: 0,
        )
    }

    suspend fun add(context: Context, name: String, url: String, intervalMinutes: Int): Unit =
        withContext(Dispatchers.IO) {
            request(context, "/api/checks", "POST", JSONObject()
                .put("name", name).put("url", url).put("interval_minutes", intervalMinutes))
        }

    suspend fun toggle(context: Context, id: String, enabled: Boolean): Unit =
        withContext(Dispatchers.IO) {
            request(context, "/api/checks/$id/toggle", "POST", JSONObject().put("enabled", enabled))
        }

    suspend fun runNow(context: Context, id: String): Unit =
        withContext(Dispatchers.IO) {
            request(context, "/api/checks/$id/run", "POST", JSONObject())
        }

    suspend fun remove(context: Context, id: String): Unit = withContext(Dispatchers.IO) {
        request(context, "/api/checks/$id", "DELETE")
    }

    suspend fun notifications(context: Context, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        request(context, "/api/settings", "POST", JSONObject().put("notifications", enabled))
    }
}
