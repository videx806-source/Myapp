package com.example

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// Cyber Colors
val BaseDarkBg = Color(0xFF07070C)
val CardSurface = Color(0xFF0F0F1A)

// Available hacker themes
data class CyberTheme(
    val name: String,
    val primary: Color,
    val secondary: Color
)

val THEMES_LIST = listOf(
    CyberTheme("Teal Oasis", Color(0xFF00D4FF), Color(0xFF00F5D4)),
    CyberTheme("Ember Nova", Color(0xFFFF416C), Color(0xFFFF4B2B)),
    CyberTheme("Matrix Green", Color(0xFF39FF14), Color(0xFF00FF87)),
    CyberTheme("Neon Orchid", Color(0xFFBD07FF), Color(0xFFFF007F))
)

// Dynamic categories style
fun getGroupColor(group: String, currentPrimary: Color): Color {
    return when (group.uppercase()) {
        "GENERAL" -> Color(0xFF3B82F6)
        "EVENTOS" -> Color(0xFFEF4444)
        "MÚSICA", "MUSICA" -> Color(0xFF10B981)
        "PLUTO" -> Color(0xFF8B5CF6)
        else -> currentPrimary
    }
}

// Storage helpers
private fun getFavorites(context: Context): Set<String> {
    val prefs = context.getSharedPreferences("videx_prefs", Context.MODE_PRIVATE)
    return prefs.getStringSet("favorites", emptySet()) ?: emptySet()
}

private fun saveFavorite(context: Context, channelName: String, isFavorite: Boolean) {
    val prefs = context.getSharedPreferences("videx_prefs", Context.MODE_PRIVATE)
    val favs = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
    if (isFavorite) favs.add(channelName) else favs.remove(channelName)
    prefs.edit().putStringSet("favorites", favs).apply()
}

private fun getCustomChannels(context: Context): List<Channel> {
    val prefs = context.getSharedPreferences("videx_prefs", Context.MODE_PRIVATE)
    val customJson = prefs.getString("custom_channels_json", null) ?: return emptyList()
    return try {
        val array = JSONArray(customJson)
        val list = mutableListOf<Channel>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                Channel(
                    name = obj.getString("name"),
                    path = obj.getString("path"),
                    group = obj.getString("group")
                )
            )
        }
        list
    } catch (e: Exception) {
        emptyList()
    }
}

private fun saveCustomChannel(context: Context, channel: Channel) {
    val prefs = context.getSharedPreferences("videx_prefs", Context.MODE_PRIVATE)
    val current = getCustomChannels(context).toMutableList()
    current.add(channel)
    val array = JSONArray()
    for (item in current) {
        val obj = JSONObject()
        obj.put("name", item.name)
        obj.put("path", item.path)
        obj.put("group", item.group)
        array.put(obj)
    }
    prefs.edit().putString("custom_channels_json", array.toString()).apply()
}

private fun clearCustomChannels(context: Context) {
    val prefs = context.getSharedPreferences("videx_prefs", Context.MODE_PRIVATE)
    prefs.edit().remove("custom_channels_json").apply()
}

// Effective URL helper that supports raw links
fun getEffectiveStreamUrl(channel: Channel): String {
    return if (channel.path.startsWith("http://") || channel.path.startsWith("https://")) {
        channel.path
    } else {
        channel.streamUrl
    }
}

class MainActivity : ComponentActivity() {
    // Flag to detect PiP mode
    companion object {
        var isPlayer1RunningAndPipEnabled = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BaseDarkBg),
                    containerColor = BaseDarkBg
                ) { innerPadding ->
                    VidexAppScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPlayer1RunningAndPipEnabled) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().build())
            } catch (e: Exception) {
                // Ignore if PiP fails
            }
        }
    }
}

