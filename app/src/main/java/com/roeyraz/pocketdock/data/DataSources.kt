package com.roeyraz.pocketdock.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import java.security.KeyStore

data class RunnerSnapshot(
    val online: Boolean,
    val info: String,
    val uptimeSeconds: Long = 0L,
    val freeMegabytes: Long = 0L,
)

data class DaylightTask(val title: String, val step: String, val status: String, val priority: String)
data class DaylightSnapshot(val tasks: List<DaylightTask>, val error: String? = null)
data class NextEvent(val title: String, val whenText: String)
data class DevSnapshot(
    val repo: String,
    val ci: String,
    val lastRun: String,
    val openPrs: List<String>,
    val error: String? = null,
)

private const val API_BASE = "https://api.github.com"
private const val NOTION_VERSION = "2025-09-03"

private fun request(url: String, bearer: String? = null, postBody: String? = null): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 5500
        connection.readTimeout = 5500
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "PocketDock/0.2")
        if (bearer != null) {
            connection.setRequestProperty("Authorization", "Bearer $bearer")
            connection.setRequestProperty("Notion-Version", NOTION_VERSION)
        }
        if (postBody != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(postBody.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

suspend fun readRunner(): RunnerSnapshot = withContext(Dispatchers.IO) {
    try {
        val health = JSONObject(request("http://127.0.0.1:8799/health"))
        if (health.optString("status") != "ok" || health.optString("service") != "pocketdock-runner") {
            return@withContext RunnerSnapshot(false, "Unexpected Runner response")
        }
        val details = try {
            JSONObject(request("http://127.0.0.1:8799/status"))
        } catch (_: Exception) {
            null // Support the 0.1 Runner until Termux has pulled the updated code.
        }
        RunnerSnapshot(
            online = true,
            info = if (details == null) "Connected. Update Runner for storage metrics." else "Running on this phone",
            uptimeSeconds = details?.optLong("uptime_seconds") ?: 0L,
            freeMegabytes = details?.optLong("free_storage_mb") ?: 0L,
        )
    } catch (_: Exception) {
        RunnerSnapshot(false, "Open Termux and start runner/server.py")
    }
}

suspend fun phoneNetworkCheck(): String = withContext(Dispatchers.IO) {
    try {
        val result = JSONObject(request("http://127.0.0.1:8799/checks/github"))
        if (result.optBoolean("reachable")) "GitHub reachable from the phone" else
            "GitHub unreachable from the phone"
    } catch (_: Exception) {
        "Network check unavailable. Update and restart the Termux Runner."
    }
}

suspend fun readDev(repository: String): DevSnapshot = withContext(Dispatchers.IO) {
    val slug = repository.trim()
    if (!Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(slug)) {
        return@withContext DevSnapshot(slug, "Unknown", "", emptyList(), "Use owner/repository")
    }
    try {
        val encoded = slug.split("/").joinToString("/")
        val runs = JSONObject(request("$API_BASE/repos/$encoded/actions/runs?per_page=1"))
            .optJSONArray("workflow_runs") ?: JSONArray()
        val last = runs.optJSONObject(0)
        val ci = if (last == null) "No workflow yet" else {
            when (last.optString("status")) {
                "completed" -> last.optString("conclusion", "unknown").uppercase(Locale.US)
                else -> last.optString("status", "unknown").uppercase(Locale.US)
            }
        }
        val prs = JSONArray(request("$API_BASE/repos/$encoded/pulls?state=open&per_page=5"))
        val labels = buildList {
            for (i in 0 until prs.length()) {
                prs.optJSONObject(i)?.optString("title")?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
        DevSnapshot(slug, ci, last?.optString("display_title").orEmpty(), labels)
    } catch (e: Exception) {
        DevSnapshot(slug, "Unavailable", "", emptyList(), e.message?.take(90) ?: "GitHub request failed")
    }
}

fun nextCalendarEvent(context: Context): NextEvent? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) !=
        PackageManager.PERMISSION_GRANTED) return null

    val now = System.currentTimeMillis()
    val end = now + 7L * 24L * 60L * 60L * 1000L
    val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
    ContentUris.appendId(builder, now)
    ContentUris.appendId(builder, end)
    val projection = arrayOf(
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
    )
    return try {
        context.contentResolver.query(
            builder.build(),
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val starts = cursor.getLong(1)
                val ends = cursor.getLong(2)
                if (ends <= now) continue
                val text = cursor.getString(0).orEmpty().ifBlank { "אירוע ביומן" }
                val date = SimpleDateFormat("EEE d MMM, HH:mm", Locale("he", "IL")).format(Date(starts))
                return@use NextEvent(text, if (starts <= now) "מתרחש עכשיו" else date)
            }
            null
        }
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }
}

