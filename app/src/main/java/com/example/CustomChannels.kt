package com.example

import android.content.Context

fun getCustomChannels(context: Context): List<Channel> {
    val prefs = context.getSharedPreferences("videx_prefs", Context.MODE_PRIVATE)
    val set = prefs.getStringSet("custom_channels_list", emptySet()) ?: emptySet()
    return set.mapNotNull { line ->
        val parts = line.split("||")
        if (parts.size >= 3) {
            Channel(
                name = parts[0],
                path = parts[1],
                group = parts[2]
            )
        } else null
    }
}

fun saveCustomChannel(context: Context, channel: Channel) {
    val prefs = context.getSharedPreferences("videx_prefs", Context.MODE_PRIVATE)
    val rawSet = prefs.getStringSet("custom_channels_list", emptySet()) ?: emptySet()
    val set = rawSet.toMutableSet()
    val serializedLine = "${channel.name}||${channel.path}||${channel.group}"
    set.add(serializedLine)
    prefs.edit().putStringSet("custom_channels_list", set).apply()
}

fun deleteCustomChannel(context: Context, name: String) {
    val prefs = context.getSharedPreferences("videx_prefs", Context.MODE_PRIVATE)
    val rawSet = prefs.getStringSet("custom_channels_list", emptySet()) ?: emptySet()
    val set = rawSet.toMutableSet()
    val lineToRemove = set.find { it.startsWith("$name||") }
    if (lineToRemove != null) {
        set.remove(lineToRemove)
        prefs.edit().putStringSet("custom_channels_list", set).apply()
    }
}
