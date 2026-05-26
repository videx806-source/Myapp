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
    val CATEGORIES = listOf("Todos", "Noticias", "Deportes", "Música", "Entretenimiento", "Cine & Series")

    val DEFAULT_CHANNELS = listOf(
        Channel(
            id = "canal26",
            name = "Canal 26 (Argentina)",
            category = "Noticias",
            url = "https://live-col.canal26.com/hls/canal26col.m3u8",
            logoUrl = "https://images.unsplash.com/photo-1504711434969-e33886168f5c?w=120"
        ),
        Channel(
            id = "euronews",
            name = "Euronews Español",
            category = "Noticias",
            url = "https://euronews-es-p4-fast.streaming.amagi.tv/playlist.m3u8",
            logoUrl = "https://images.unsplash.com/photo-1451187580459-43490279c0fa?w=120"
        ),
        Channel(
            id = "dw_espanol",
            name = "DW Español",
            category = "Noticias",
            url = "https://dwstream4-lh.akamaihd.net/i/dwespanol_1@115160/master.m3u8",
            logoUrl = "https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=120"
        ),
        Channel(
            id = "telesur",
            name = "TeleSUR (Latinoamérica)",
            category = "Noticias",
            url = "https://telesurenvivo.gcdn.co/live/telesur_live/index.m3u8",
            logoUrl = "https://images.unsplash.com/photo-1585829365295-ab7cd400c167?w=120"
        ),
        Channel(
            id = "redbull",
            name = "Red Bull TV",
            category = "Deportes",
            url = "https://rbmn-live.secure.footprint.net/v1/manifest/redbulltv-es.m3u8",
            logoUrl = "https://images.unsplash.com/photo-1461896836934-ffe607ba8211?w=120"
        ),
        Channel(
            id = "kissfmtv",
            name = "KISS FM TV",
            category = "Música",
            url = "https://kissfmtron-lh.akamaihd.net/i/KISSFM_1@505432/master.m3u8",
            logoUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=120"
        ),
        Channel(
            id = "canalsur",
            name = "Canal Sur Andalucía",
            category = "Entretenimiento",
            url = "https://canalsur-live.secure.footprint.net/v1/manifest/canalsur.m3u8",
            logoUrl = "https://images.unsplash.com/photo-1522202176988-66273c2fd55f?w=120"
        ),
        Channel(
            id = "canal_ciudad",
            name = "Canal de la Ciudad (BA)",
            category = "Entretenimiento",
            url = "https://cda-fast-01.strm.com.ar/cdafast/live/playlist.m3u8",
            logoUrl = "https://images.unsplash.com/photo-1517604931442-7e0c8ed2963c?w=120"
        ),
        Channel(
            id = "cine_classic_mp4",
            name = "Cine de Aventuras (Muestra)",
            category = "Cine & Series",
            url = "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            logoUrl = "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?w=120"
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
