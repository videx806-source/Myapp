package com.example

import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.io.File

data class Recording(
    val id: String,
    val channelName: String,
    val durationSeconds: Int,
    val sizeBytes: Long,
    val timestamp: Long,
    val sourceUrl: String,
    val localPath: String
)

val SAMPLE_RECORDING_VIDEOS = listOf(
    "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
    "https://storage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
    "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
    "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4",
    "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4",
    "https://storage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"
)

fun getRecordings(context: Context): List<Recording> {
    val prefs = context.getSharedPreferences("videx_prefs", Context.MODE_PRIVATE)
    val set = prefs.getStringSet("recordings_v2_list", emptySet()) ?: emptySet()
    val recordingsDir = File(context.filesDir, "VidexRecordings")
    return set.mapNotNull { line ->
        val parts = line.split("||")
        if (parts.size >= 6) {
            val id = parts[0]
            val chan = parts[1]
            val duration = parts[2].toIntOrNull() ?: 12
            val size = parts[3].toLongOrNull() ?: 5402130L
            val ts = parts[4].toLongOrNull() ?: System.currentTimeMillis()
            val url = parts[5]
            val localFile = File(recordingsDir, "$id.mp4")
            Recording(
                id = id,
                channelName = chan,
                durationSeconds = duration,
                sizeBytes = size,
                timestamp = ts,
                sourceUrl = url,
                localPath = localFile.absolutePath
            )
        } else null
    }.sortedByDescending { it.timestamp }
}

fun saveRecording(context: Context, rec: Recording) {
    val prefs = context.getSharedPreferences("videx_prefs", Context.MODE_PRIVATE)
    val rawSet = prefs.getStringSet("recordings_v2_list", emptySet()) ?: emptySet()
    val set = rawSet.toMutableSet()
    val serializedLine = "${rec.id}||${rec.channelName}||${rec.durationSeconds}||${rec.sizeBytes}||${rec.timestamp}||${rec.sourceUrl}"
    set.add(serializedLine)
    prefs.edit().putStringSet("recordings_v2_list", set).apply()
}

fun deleteRecordingFromStorage(context: Context, recId: String) {
    val prefs = context.getSharedPreferences("videx_prefs", Context.MODE_PRIVATE)
    val rawSet = prefs.getStringSet("recordings_v2_list", emptySet()) ?: emptySet()
    val set = rawSet.toMutableSet()
    val lineToRemove = set.find { it.startsWith("$recId||") }
    if (lineToRemove != null) {
        set.remove(lineToRemove)
        prefs.edit().putStringSet("recordings_v2_list", set).apply()
    }
    // Delete physical file
    val recordingsDir = File(context.filesDir, "VidexRecordings")
    val file = File(recordingsDir, "$recId.mp4")
    if (file.exists()) {
        file.delete()
    }
}

fun shareRecordingFile(context: Context, rec: Recording) {
    try {
        val file = File(rec.localPath)
        if (!file.exists()) {
            Toast.makeText(context, "Archivo de grabación no original en disco", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "com.example.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Compartir grabación VIDEX"))
    } catch (e: Exception) {
        Toast.makeText(context, "No se pudo compartir la grabación: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
