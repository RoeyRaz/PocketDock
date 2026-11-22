package com.roeyraz.pocketdock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val RUNNER_URL = "http://127.0.0.1:8799/health"
private val CanvasColor = Color(0xFF10151C)
private val CardColor = Color(0xFF1C2632)
private val MutedColor = Color(0xFF9FAFC1)
private val AccentColor = Color(0xFF8FC6F5)

private data class RunnerState(val online: Boolean, val message: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = CanvasColor)) {
                Surface(modifier = Modifier.fillMaxSize(), color = CanvasColor) {
                    PocketDockHome()
                }
            }
        }
    }
}

@Composable
private fun PocketDockHome() {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(true) }
    var runner by remember { mutableStateOf(RunnerState(false, "Not checked yet")) }

    LaunchedEffect(Unit) {
        runner = checkRunner()
        checking = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasColor)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("PocketDock", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                Text("Android-first PC companion", color = MutedColor, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                enabled = !checking,
                onClick = {
                    scope.launch {
                        checking = true
                        runner = checkRunner()
                        checking = false
                    }
                },
            ) {
                Text(if (checking) "Checking…" else "Refresh")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoCard(
                title = "RUNNER",
                value = if (checking) "CHECKING" else if (runner.online) "ONLINE" else "OFFLINE",
                detail = runner.message,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            InfoCard(
                title = "EXECUTION",
                value = "ON ANDROID",
                detail = "The health server runs in Termux, not on Windows.",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            InfoCard(
                title = "COMING NEXT",
                value = "FOCUS + DEV",
                detail = "Daylight, GitHub Actions, and Codex Cloud integration.",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Text(
            "Prototype 0.1 · local connection only · no background service",
            color = MutedColor,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun InfoCard(
    title: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CardColor),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = MutedColor, fontSize = 11.sp)
            Text(value, color = AccentColor, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = Color.White, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

private suspend fun checkRunner(): RunnerState = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = URL(RUNNER_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 1500
        connection.readTimeout = 1500
        connection.requestMethod = "GET"
        val result = connection.inputStream.bufferedReader().use { it.readText() }
        val body = JSONObject(result)
        val online = connection.responseCode == 200 &&
            body.optString("service") == "pocketdock-runner" &&
            body.optString("status") == "ok"
        RunnerState(
            online = online,
            message = if (online) "Connected to 127.0.0.1:8799" else "Unexpected response",
        )
    } catch (_: Exception) {
        RunnerState(false, "Start runner/server.py in Termux to connect")
    } finally {
        connection?.disconnect()
    }
}
