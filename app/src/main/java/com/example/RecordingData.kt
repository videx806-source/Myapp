package com.example

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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

object RecordingDataProvider {
    fun getRecordings(context: Context): List<Recording> {
        val sp = context.getSharedPreferences("videx_recordings", Context.MODE_PRIVATE)
        val json = sp.getString("recordings_list", "") ?: ""
        if (json.isEmpty()) return emptyList()
        return try {
            val listType = object : TypeToken<List<Recording>>() {}.type
            val list: List<Recording> = Gson().fromJson(json, listType)
            // Filter to only include files that exist physically
            list.filter { File(it.localPath).exists() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveRecording(context: Context, rec: Recording) {
        val sp = context.getSharedPreferences("videx_recordings", Context.MODE_PRIVATE)
        val list = getRecordings(context).toMutableList()
        list.add(0, rec)
        sp.edit().putString("recordings_list", Gson().toJson(list)).apply()
    }

    fun deleteRecording(context: Context, recId: String) {
        val sp = context.getSharedPreferences("videx_recordings", Context.MODE_PRIVATE)
        val list = getRecordings(context)
        val rec = list.find { it.id == recId }
        if (rec != null) {
            try {
                val f = File(rec.localPath)
                if (f.exists()) f.delete()
            } catch (e: Exception) {}
        }
        val newList = list.filter { it.id != recId }
        sp.edit().putString("recordings_list", Gson().toJson(newList)).apply()
    }
}
val SAMPLE_RECORDING_VIDEOS = listOf(
    "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
    "https://storage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
    "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
    "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4"
)
