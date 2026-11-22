package com.roeyraz.pocketdock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.roeyraz.pocketdock.data.DaylightCredentials
import com.roeyraz.pocketdock.data.DaylightSnapshot
import com.roeyraz.pocketdock.data.DevSnapshot
import com.roeyraz.pocketdock.data.NextEvent
import com.roeyraz.pocketdock.data.RunnerSnapshot
import com.roeyraz.pocketdock.data.nextCalendarEvent
import com.roeyraz.pocketdock.data.phoneNetworkCheck
import com.roeyraz.pocketdock.data.readDaylight
import com.roeyraz.pocketdock.data.readDev
import com.roeyraz.pocketdock.ui.WatchdogPanel
import com.roeyraz.pocketdock.ui.StoragePanel
import com.roeyraz.pocketdock.data.readRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val Background = Color(0xFF0D1219)
private val PanelBackground = Color(0xFF18222E)
private val Muted = Color(0xFFA4B1C2)
private val Accent = Color(0xFF91C5F2)
private val Good = Color(0xFF80D3B4)

private enum class Tab(val label: String) {
    RUNNER("שירותים"),
    HOME("בית"),
    FOCUS("פוקוס"),
    DEV("פיתוח"),
    STORAGE("קבצים"),
    SETTINGS("הגדרות"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Background,
                surface = Background,
                primary = Accent,
            )) {
                CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
                        PocketDockApp()
                    }
                }
            }
        }
    }
}

@Composable
private fun PocketDockApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.RUNNER) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text("PocketDock", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                Text("Android companion · 0.3", color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            Tab.entries.forEach { option ->
                if (tab == option) {
                    Button(onClick = { tab = option }) { Text(option.label) }
                } else {
                    TextButton(onClick = { tab = option }) { Text(option.label, color = Muted) }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.RUNNER -> WatchdogPanel()
                Tab.STORAGE -> StoragePanel()
                Tab.HOME -> HomePanel()
                Tab.FOCUS -> FocusPanel(onSettings = { tab = Tab.SETTINGS })
                Tab.DEV -> DevPanel()
                Tab.SETTINGS -> SettingsPanel()
            }
        }
    }
}

@Composable
private fun Panel(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = PanelBackground),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Medium)
            content()
        }
    }
}

@Composable
private fun StatusText(value: String, color: Color = Accent) {
    Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = color)
}

