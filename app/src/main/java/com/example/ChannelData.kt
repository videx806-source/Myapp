package com.example

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class Channel(
    val id: String,
    val name: String,
    val category: String,
    val url: String,
    val logoUrl: String,
    val isCustom: Boolean = false
)

object ChannelDataProvider {
    val CATEGORIES = listOf("Todos", "Deportes", "Cine & Series", "Entretenimiento", "Noticias", "Música")

    val DEFAULT_CHANNELS = listOf(
        Channel(
            id = "tve1",
            name = "La 1 (RTVE)",
            category = "Noticias",
            url = "https://rtvelivehlspull-lh.akamaihd.net/i/la1_g_main@325430/index_1500_av-p.m3u8",
            logoUrl = "https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=100"
        ),
        Channel(
            id = "teledeporte",
            name = "Teledeporte",
            category = "Deportes",
            url = "https://rtvelivehlspull-lh.akamaihd.net/i/tdp_g_main@324391/index_1500_av-p.m3u8",
            logoUrl = "https://images.unsplash.com/photo-1461896836934-ffe607ba8211?w=100"
        ),
        Channel(
            id = "canal24h",
            name = "Canal 24 Horas",
            category = "Noticias",
            url = "https://rtvelivehlspull-lh.akamaihd.net/i/24h_g_main@325178/index_1500_av-p.m3u8",
            logoUrl = "https://images.unsplash.com/photo-1504711434969-e33886168f5c?w=100"
        ),
        Channel(
            id = "euronews",
            name = "Euronews Español",
            category = "Noticias",
            url = "https://euronews-es-p4-fast.streaming.amagi.tv/playlist.m3u8",
            logoUrl = "https://images.unsplash.com/photo-1451187580459-43490279c0fa?w=100"
        ),
        Channel(
            id = "redbull",
            name = "Red Bull TV",
            category = "Deportes",
            url = "https://rbmn-live.secure.footprint.net/v1/manifest/redbulltv-es.m3u8",
            logoUrl = "https://images.unsplash.com/photo-1551698618-1dfe5d97d256?w=100"
        ),
        Channel(
            id = "nasa",
            name = "NASA TV Live",
            category = "Entretenimiento",
            url = "https://ntvlive.nasa.gov/hls/ntv-live.m3u8",
            logoUrl = "https://images.unsplash.com/photo-1451187580459-43490279c0fa?w=100"
        ),
        Channel(
            id = "fashion",
            name = "Fashion TV",
            category = "Entretenimiento",
            url = "https://fash1043.cloudycdn.com/ytb/ftv_es/index.m3u8",
            logoUrl = "https://images.unsplash.com/photo-1483985988355-763728e1935b?w=100"
        ),
        Channel(
            id = "music_latino",
            name = "Latino Hits",
            category = "Música",
            url = "https://d1zzm4shgmxsk.cloudfront.net/out/v1/673bbce6429944a9918738da0efcb8df/index.m3u8",
            logoUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=100"
        ),
        Channel(
            id = "cine_classic",
            name = "Cine Clásico",
            category = "Cine & Series",
            url = "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            logoUrl = "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?w=100"
        )
    )

    fun getChannels(context: Context): List<Channel> {
        val sp = context.getSharedPreferences("videx_channels", Context.MODE_PRIVATE)
        val json = sp.getString("custom_channels_list", "") ?: ""
        if (json.isEmpty()) return DEFAULT_CHANNELS
        return try {
            val listType = object : TypeToken<List<Channel>>() {}.type
            val custom: List<Channel> = Gson().fromJson(json, listType)
            DEFAULT_CHANNELS + custom
        } catch (e: Exception) {
            DEFAULT_CHANNELS
        }
    }

    fun saveCustomChannel(context: Context, channel: Channel) {
        val sp = context.getSharedPreferences("videx_channels", Context.MODE_PRIVATE)
        val json = sp.getString("custom_channels_list", "") ?: ""
        val listType = object : TypeToken<List<Channel>>() {}.type
        val currentList: MutableList<Channel> = if (json.isEmpty()) {
            mutableListOf()
        } else {
            try {
                Gson().fromJson(json, listType)
            } catch (e: Exception) {
                mutableListOf()
            }
        }
        currentList.add(channel)
        sp.edit().putString("custom_channels_list", Gson().toJson(currentList)).apply()
    }

    fun deleteCustomChannel(context: Context, channelId: String) {
        val sp = context.getSharedPreferences("videx_channels", Context.MODE_PRIVATE)
        val json = sp.getString("custom_channels_list", "") ?: ""
        if (json.isEmpty()) return
        val listType = object : TypeToken<List<Channel>>() {}.type
        try {
            val currentList: List<Channel> = Gson().fromJson(json, listType)
            val filtered = currentList.filter { it.id != channelId }
            sp.edit().putString("custom_channels_list", Gson().toJson(filtered)).apply()
        } catch (e: Exception) {}
    }
}
