package com.roeyraz.pocketdock.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roeyraz.pocketdock.data.RunnerDashboard
import com.roeyraz.pocketdock.data.RunnerPairing
import com.roeyraz.pocketdock.data.WatchdogApi
import com.roeyraz.pocketdock.data.MonitorItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val Panel = Color(0xFF18222E)
private val Gray = Color(0xFFA6B4C4)
private val Green = Color(0xFF86DEBB)
private val Red = Color(0xFFFA998E)

@Composable
private fun DockCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = Gray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun WatchdogPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var paired by remember { mutableStateOf(RunnerPairing.hasToken(context)) }
    var tokenInput by remember { mutableStateOf("") }
    var data by remember { mutableStateOf<RunnerDashboard?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var interval by remember { mutableStateOf(15) }
    var toDelete by remember { mutableStateOf<MonitorItem?>(null) }

    suspend fun refresh() {
        loading = true
        try {
            data = WatchdogApi.overview(context)
            error = ""
        } catch (e: Exception) {
            error = e.message?.take(130) ?: "Runner not available"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(paired) {
        if (paired) refresh()
    }

    if (!paired) {
        DockCard("חיבור מאובטח ל־Runner", Modifier.fillMaxSize()) {
            Text("כדי לנהל בדיקות מהאפליקציה, צריך לחבר אותה למנוע שכבר רץ ב־Termux.", fontSize = 13.sp)
            Text("ב־Termux פתח חלון נוסף והרץ:", color = Gray, fontSize = 12.sp)
            Text("cd ~/PocketDock", color = Green, fontSize = 12.sp)
            Text("python runner/server.py --show-token", color = Green, fontSize = 12.sp)
            Text("העתק את הקוד שיופיע והדבק כאן. הוא מוצפן ונשמר רק בטלפון.", color = Gray, fontSize = 11.sp)
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("קוד חיבור מקומי") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(error, color = Red, fontSize = 12.sp)
            Button(onClick = {
                try {
                    RunnerPairing.save(context, tokenInput)
                    tokenInput = ""
                    paired = true
                    error = ""
                } catch (_: Exception) {
                    error = "קוד לא תקין"
                }
            }) { Text("חבר Runner") }
        }
        return
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.weight(1.4f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DockCard("Watchdog · בדיקות שרצות בטלפון") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${data?.enabled ?: 0} פעילות", fontWeight = FontWeight.SemiBold, color = Green)
                    Text("${data?.down ?: 0} תקלות", color = if ((data?.down ?: 0) > 0) Red else Gray)
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = !loading,
                        onClick = { scope.launch { refresh() } },
                    ) { Text(if (loading) "בודק…" else "רענון") }
                }
                if (error.isNotBlank()) {
                    Text(error, color = Red, fontSize = 12.sp)
                    TextButton(onClick = {
                        RunnerPairing.clear(context)
                        paired = false
                        data = null
                    }) { Text("חבר מחדש") }
                }
                if (data?.checks.isNullOrEmpty()) {
                    Text("אין בדיקות מוגדרות.", color = Gray, fontSize = 12.sp)
                }
                data?.checks?.forEach { item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${item.url} · כל ${item.intervalMinutes} דקות",
                                fontSize = 10.sp, color = Gray, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when (item.state) {
                                    "up" -> "תקין · ${item.latencyMs}ms"
                                    "down" -> "תקלה · ${item.error}"
                                    else -> "עוד לא נבדק"
                                },
                                fontSize = 11.sp,
                                color = if (item.state == "down") Red else Green,
                                maxLines = 2,
                            )
                        }
                        Switch(
                            checked = item.enabled,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    try {
                                        WatchdogApi.toggle(context, item.id, checked)
                                        refresh()
                                    } catch (e: Exception) { error = e.message.orEmpty() }
                                }
                            },
                        )
                        TextButton(onClick = {
                            scope.launch {
                                try { WatchdogApi.runNow(context, item.id); refresh() }
                                catch (e: Exception) { error = e.message.orEmpty() }
                            }
                        }) { Text("בדוק") }
                        TextButton(onClick = { toDelete = item }) { Text("מחק", color = Gray) }
                    }
                }
            }
            DockCard("הוסף בדיקת HTTPS") {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("שם הבדיקה") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("כתובת HTTPS ציבורית") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 15, 30, 60).forEach { minutes ->
                        if (interval == minutes)
                            Button(onClick = { interval = minutes }) { Text("${minutes}ד") }
                        else
                            TextButton(onClick = { interval = minutes }) { Text("${minutes}ד") }
                    }
                }
                Button(enabled = !loading && name.isNotBlank(), onClick = {
                    scope.launch {
                        try {
                            WatchdogApi.add(context, name, url, interval)
                            name = ""
                            url = "https://"
                            refresh()
                        } catch (e: Exception) { error = e.message.orEmpty() }
                    }
                }) { Text("הוסף בדיקה") }
                Text("עד 12 בדיקות. כתובות HTTPS ציבוריות בלבד, ללא כתובות ברשת הביתית.", color = Gray, fontSize = 10.sp)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DockCard("התראות רק על שינוי מצב") {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("הפעל התראות בטלפון", fontSize = 13.sp)
                        Text("דורש Termux:API. כבוי כברירת מחדל.", fontSize = 11.sp, color = Gray)
                    }
                    Switch(
                        checked = data?.notifications == true,
                        onCheckedChange = { checked ->
                            scope.launch {
                                try { WatchdogApi.notifications(context, checked); refresh() }
                                catch (e: Exception) { error = e.message.orEmpty() }
                            }
                        },
                    )
                }
            }
            DockCard("היסטוריית שינויים") {
                if (data?.history.isNullOrEmpty()) {
                    Text("אין אירועים עדיין. הבדיקה הראשונה תתווסף כאן.", color = Gray, fontSize = 12.sp)
                }
                data?.history?.take(15)?.forEach { event ->
                    Text(
                        "${if (event.state == "up") "●" else "!"} ${event.name} · ${event.state.uppercase()}",
                        color = if (event.state == "up") Green else Red,
                        fontSize = 12.sp,
                    )
                    Text(
                        SimpleDateFormat("dd.MM HH:mm", Locale("he", "IL"))
                            .format(Date(event.timestamp * 1000)),
                        color = Gray,
                        fontSize = 10.sp,
                    )
                }
                Text("נשמר על הטלפון בלבד. עד 300 שינויי מצב.", color = Gray, fontSize = 10.sp)
            }
        }
    }

    toDelete?.let { check ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("למחוק את ${check.name}?") },
            text = { Text("הבדיקה תוסר. היסטוריית השינויים תישמר.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch {
                        try { WatchdogApi.remove(context, check.id); refresh() }
                        catch (e: Exception) { error = e.message.orEmpty() }
                    }
                }) { Text("מחק") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("ביטול") } },
        )
    }
}