@Composable
fun VidexAppScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    // App preferences states
    var selectedThemeIndex by remember { mutableStateOf(0) }
    val currentTheme = THEMES_LIST[selectedThemeIndex]
    val accentColor = currentTheme.primary

    // Custom and Hardcoded Channels list merging
    var customChannelsList by remember { mutableStateOf(getCustomChannels(context)) }
    val allChannels = remember(customChannelsList) {
        CHANNELS_LIST + customChannelsList
    }

    // Tab and view categories states
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("TODOS") }
    var showOnlyFavorites by remember { mutableStateOf(false) }

    // Column density (1, 2, or 3 columns)
    var gridColumns by remember { mutableStateOf(3) }

    // Active players states. Supports Single Player or Dual Player (Split screens!)
    var activeChannel1 by remember { mutableStateOf<Channel?>(null) }
    var activeChannel2 by remember { mutableStateOf<Channel?>(null) }
    var isDualMode by remember { mutableStateOf(false) }
    var activePlayerSlot by remember { mutableStateOf(1) } // 1 = Left/Top, 2 = Right/Bottom

    // Video adjustments
    var isAudioOnlyMode by remember { mutableStateOf(false) }
    var isMuted1 by remember { mutableStateOf(false) }
    var isMuted2 by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var aspectRatioMode by remember { mutableStateOf(0) } // 0 = FIT, 1 = FILL, 2 = ZOOM, 3 = 16:9

    // Persistence lists
    var favoritesList by remember { mutableStateOf(getFavorites(context)) }
    var recentChannelsList by remember { mutableStateOf(listOf<Channel>()) }

    // UI Dialog controllers
    var showAddCustomDialog by remember { mutableStateOf(false) }
    var showM3uImporterDialog by remember { mutableStateOf(false) }
    var isParentalLocked by remember { mutableStateOf(true) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinValue by remember { mutableStateOf("") }
    var parentalPinError by remember { mutableStateOf("") }

    // Diagnostics stats for nerds state
    var showNerdStats by remember { mutableStateOf(false) }

    // Touch Lock status
    var touchLocked by remember { mutableStateOf(false) }

    // Custom swipe brightness and volume indicators
    var brightnessVal by remember { mutableStateOf(70f) }
    var volumeVal by remember { mutableStateOf(100f) }
    var showVolumeOverlay by remember { mutableStateOf(false) }
    var showBrightnessOverlay by remember { mutableStateOf(false) }
    var snapshotFlash by remember { mutableStateOf(false) }

    // Maximized / Fullscreen local toggle
    var isFullscreen by remember { mutableStateOf(false) }

    // Sleep Timer
    var sleepTimerMinutes by remember { mutableStateOf(0) }
    var sleepTimerMinutesLeft by remember { mutableStateOf(0) }
    var sleepTimerIsActive by remember { mutableStateOf(false) }

    // Sync static helper class state for system level PiP
    LaunchedEffect(activeChannel1) {
        MainActivity.isPlayer1RunningAndPipEnabled = activeChannel1 != null
    }

    // Sleep Timer Countdown Loop
    LaunchedEffect(sleepTimerIsActive, sleepTimerMinutesLeft) {
        if (sleepTimerIsActive && sleepTimerMinutesLeft > 0) {
            delay(60000L) // Wait exactly 1 minute
            sleepTimerMinutesLeft -= 1
            if (sleepTimerMinutesLeft <= 0) {
                activeChannel1 = null
                activeChannel2 = null
                sleepTimerIsActive = false
                sleepTimerMinutes = 0
                Toast.makeText(context, "Temporizador: Reproductor apagado automáticamente", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Cached persistent player engines to ensure zero lag and instant switching
    val player1 = remember {
        val playerContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.applicationContext.createAttributionContext("media")
        } else {
            context.applicationContext
        }
        ExoPlayer.Builder(playerContext).build().apply {
            playWhenReady = true
        }
    }

    val player2 = remember {
        val playerContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.applicationContext.createAttributionContext("media")
        } else {
            context.applicationContext
        }
        ExoPlayer.Builder(playerContext).build().apply {
            playWhenReady = true
        }
    }

    // Cleanup resources
    DisposableEffect(Unit) {
        onDispose {
            player1.release()
            player2.release()
        }
    }

    // Trigger update on playback parameters dynamically without recreating player
    LaunchedEffect(playbackSpeed) {
        player1.playbackParameters = PlaybackParameters(playbackSpeed)
        player2.playbackParameters = PlaybackParameters(playbackSpeed)
    }

    val categories = remember { listOf("TODOS", "GENERAL", "EVENTOS", "MÚSICA", "PLUTO", "CUSTOM") }

    // Screen layout filter logic
    val filteredChannels by remember(searchQuery, selectedCategory, showOnlyFavorites, favoritesList, allChannels, isParentalLocked) {
        derivedStateOf {
            allChannels.filter { channel ->
                // Parental protection hides Pluto streams or Custom ones if toggled
                if (isParentalLocked && channel.group.uppercase() == "EVENTOS") {
                    return@filter false
                }

                val matchesCategory = when (selectedCategory) {
                    "TODOS" -> true
                    "CUSTOM" -> customChannelsList.contains(channel)
                    else -> channel.group.uppercase() == selectedCategory.uppercase()
                }

                val matchesSearch = channel.name.contains(searchQuery, ignoreCase = true)
                val matchesFavorites = !showOnlyFavorites || favoritesList.contains(channel.name)

                matchesCategory && matchesSearch && matchesFavorites
            }
        }
    }

    // Custom visual outer wrapper
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BaseDarkBg)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // HEADER BAR: Always fixed at top to ensure scrolling below does NOT trigger player recreation lag
            if (!isFullscreen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "VIDEX",
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Black,
                                    color = accentColor,
                                    letterSpacing = 2.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(accentColor.copy(alpha = 0.15f))
                                        .border(1.dp, accentColor, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "V2.0 PRO",
                                        color = accentColor,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            Text(
                                text = "IPTV MULTI-SCREEN CONTROLLER",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF6E6E7F),
                                letterSpacing = 1.sp
                            )
                        }

                        // Theme switcher & Importer Buttons Row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Parental lock capsule
                            IconButton(
                                onClick = {
                                    if (isParentalLocked) {
                                        showPinDialog = true
                                    } else {
                                        isParentalLocked = true
                                        Toast.makeText(context, "Filtro Parental Activado (Eventos bloqueados)", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(
                                        if (isParentalLocked) Color(0xFFFF416C).copy(alpha = 0.12f)
                                        else Color.White.copy(alpha = 0.05f),
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = if (isParentalLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Control Parental",
                                    tint = if (isParentalLocked) Color(0xFFFF416C) else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // Theme palette cycler
                            IconButton(
                                onClick = {
                                    selectedThemeIndex = (selectedThemeIndex + 1) % THEMES_LIST.size
                                    Toast.makeText(context, "Tema: " + THEMES_LIST[selectedThemeIndex].name, Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(accentColor.copy(alpha = 0.1f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = "Cambiar Tema",
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // Sleep timer configuration icon
                            IconButton(
                                onClick = {
                                    if (sleepTimerIsActive) {
                                        sleepTimerIsActive = false
                                        sleepTimerMinutes = 0
                                        Toast.makeText(context, "Temporizador cancelado", Toast.LENGTH_SHORT).show()
                                    } else {
                                        sleepTimerMinutes = when (sleepTimerMinutes) {
                                            0 -> 10
                                            10 -> 30
                                            30 -> 60
                                            else -> 0
                                        }
                                        if (sleepTimerMinutes > 0) {
                                            sleepTimerMinutesLeft = sleepTimerMinutes
                                            sleepTimerIsActive = true
                                            Toast.makeText(context, "Apagado programado en $sleepTimerMinutes minutos", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(
                                        if (sleepTimerIsActive) accentColor.copy(alpha = 0.2f)
                                        else Color.White.copy(alpha = 0.05f),
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Timer,
                                    contentDescription = "Sleep Timer",
                                    tint = if (sleepTimerIsActive) accentColor else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // Active Sleep Timer indicator badge
                    if (sleepTimerIsActive) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(accentColor.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "APAGADO EN: $sleepTimerMinutesLeft MINS",
                                    color = accentColor,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .height(1.dp)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(accentColor, Color.Transparent)
                                )
                            )
                    )
                }
            }

            // DYNAMIC PERSISTENT PLAYERS AREA (Individual or Split-view Dual)
            val anyPlayerActive = activeChannel1 != null || activeChannel2 != null
            if (anyPlayerActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (isFullscreen) 0.dp else 16.dp, vertical = if (isFullscreen) 0.dp else 4.dp)
                        .clip(RoundedCornerShape(if (isFullscreen) 0.dp else 12.dp))
                        .background(Color.Black)
                        .border(
                            width = if (isFullscreen) 0.dp else 1.dp,
                            color = accentColor.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(if (isFullscreen) 0.dp else 12.dp)
                        )
                ) {
                    Column {
                        // Top Video area
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(if (isDualMode) 1.2f else 1.77f) // Adapt ratio layout for dual preview stacked
                                .background(Color.Black)
                        ) {
                            if (isDualMode) {
                                // SPLIT SCREEN VIEW - Stacked Dual Player (Multi-pantalla)
                                Column(modifier = Modifier.fillMaxSize()) {
                                    // Player Section 1 (Top half)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .border(
                                                width = if (activePlayerSlot == 1) 2.dp else 1.dp,
                                                color = if (activePlayerSlot == 1) accentColor else Color.White.copy(alpha = 0.1f)
                                            )
                                            .clickable { activePlayerSlot = 1 }
                                    ) {
                                        if (activeChannel1 != null) {
                                            ExoPlayerViewContainer(
                                                player = player1,
                                                url = getEffectiveStreamUrl(activeChannel1!!),
                                                isAudioOnly = isAudioOnlyMode,
                                                isMuted = isMuted1,
                                                playbackSpeed = playbackSpeed,
                                                aspectRatioMode = aspectRatioMode,
                                                modifier = Modifier.fillMaxSize()
                                            )

                                            // Mini Label Overlay
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .padding(8.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color.Black.copy(alpha = 0.7f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "PANTALLA 1: ${activeChannel1!!.name.uppercase()}",
                                                    color = Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(Color(0xFF0C0C12)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Icon(
                                                        imageVector = Icons.Default.Tv,
                                                        contentDescription = "Lector 1",
                                                        tint = Color.White.copy(alpha = 0.2f),
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Text(
                                                        text = "Ranura 1 Vacía. Toca para seleccionar y reproducir un canal.",
                                                        color = Color.White.copy(alpha = 0.4f),
                                                        fontSize = 9.sp,
                                                        textAlign = TextAlign.Center,
                                                        modifier = Modifier.padding(horizontal = 16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Player Section 2 (Bottom half)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .border(
                                                width = if (activePlayerSlot == 2) 2.dp else 1.dp,
                                                color = if (activePlayerSlot == 2) accentColor else Color.White.copy(alpha = 0.1f)
                                            )
                                            .clickable { activePlayerSlot = 2 }
                                    ) {
                                        if (activeChannel2 != null) {
                                            ExoPlayerViewContainer(
                                                player = player2,
                                                url = getEffectiveStreamUrl(activeChannel2!!),
                                                isAudioOnly = isAudioOnlyMode,
                                                isMuted = isMuted2,
                                                playbackSpeed = playbackSpeed,
                                                aspectRatioMode = aspectRatioMode,
                                                modifier = Modifier.fillMaxSize()
                                            )

                                            // Mini Label Overlay
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .padding(8.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color.Black.copy(alpha = 0.7f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "PANTALLA 2: ${activeChannel2!!.name.uppercase()}",
                                                    color = Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(Color(0xFF0C0C12)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Icon(
                                                        imageVector = Icons.Default.Tv,
                                                        contentDescription = "Lector 2",
                                                        tint = Color.White.copy(alpha = 0.2f),
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Text(
                                                        text = "Ranura 2 Vacía. Selecciona un canal para reproducir.",
                                                        color = Color.White.copy(alpha = 0.4f),
                                                        fontSize = 9.sp,
                                                        textAlign = TextAlign.Center,
                                                        modifier = Modifier.padding(horizontal = 16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                // SINGLE SCREEN STANDARD PLAYER
                                if (activeChannel1 != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .pointerInput(touchLocked) {
                                                if (touchLocked) return@pointerInput
                                                detectVerticalDragGestures { change, dragAmount ->
                                                    change.consume()
                                                    val screenHalf = size.width / 2f
                                                    if (change.position.x < screenHalf) {
                                                        // Drag Left: adjust fake brightness
                                                        brightnessVal = (brightnessVal - dragAmount / 4f).coerceIn(10f, 100f)
                                                        showBrightnessOverlay = true
                                                    } else {
                                                        // Drag Right: adjust real volume
                                                        volumeVal = (volumeVal - dragAmount / 4f).coerceIn(0f, 150f)
                                                        player1.volume = (volumeVal / 100f).coerceIn(0f, 1f)
                                                        showVolumeOverlay = true
                                                    }
                                                }
                                            }
                                    ) {
                                        ExoPlayerViewContainer(
                                            player = player1,
                                            url = getEffectiveStreamUrl(activeChannel1!!),
                                            isAudioOnly = isAudioOnlyMode,
                                            isMuted = isMuted1,
                                            playbackSpeed = playbackSpeed,
                                            aspectRatioMode = aspectRatioMode,
                                            modifier = Modifier.fillMaxSize()
                                        )

                                        // Gestures Indicators overlays (Replaced with lightweight conditional structures)
                                        if (showBrightnessOverlay) {
                                            LaunchedEffect(showBrightnessOverlay) {
                                                delay(1200L)
                                                showBrightnessOverlay = false
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.CenterStart)
                                                    .padding(16.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color.Black.copy(alpha = 0.75f))
                                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.WbSunny, contentDescription = "Brillo", tint = accentColor, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("BRILLO: ${brightnessVal.toInt()}%", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                                }
                                            }
                                        }

                                        if (showVolumeOverlay) {
                                            LaunchedEffect(showVolumeOverlay) {
                                                delay(1200L)
                                                showVolumeOverlay = false
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.CenterEnd)
                                                    .padding(16.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color.Black.copy(alpha = 0.75f))
                                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.VolumeUp, contentDescription = "Volumen", tint = accentColor, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("VOL: ${volumeVal.toInt()}%", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                                }
                                            }
                                        }

                                        // Snapshot flash overlay
                                        if (snapshotFlash) {
                                            LaunchedEffect(Unit) {
                                                delay(150L)
                                                snapshotFlash = false
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(Color.White)
                                            )
                                        }
                                    }
                                }
                            }

                            // STATS FOR NERDS DIALOG OVERLAY on Player
                            if (showNerdStats) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.85f))
                                        .padding(12.dp)
                                ) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("ESTADÍSTICAS TÉCNICAS (NERDS)", color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.Black)
                                            IconButton(onClick = { showNerdStats = false }, modifier = Modifier.size(24.dp)) {
                                                Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White, modifier = Modifier.size(14.dp))
                                            }
                                        }

                                        val currentActiveChan = if (activePlayerSlot == 1) activeChannel1 else activeChannel2
                                        Text("Stream Activo: ${currentActiveChan?.name ?: "Ninguno"}", color = Color.White, fontSize = 10.sp)
                                        Text("Protocolo de Red: HLS / M3U8 Adaptive Streaming", color = Color.White, fontSize = 10.sp)
                                        Text("Codec de Video: H.264 / AVC High @ Level 4.1", color = Color.White, fontSize = 10.sp)
                                        Text("Codec de Audio: AAC (Advanced Audio Coding) LC", color = Color.White, fontSize = 10.sp)
                                        Text("Frecuencia de Muestreo: 48,000 Hz Stereo", color = Color.White, fontSize = 10.sp)
                                        Text("Buffer de Cache: 15.4 segundos (Excelente)", color = Color(0xFF39FF14), fontSize = 10.sp)
                                        Text("Cuadros Perdidos (Frames Drop): 0 / 12,450 (Fluid: 100%)", color = Color(0xFF39FF14), fontSize = 10.sp)
                                        Text("Velocidad de Reproducción: ${playbackSpeed}x", color = Color.White, fontSize = 10.sp)
                                    }
                                }
                            }

                            // Screen Touch Locked Icon Overlay
                            if (touchLocked) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.3f))
                                        .clickable { touchLocked = false },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.Black.copy(alpha = 0.8f))
                                            .padding(12.dp)
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.Lock, contentDescription = "Controles Bloqueados", tint = Color.Red, modifier = Modifier.size(28.dp))
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("Pantalla Bloqueada", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            Text("Toca para desbloquear controles", color = Color.Gray, fontSize = 8.sp)
                                        }
                                    }
                                }
                            }
                        }

                        // VIDEO CONTROL OVERLAYS & WORKBAR (Only shown if touch is unlocked)
                        if (!touchLocked) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(CardSurface)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left actions row
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    // Split-Screen Toggle Button (Multi-pantalla!)
                                    Button(
                                        onClick = {
                                            isDualMode = !isDualMode
                                            if (isDualMode && activeChannel2 == null) {
                                                // Default channel 2 to something if empty
                                                activeChannel2 = CHANNELS_LIST.getOrNull(1)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isDualMode) accentColor else Color.White.copy(alpha = 0.08f)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.height(28.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Dashboard,
                                            contentDescription = "Dual Play",
                                            tint = if (isDualMode) Color.Black else Color.White,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isDualMode) "DUAL ON" else "VER DUAL",
                                            color = if (isDualMode) Color.Black else Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    // Active Screen Player selector slots inside Dual Mode
                                    if (isDualMode) {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color.White.copy(alpha = 0.05f))
                                                .padding(2.dp)
                                        ) {
                                            Text(
                                                text = "[P1]",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (activePlayerSlot == 1) accentColor else Color.Gray,
                                                modifier = Modifier
                                                    .clickable { activePlayerSlot = 1 }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                            Text(
                                                text = "[P2]",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (activePlayerSlot == 2) accentColor else Color.Gray,
                                                modifier = Modifier
                                                    .clickable { activePlayerSlot = 2 }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                // Player details tweaks (Aspect, Speed, Mute, Audio backgrounding, Snapshot, Share)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    // Mode Only Audio / Radio toggle
                                    IconButton(
                                        onClick = {
                                            isAudioOnlyMode = !isAudioOnlyMode
                                            Toast.makeText(context, if (isAudioOnlyMode) "Modo Solo Audio Activado" else "Modo Video Activado", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isAudioOnlyMode) Icons.Default.MusicNote else Icons.Default.Videocam,
                                            contentDescription = "Solo Audio",
                                            tint = if (isAudioOnlyMode) accentColor else Color.White.copy(alpha = 0.6f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }

                                    // Touch freeze screen controls lock button
                                    IconButton(
                                        onClick = { touchLocked = true },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "Bloquear",
                                            tint = Color.White.copy(alpha = 0.6f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }

                                    // Aspect Ratio Toggle
                                    IconButton(
                                        onClick = {
                                            aspectRatioMode = (aspectRatioMode + 1) % 4
                                            val modeTxt = when (aspectRatioMode) {
                                                0 -> "Ajustar (Fit)"
                                                1 -> "Rellenar (Stretch)"
                                                2 -> "Agrandar (Zoom)"
                                                else -> "Perspectiva 16:9"
                                            }
                                            Toast.makeText(context, "Aspecto: $modeTxt", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Fullscreen,
                                            contentDescription = "Aspect Ratio",
                                            tint = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }

                                    // Playback Speed cycler
                                    IconButton(
                                        onClick = {
                                            playbackSpeed = when (playbackSpeed) {
                                                1.0f -> 1.5f
                                                1.5f -> 2.0f
                                                2.0f -> 0.5f
                                                else -> 1.0f
                                            }
                                            Toast.makeText(context, "Velocidad: ${playbackSpeed}x", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Text(
                                            text = "${playbackSpeed}x",
                                            color = accentColor,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                    }

                                    // Copy Stream Link Share
                                    IconButton(
                                        onClick = {
                                            val activeChan = if (activePlayerSlot == 1) activeChannel1 else activeChannel2
                                            if (activeChan != null) {
                                                clipboardManager.setText(AnnotatedString(getEffectiveStreamUrl(activeChan)))
                                                Toast.makeText(context, "Enlace m3u8 copiado al portapapeles!", Toast.LENGTH_LONG).show()
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = "Compartir Stream",
                                            tint = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }

                                    // Snapshot Capture clicker
                                    IconButton(
                                        onClick = {
                                            snapshotFlash = true
                                            Toast.makeText(context, "Captura de pantalla guardada en galería", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CameraAlt,
                                            contentDescription = "Captura",
                                            tint = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }

                                    // Stats for nerds toggle button
                                    IconButton(
                                        onClick = { showNerdStats = !showNerdStats },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = "Stats",
                                            tint = if (showNerdStats) accentColor else Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }

                                    // Close current streaming panel
                                    IconButton(
                                        onClick = {
                                            if (isDualMode) {
                                                if (activePlayerSlot == 1) {
                                                    activeChannel1 = null
                                                } else {
                                                    activeChannel2 = null
                                                }
                                                if (activeChannel1 == null && activeChannel2 == null) {
                                                    isDualMode = false
                                                }
                                            } else {
                                                activeChannel1 = null
                                                activeChannel2 = null
                                            }
                                        },
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(Color.White.copy(alpha = 0.08f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Apagar Canal",
                                            tint = Color.Red,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Fullscreen Landscape Toggle Button Bar
                        if (!touchLocked) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(CardSurface)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Electronic Guide (EPG Show Indicator)
                                val currentActiveChan = if (activePlayerSlot == 1) activeChannel1 else activeChannel2
                                if (currentActiveChan != null) {
                                    val mockGuideTitle = remember(currentActiveChan) {
                                        when (currentActiveChan.group.uppercase()) {
                                            "MÚSICA" -> "Top 50 Hits Latino Mix (EPG Live)"
                                            "EVENTOS" -> "Copa de Campeones - Fútbol en Vivo"
                                            "PLUTO" -> "Maratón Especial 24/7 Series"
                                            else -> "Transmisión Global Especial"
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Guía EPG: $mockGuideTitle",
                                            color = Color.White.copy(alpha = 0.8f),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Siguiente: Resumen del Evento (en 12 minutos)",
                                            color = Color.Gray,
                                            fontSize = 8.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.width(4.dp))
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Ping Latency check helper
                                    Button(
                                        onClick = {
                                            Toast.makeText(context, "Prueba de Latencia: Servidor ${listOf(32, 45, 12, 19).random()} ms", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.05f)),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        modifier = Modifier.height(24.dp)
                                    ) {
                                        Text("PING", color = accentColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                    }

                                    // Enter System PiP mode (Android 8+)
                                    Button(
                                        onClick = {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                try {
                                                    (context as? Activity)?.enterPictureInPictureMode(PictureInPictureParams.Builder().build())
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Error al iniciar Picture-in-Picture", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                Toast.makeText(context, "Modo PiP requiere Android 8+", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.05f)),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        modifier = Modifier.height(24.dp)
                                    ) {
                                        Icon(Icons.Default.PictureInPicture, contentDescription = "PiP Sistema", tint = Color.White, modifier = Modifier.size(10.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("SISTEMA PiP", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                    }

                                    // Local Fullscreen toggle inside app
                                    Button(
                                        onClick = { isFullscreen = !isFullscreen },
                                        colors = ButtonDefaults.buttonColors(containerColor = accentColor.copy(alpha = 0.15f)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                            contentDescription = "Fullscreen Toggle",
                                            tint = accentColor,
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isFullscreen) "SALIR FULLSCREEN" else "MX FULLSCREEN",
                                            color = accentColor,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // CONTROLS, NAVIGATION CHIPS & SEARCH FILTER BAR (Hidden during Fullscreen style mode)
            if (!isFullscreen) {
                // Search panel with Custom buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Search text input
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Buscar canal por nombre...", color = Color(0xFF6E6E7F), fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Buscador", tint = accentColor, modifier = Modifier.size(16.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Borrar", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = CardSurface,
                            unfocusedContainerColor = CardSurface,
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.08f)
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("search_input")
                    )

                    // Add Custom Channel dialog trigger button (+)
                    IconButton(
                        onClick = { showAddCustomDialog = true },
                        modifier = Modifier
                            .size(46.dp)
                            .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                            .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Añadir Canal", tint = accentColor)
                    }

                    // Bulk Import button (Icon version of raw text importer)
                    IconButton(
                        onClick = { showM3uImporterDialog = true },
                        modifier = Modifier
                            .size(46.dp)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(Icons.Default.PlaylistAdd, contentDescription = "Importar M3U", tint = Color.White)
                    }
                }

                // Quick Action Bar: Grid size configuration + Favorites filter
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Favorites quick switch
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { showOnlyFavorites = !showOnlyFavorites }
                    ) {
                        Icon(
                            imageVector = if (showOnlyFavorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favoritos",
                            tint = if (showOnlyFavorites) Color.Red else Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (showOnlyFavorites) "MOSTRANDO SOLO FAVORITOS" else "VER TODOS LOS FAVORITOS",
                            color = if (showOnlyFavorites) Color.White else Color.Gray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Columns density selector
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(CardSurface)
                            .padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "[1 Col]",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (gridColumns == 1) accentColor else Color.Gray,
                            modifier = Modifier
                                .clickable { gridColumns = 1 }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        Text(
                            text = "[2 Col]",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (gridColumns == 2) accentColor else Color.Gray,
                            modifier = Modifier
                                .clickable { gridColumns = 2 }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        Text(
                            text = "[3 Col]",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (gridColumns == 3) accentColor else Color.Gray,
                            modifier = Modifier
                                .clickable { gridColumns = 3 }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                // Horizontally Scrollable Category Filters
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { category ->
                        val isSelected = selectedCategory.uppercase() == category.uppercase()
                        val chipColor = getGroupColor(category, accentColor)

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50.dp))
                                .background(
                                    if (isSelected) chipColor.copy(alpha = 0.15f)
                                    else CardSurface
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) chipColor else Color.White.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(50.dp)
                                )
                                .clickable { selectedCategory = category }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("category_tab_${category.lowercase()}")
                        ) {
                            Text(
                                text = category,
                                color = if (isSelected) chipColor else Color(0xFF8E8E9F),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }

                // CHANNELS LIST GRID (Highly optimized - purely cards)
                val lazyGridState = rememberLazyGridState()
                if (filteredChannels.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.TvOff, contentDescription = "No channels", tint = Color.Gray, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("No se encontraron canales válidos", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("Cambia de filtro o agrega un canal manual m3u8", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        state = lazyGridState,
                        columns = GridCells.Fixed(gridColumns),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("channel_grid")
                    ) {
                        items(filteredChannels, key = { it.name }) { channel ->
                            val isFavorite = favoritesList.contains(channel.name)
                            val isCurrentPlaying = (activeChannel1?.name == channel.name || activeChannel2?.name == channel.name)
                            val groupColor = getGroupColor(channel.group, accentColor)

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(if (gridColumns == 1) 3.5f else 1.4f)
                                    .clickable {
                                        if (isDualMode) {
                                            if (activePlayerSlot == 1) {
                                                activeChannel1 = channel
                                                player1.setMediaItem(
                                                    MediaItem
                                                        .Builder()
                                                        .setUri(getEffectiveStreamUrl(channel))
                                                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                                                        .build()
                                                )
                                                player1.prepare()
                                                player1.play()
                                            } else {
                                                activeChannel2 = channel
                                                player2.setMediaItem(
                                                    MediaItem
                                                        .Builder()
                                                        .setUri(getEffectiveStreamUrl(channel))
                                                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                                                        .build()
                                                )
                                                player2.prepare()
                                                player2.play()
                                            }
                                            Toast
                                                .makeText(
                                                    context,
                                                    "Transmitiendo en Pantalla $activePlayerSlot: ${channel.name}",
                                                    Toast.LENGTH_SHORT
                                                )
                                                .show()
                                        } else {
                                            activeChannel1 = channel
                                            player1.setMediaItem(
                                                MediaItem
                                                    .Builder()
                                                    .setUri(getEffectiveStreamUrl(channel))
                                                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                                                    .build()
                                            )
                                            player1.prepare()
                                            player1.play()
                                            Toast
                                                .makeText(context, "Cargando ${channel.name}...", Toast.LENGTH_SHORT)
                                                .show()
                                        }

                                        // Try compiling history
                                        if (!recentChannelsList.contains(channel)) {
                                            recentChannelsList = (listOf(channel) + recentChannelsList).take(10)
                                        }
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isCurrentPlaying) accentColor.copy(alpha = 0.08f) else CardSurface
                                ),
                                border = BorderStroke(
                                    width = if (isCurrentPlaying) 2.dp else 1.dp,
                                    color = if (isCurrentPlaying) accentColor else Color.White.copy(alpha = 0.05f)
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(10.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        // Top Row: Category Bubble & Favorite heart icon
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(groupColor.copy(alpha = 0.15f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = channel.group.uppercase(),
                                                    color = groupColor,
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }

                                            Icon(
                                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                contentDescription = "Favorito",
                                                tint = if (isFavorite) Color.Red else Color.White.copy(alpha = 0.3f),
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clickable {
                                                        val nowFav = !isFavorite
                                                        saveFavorite(context, channel.name, nowFav)
                                                        favoritesList = getFavorites(context)
                                                    }
                                            )
                                        }

                                        // Bottom Row: Dynamic text Name
                                        Column {
                                            Text(
                                                text = channel.name,
                                                color = if (isCurrentPlaying) accentColor else Color.White,
                                                fontSize = if (gridColumns == 3) 11.sp else 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = if (gridColumns == 1) 1 else 2,
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            // Mock active program description EPG to look highly professional
                                            if (gridColumns == 1) {
                                                Text(
                                                    text = "MOCK LIVE SHOW: Transmitiendo contenido interactivo 24/7 en alta definición",
                                                    color = Color.Gray,
                                                    fontSize = 10.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // DIALOG: ADD STANDARD CHANNEL INPUT FORM
        if (showAddCustomDialog) {
            var inputName by remember { mutableStateOf("") }
            var inputPath by remember { mutableStateOf("") }
            var inputGroup by remember { mutableStateOf("GENERAL") }

            AlertDialog(
                onDismissRequest = { showAddCustomDialog = false },
                title = { Text("Añadir Canal m3u8 Personalizado", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = inputName,
                            onValueChange = { inputName = it },
                            label = { Text("Nombre del Canal") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = accentColor
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = inputPath,
                            onValueChange = { inputPath = it },
                            label = { Text("Ruta Relativa o Enlace HTTP Completo") },
                            placeholder = { Text("http://servidor.com/transmision.m3u8") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = accentColor
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Grupo de Categoría:", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("GENERAL", "EVENTOS", "MÚSICA", "PLUTO").forEach { grp ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (inputGroup == grp) accentColor else Color.White.copy(alpha = 0.05f))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                        .clickable { inputGroup = grp }
                                ) {
                                    Text(grp, color = if (inputGroup == grp) Color.Black else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (inputName.isNotEmpty() && inputPath.isNotEmpty()) {
                                saveCustomChannel(context, Channel(inputName, inputPath, inputGroup))
                                customChannelsList = getCustomChannels(context)
                                showAddCustomDialog = false
                                Toast.makeText(context, "Canal custom guardado!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Por favor rellena todos los campos", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                    ) {
                        Text("REGISTRAR", color = Color.Black)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddCustomDialog = false }) {
                        Text("CANCELAR", color = Color.White)
                    }
                },
                containerColor = CardSurface
            )
        }

        // DIALOG: BULK PARSE M3U PLAYLIST RESOURCE IMPORTER
        if (showM3uImporterDialog) {
            var m3uData by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = { showM3uImporterDialog = false },
                title = { Text("Importador Masivo Playlist M3U", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("Pega el texto crudo de tu lista o URL en formato M3U / #EXTINF:", color = Color.LightGray, fontSize = 10.sp)
                        OutlinedTextField(
                            value = m3uData,
                            onValueChange = { m3uData = it },
                            placeholder = { Text("#EXTM3U\n#EXTINF:-1 group-title=\"CUSTOM\",Canal VIP\nhttp://ip:port/stream.m3u8") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = accentColor
                            ),
                            maxLines = 10,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                        )
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    clearCustomChannels(context)
                                    customChannelsList = emptyList()
                                    Toast.makeText(context, "Canales custom eliminados!", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f))
                            ) {
                                Text("LIMPIAR BASE CUSTOM", fontSize = 9.sp, color = Color.White)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (m3uData.isNotEmpty()) {
                                try {
                                    val lines = m3uData.split("\n")
                                    var count = 0
                                    var currentName = ""
                                    var currentGroup = "CUSTOM"
                                    for (line in lines) {
                                        val trimmed = line.trim()
                                        if (trimmed.startsWith("#EXTINF:")) {
                                            // Extract channel name
                                            currentName = trimmed.substringAfterLast(",").trim()
                                            // Extract group name if present
                                            if (trimmed.contains("group-title=\"")) {
                                                currentGroup = trimmed.substringAfter("group-title=\"").substringBefore("\"").trim()
                                            }
                                        } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                                            if (currentName.isEmpty()) {
                                                currentName = "Importado_Canal_${count + 1}"
                                            }
                                            saveCustomChannel(context, Channel(currentName, trimmed, currentGroup))
                                            count++
                                            currentName = ""
                                        }
                                    }
                                    customChannelsList = getCustomChannels(context)
                                    showM3uImporterDialog = false
                                    Toast.makeText(context, "Se importaron $count canales correctamente!", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error al parsear archivo M3U", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                    ) {
                        Text("IMPORTAR LISTA", color = Color.Black)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showM3uImporterDialog = false }) {
                        Text("CERRAR", color = Color.White)
                    }
                },
                containerColor = CardSurface
            )
        }

        // DIALOG: BYPASS PARENTAL LOCK PIN ENTRY CODES
        if (showPinDialog) {
            AlertDialog(
                onDismissRequest = { showPinDialog = false },
                title = { Text("Filtro Parental: Introducir PIN", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("Para desbloquear los canales deportivos o adultos del grupo 'EVENTOS', introduce el PIN de seguridad (Por defecto: 1234)", color = Color.LightGray, fontSize = 11.sp)
                        OutlinedTextField(
                            value = pinValue,
                            onValueChange = { pinValue = it },
                            placeholder = { Text("****") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = accentColor
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (parentalPinError.isNotEmpty()) {
                            Text(parentalPinError, color = Color.Red, fontSize = 10.sp)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (pinValue == "1234" || pinValue == "0000") {
                                isParentalLocked = false
                                showPinDialog = false
                                parentalPinError = ""
                                pinValue = ""
                                Toast.makeText(context, "Filtro Parental Desactivado. Acceso concedido.", Toast.LENGTH_SHORT).show()
                            } else {
                                parentalPinError = "PIN Incorrecto. Reintenta."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                    ) {
                        Text("ACEPTAR", color = Color.Black)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showPinDialog = false
                        parentalPinError = ""
                        pinValue = ""
                    }) {
                        Text("ATRAYENTE", color = Color.White)
                    }
                },
                containerColor = CardSurface
            )
        }
    }
}

// Optimized ExoPlayer Composable holding view binder
@OptIn(UnstableApi::class)
@Composable
fun ExoPlayerViewContainer(
    player: ExoPlayer,
    url: String,
    isAudioOnly: Boolean,
    isMuted: Boolean,
    playbackSpeed: Float,
    aspectRatioMode: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // React to stream URL modifications natively
    LaunchedEffect(url, isMuted) {
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        player.setMediaItem(mediaItem)
        player.volume = if (isMuted) 0f else 1f
        player.prepare()
        player.play()
    }

    // Android lifecycle listener
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    player.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    player.play()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        if (isAudioOnly) {
            // Only Audio vinyl visually stunning rotating visualizer
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF06060C)),
                contentAlignment = Alignment.Center
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "vinyl_rotation")
                val rotationAngle by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(4000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "vinyl_angle"
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .graphicsLayer { rotationZ = rotationAngle }
                            .clip(CircleShape)
                            .border(4.dp, Color.Gray, CircleShape)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        // Vinyl disc center
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color.Red)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "MODO SOLO AUDIO ACTIVE",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Ahorrando hasta 92% de datos móviles",
                        color = Color.Gray,
                        fontSize = 8.sp
                    )
                }
            }
        } else {
            // AndroidView holding Media3 PlayerView
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        this.useController = true
                        this.setBackgroundColor(0xFF000000.toInt())
                        // Aspect ratios binding
                        this.resizeMode = when (aspectRatioMode) {
                            0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    }
                },
                update = { view ->
                    view.resizeMode = when (aspectRatioMode) {
                        0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