@Composable
private fun HomePanel() {
    val scope = rememberCoroutineScope()
    var runner by remember { mutableStateOf(RunnerSnapshot(false, "Not checked")) }
    var checking by remember { mutableStateOf(true) }
    var network by remember { mutableStateOf("לא נבדק") }

    LaunchedEffect(Unit) {
        runner = readRunner()
        checking = false
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel("RUNNER · שירות בטלפון", Modifier.weight(1f).fillMaxHeight()) {
            StatusText(
                if (checking) "CHECKING" else if (runner.online) "ONLINE" else "OFFLINE",
                if (runner.online) Good else Accent,
            )
            Text(runner.info, color = Muted, fontSize = 12.sp)
            if (runner.uptimeSeconds > 0) {
                Text("זמן פעילות: ${runner.uptimeSeconds / 60} דקות", fontSize = 13.sp)
            }
            if (runner.freeMegabytes > 0) {
                Text("אחסון פנוי: ${runner.freeMegabytes} MB", fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            Button(
                enabled = !checking,
                onClick = {
                    scope.launch {
                        checking = true
                        runner = readRunner()
                        checking = false
                    }
                },
            ) { Text("רענון") }
        }
        Panel("פעולות בטלפון", Modifier.weight(1f).fillMaxHeight()) {
            StatusText("ON ANDROID")
            Text("הבדיקות מתבצעות על ה־Samsung, בלי שירות חדש ב־Windows.", fontSize = 12.sp)
            Text(network, color = Muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                enabled = runner.online,
                onClick = { scope.launch { network = phoneNetworkCheck() } },
            ) { Text("בדיקת רשת בטלפון") }
        }
        Panel("מצב הפרויקט", Modifier.weight(1f).fillMaxHeight()) {
            StatusText("FOCUS + DEV")
            Text("פוקוס: יומן, Daylight וטיימר לימוד.", fontSize = 12.sp)
            Text("פיתוח: מצב GitHub וכניסה ל־Codex Cloud.", fontSize = 12.sp)
            Text("אין ריצה אוטומטית של שירותי Windows.", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FocusPanel(onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(DaylightSnapshot(emptyList(), "Not loaded")) }
    var loading by remember { mutableStateOf(true) }
    var calendarGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            == PackageManager.PERMISSION_GRANTED)
    }
    var event by remember { mutableStateOf<NextEvent?>(null) }
    val requestCalendar = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            calendarGranted = granted
            if (granted) scope.launch {
                event = withContext(Dispatchers.IO) { nextCalendarEvent(context) }
            }
        },
    )
    var deadline by remember { mutableLongStateOf(0L) }
    var timeLeft by remember { mutableLongStateOf(0L) }

    LaunchedEffect(deadline) {
        if (deadline > 0L) {
            while (true) {
                timeLeft = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                if (timeLeft == 0L) break
                delay(1000)
            }
        } else {
            timeLeft = 0L
        }
    }
    LaunchedEffect(Unit) {
        snapshot = readDaylight(context)
        if (calendarGranted) {
            event = withContext(Dispatchers.IO) { nextCalendarEvent(context) }
        }
        loading = false
    }

    Row(
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel("DAYLIGHT · משימות פתוחות", Modifier.weight(1.6f).fillMaxHeight()) {
            Row {
                Text(
                    if (loading) "מעדכן…" else "${snapshot.tasks.size} משימות להצגה",
                    fontSize = 12.sp,
                    color = Muted,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        scope.launch { loading = true; snapshot = readDaylight(context); loading = false }
                    },
                ) { Text("רענון") }
            }
            if (snapshot.error != null) {
                Text(snapshot.error.orEmpty(), fontSize = 12.sp, color = Muted)
                if (!DaylightCredentials.configured(context)) {
                    OutlinedButton(onClick = onSettings) { Text("חיבור Daylight") }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                snapshot.tasks.forEach { task ->
                    Text(task.title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    if (task.step.isNotBlank()) {
                        Text(task.step, fontSize = 11.sp, color = Muted, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(9.dp))
                }
            }
            Text("לקריאה בלבד · המשימות נשארות ב־Notion", color = Muted, fontSize = 10.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel("האירוע הבא ביומן", Modifier.fillMaxWidth().weight(1f)) {
                if (!calendarGranted) {
                    Text("לאפליקציה אין עדיין הרשאת יומן.", fontSize = 12.sp)
                    OutlinedButton(
                        onClick = { requestCalendar.launch(Manifest.permission.READ_CALENDAR) },
                    ) { Text("אשר גישה ליומן") }
                } else if (event == null) {
                    Text("אין אירוע בשבוע הקרוב, או שהיומן לא מסונכרן לטלפון.", fontSize = 12.sp)
                } else {
                    StatusText(event!!.whenText)
                    Text(event!!.title, fontSize = 12.sp)
                }
            }
            Panel("סשן פוקוס · על הטלפון", Modifier.fillMaxWidth().weight(1f)) {
                StatusText(if (deadline == 0L) "מוכן להתחיל" else {
                    val secs = timeLeft / 1000L
                    String.format(Locale.US, "%02d:%02d", secs / 60, secs % 60)
                })
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    OutlinedButton(onClick = { deadline = SystemClock.elapsedRealtime() + 25 * 60_000L }) {
                        Text("25 דק׳")
                    }
                    OutlinedButton(onClick = { deadline = SystemClock.elapsedRealtime() + 45 * 60_000L }) {
                        Text("45 דק׳")
                    }
                    TextButton(onClick = { deadline = 0L }) { Text("עצור") }
                }
                Text("טיימר מקומי. לא פועל כשירות רקע.", fontSize = 10.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun DevPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var repository by rememberSaveable { mutableStateOf("RoeyRaz/PocketDock") }
    var snapshot by remember { mutableStateOf(DevSnapshot(repository, "Not checked", "", emptyList())) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        snapshot = readDev(repository)
        loading = false
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel("GITHUB · מצב פיתוח", Modifier.weight(1.3f).fillMaxHeight()) {
            OutlinedTextField(
                value = repository,
                onValueChange = { repository = it },
                label = { Text("Repository (owner/name)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            StatusText(if (loading) "LOADING" else snapshot.ci)
            if (snapshot.lastRun.isNotBlank()) {
                Text(snapshot.lastRun, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (snapshot.error != null) Text(snapshot.error.orEmpty(), color = Muted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch { loading = true; snapshot = readDev(repository); loading = false }
                }, enabled = !loading) { Text("בדוק") }
                OutlinedButton(onClick = { openExternal(context, "https://github.com/${repository.trim()}") }) {
                    Text("פתח GitHub")
                }
            }
        }
        Panel("PULL REQUESTS", Modifier.weight(1f).fillMaxHeight()) {
            if (snapshot.openPrs.isEmpty()) {
                Text("אין PRs פתוחים שנמצאו.", color = Muted, fontSize = 12.sp)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                snapshot.openPrs.forEach {
                    Text("• $it", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                }
            }
            OutlinedButton(
                onClick = { openExternal(context, "https://github.com/${repository.trim()}/pulls") },
            ) { Text("סקירת PRs") }
        }
        Panel("CODEX CLOUD", Modifier.weight(0.9f).fillMaxHeight()) {
            StatusText("CLOUD")
            Text("משימות קידוד כבדות יכולות לרוץ בענן, לא ב־Windows המקומי.", fontSize = 12.sp)
            Text("נפתח את Codex בדפדפן. לא מוצג סטטוס Codex ללא חיבור מורשה.", fontSize = 11.sp, color = Muted)
            Spacer(Modifier.weight(1f))
            Button(onClick = { openExternal(context, "https://chatgpt.com/codex") }) {
                Text("פתח Codex")
            }
        }
    }
}

@Composable
private fun SettingsPanel() {
    val context = LocalContext.current
    var source by remember { mutableStateOf(DaylightCredentials.sourceId(context)) }
    var token by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(DaylightCredentials.configured(context)) }
    var notice by remember { mutableStateOf("") }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel("חיבור Daylight · לקריאה בלבד", Modifier.weight(1.25f).fillMaxHeight()) {
            Text(
                if (connected) "יש חיבור שמור. אפשר להחליף או לנתק אותו." else "חבר אינטגרציית Notion עם הרשאת קריאה בלבד.",
                fontSize = 12.sp,
            )
            OutlinedTextField(
                value = source,
                onValueChange = { source = it },
                label = { Text("Notion data source ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Internal integration token") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(notice, fontSize = 11.sp, color = Muted)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(onClick = {
                    try {
                        DaylightCredentials.save(context, token, source)
                        token = ""
                        connected = true
                        notice = "נשמר באופן מוצפן במכשיר."
                    } catch (_: Exception) {
                        notice = "יש לבדוק טוקן ו־data source ID."
                    }
                }) { Text("שמור") }
                TextButton(onClick = {
                    DaylightCredentials.clear(context)
                    token = ""
                    connected = false
                    notice = "החיבור הוסר מהמכשיר."
                }) { Text("נתק") }
            }
        }
        Panel("פרטיות וביצועים", Modifier.weight(1f).fillMaxHeight()) {
            StatusText("LOCAL FIRST")
            Text("הטוקן מוצפן באמצעות Android Keystore ונשמר בטלפון בלבד.", fontSize = 12.sp)
            Text("לא מעלים טוקנים ל־GitHub ולא משתמשים בשירות Windows נוסף.", fontSize = 12.sp)
            Text("החיבור ל־Notion קורא משימות רק ברענון ידני.", fontSize = 12.sp)
            Text("יש לשתף את מאגר המשימות עם אינטגרציית Notion שנוצרה.", color = Muted, fontSize = 11.sp)
        }
    }
}

private fun openExternal(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        // No browser installed.
    }
}
