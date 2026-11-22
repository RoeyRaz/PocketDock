package com.roeyraz.pocketdock.ui

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.IOException

private const val MAX_FILE_BYTES = 150L * 1024 * 1024
private val Ink = Color(0xFFB0BECD)
private val Panel = Color(0xFF18222E)

private fun localInbox(context: Context): File =
    File(context.filesDir, "inbox").apply { mkdirs() }

private fun safeName(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._\u0590-\u05FF -]"), "_")
        .trim('.', ' ')
        .take(90)
        .ifBlank { "file" }

private fun importToInbox(context: Context, uri: Uri): String {
    val name = context.contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "file"
    val directory = localInbox(context)
    val fileName = safeName(name)
    val base = fileName.substringBeforeLast(".", fileName)
    val ext = fileName.substringAfterLast(".", "")
    var destination = File(directory, fileName)
    for (i in 1..1000) {
        if (!destination.exists()) break
        destination = File(directory, base + "-" + i + if (ext.isNotBlank()) ".$ext" else "")
    }
    if (destination.exists()) throw IOException("Too many duplicate filenames")
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                var written = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    written += count
                    if (written > MAX_FILE_BYTES) throw IOException("Maximum file size: 150 MB")
                    output.write(buffer, 0, count)
                }
            }
        } ?: throw IOException("Could not open document")
    } catch (error: Exception) {
        destination.delete()
        throw error
    }
    return destination.name
}

@Composable
fun StoragePanel() {
    val context = LocalContext.current
    var files by remember { mutableStateOf(localInbox(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()) }
    var notice by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    fun refresh() {
        files = localInbox(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { notice = "נשמר: " + importToInbox(context, uri) }
            catch (e: Exception) { notice = e.message ?: "Import failed" }
            refresh()
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination ->
        val source = selected
        if (destination != null && source != null) {
            try {
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("Could not save file")
                notice = "הקובץ יוצא בהצלחה"
            } catch (e: Exception) { notice = e.message ?: "Export failed" }
        }
        selected = null
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.weight(1.4f).fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Inbox · אחסון מקומי בטלפון", fontWeight = FontWeight.SemiBold)
                Row {
                    Button(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("ייבוא קובץ") }
                    Spacer(Modifier.weight(1f))
                    Text("${files.size} קבצים", color = Ink, fontSize = 12.sp)
                }
                Text(notice, color = Ink, fontSize = 11.sp)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    files.forEach { file ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(file.name, fontSize = 12.sp, maxLines = 2)
                                Text("${file.length() / 1024} KB", color = Ink, fontSize = 10.sp)
                            }
                            TextButton(onClick = {
                                selected = file
                                exportLauncher.launch(file.name)
                            }) { Text("ייצוא") }
                            TextButton(onClick = { deleting = file }) { Text("מחק") }
                        }
                    }
                    if (files.isEmpty()) Text("אין קבצים עדיין.", color = Ink)
                }
            }
        }
        Card(Modifier.weight(1f).fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.fillMaxSize().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("למה זה שימושי?", fontWeight = FontWeight.SemiBold)
                Text(
                    "תיקיית Inbox פרטית לטלפון. אפשר לייבא אליה קובץ ולייצא אותו בחזרה למיקום שתבחר.",
                    fontSize = 12.sp,
                )
                Text(
                    "אין עדיין העברה אוטומטית למחשב, רשת SFTP או גיבוי. אלה דורשים חיבור מאובטח ובדיקת יציבות.",
                    fontSize = 12.sp, color = Ink,
                )
                Text(
                    "מגבלה: 150 MB לקובץ. אין סנכרון ונתוני האפליקציה עלולים להימחק אם מסירים אותה.",
                    fontSize = 12.sp, color = Ink,
                )
                Text("מומלץ לשמור קבצים חשובים גם במקום אחר.", color = Ink, fontSize = 11.sp)
            }
        }
    }
    deleting?.let { file ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("למחוק את ${file.name}?") },
            text = { Text("הקובץ יימחק רק מתיקיית PocketDock הפרטית בטלפון.") },
            confirmButton = {
                TextButton(onClick = {
                    if (file.delete()) notice = "הקובץ נמחק" else notice = "לא ניתן למחוק"
                    deleting = null
                    refresh()
                }) { Text("מחק") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("ביטול") } },
        )
    }
}