/**
 * User-provided Notion token is encrypted at rest with the Android Keystore.
 * Neither token nor data source ID is shipped in the public repository.
 * A scoped integration must be explicitly shared with the Daylight database.
 */
object DaylightCredentials {
    private const val PREF = "pocketdock_private"
    private const val ALIAS = "pocketdock_notion_aes_gcm_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    fun configured(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return prefs.contains("token") && !prefs.getString("source", "").isNullOrBlank()
    }

    fun sourceId(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("source", "").orEmpty()

    fun save(context: Context, token: String, source: String) {
        require(Regex("^[0-9a-fA-F-]{32,36}$").matches(source.trim())) { "Invalid data source ID" }
        require(token.isNotBlank()) { "Token required" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(data, Base64.NO_WRAP)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("token", encoded)
            .putString("source", source.trim())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun token(context: Context): String {
        val encoded = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("token", "") ?: return ""
        if (encoded.isBlank()) return ""
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}

private fun notionText(elements: JSONArray?): String = buildString {
    if (elements == null) return@buildString
    for (index in 0 until elements.length()) {
        append(elements.optJSONObject(index)?.optString("plain_text").orEmpty())
    }
}

suspend fun readDaylight(context: Context): DaylightSnapshot = withContext(Dispatchers.IO) {
    if (!DaylightCredentials.configured(context)) {
        return@withContext DaylightSnapshot(emptyList(), "Connect Daylight in Settings")
    }
    try {
        val source = DaylightCredentials.sourceId(context)
        val token = DaylightCredentials.token(context)
        val data = JSONObject(
            request(
                url = "https://api.notion.com/v1/data_sources/$source/query",
                bearer = token,
                postBody = """{"page_size":75,"filter":{"property":"מצב","status":{"does_not_equal":"הושלם"}}}""",
            )
        )
        val results = data.optJSONArray("results") ?: JSONArray()
        val tasks = buildList {
            for (i in 0 until results.length()) {
                val properties = results.optJSONObject(i)?.optJSONObject("properties") ?: continue
                val list = properties.optJSONObject("רשימה")?.optJSONObject("select")
                    ?.optString("name").orEmpty()
                if (list == "Shop") continue
                val title = notionText(properties.optJSONObject("משימה")?.optJSONArray("title"))
                if (title.isBlank()) continue
                add(
                    DaylightTask(
                        title = title,
                        step = notionText(properties.optJSONObject("צעד ראשון")?.optJSONArray("rich_text")),
                        status = properties.optJSONObject("מצב")?.optJSONObject("status")?.optString("name").orEmpty(),
                        priority = properties.optJSONObject("דחיפות")?.optJSONObject("select")?.optString("name").orEmpty(),
                    )
                )
            }
        }.sortedBy { when (it.priority) {
            "דחוף" -> 0
            "גבוהה" -> 1
            "רגילה" -> 2
            else -> 3
        } }
        DaylightSnapshot(tasks.take(12), if (data.optBoolean("has_more")) "Showing first page of active tasks" else null)
    } catch (e: Exception) {
        DaylightSnapshot(emptyList(), "Daylight unavailable: ${e.message?.take(75) ?: "connection failed"}")
    }
}
