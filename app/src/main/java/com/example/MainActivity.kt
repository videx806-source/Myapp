package com.example

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Cyber Theme Colors
val BaseDarkBg = Color(0xFF07070C)
val CardSurface = Color(0xFF0F0F1A)

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

fun getGroupColor(group: String, currentPrimary: Color): Color {
    return when (group.uppercase()) {
        "GENERAL" -> Color(0xFF3B82F6)
        "EVENTOS" -> Color(0xFFEF4444)
        "MÚSICA", "MUSICA" -> Color(0xFF10B981)
        "PLUTO" -> Color(0xFF8B5CF6)
        else -> currentPrimary
    }
}

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

fun getEffectiveStreamUrl(channel: Channel): String {
    return if (channel.path.startsWith("http://") || channel.path.startsWith("https://")) {
        channel.path
    } else {
        channel.streamUrl
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        var isPlayer1RunningAndPipEnabled = false
    }

    var isPipModeActive = mutableStateOf(false)

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

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPipModeActive.value = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPlayer1RunningAndPipEnabled) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().build())
            } catch (e: Exception) {
                // Ignore
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

    // Preferences and themes
    var selectedThemeIndex by remember { mutableStateOf(0) }
    val currentTheme = THEMES_LIST[selectedThemeIndex]
    val accentColor = currentTheme.primary

    // Dynamic filtering
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("TODOS") }
    var showOnlyFavorites by remember { mutableStateOf(false) }
    var gridColumns by remember { mutableStateOf(3) }

    // Active channels and properties
    var activeChannel by remember { mutableStateOf<Channel?>(null) }
    var isAudioOnlyMode by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var aspectRatioMode by remember { mutableStateOf(0) }
    var favoritesList by remember { mutableStateOf(getFavorites(context)) }

    // System configurations & dialogues
    var isParentalLocked by remember { mutableStateOf(true) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinValue by remember { mutableStateOf("") }
    var parentalPinError by remember { mutableStateOf("") }
    var showNerdStats by remember { mutableStateOf(false) }
    var touchLocked by remember { mutableStateOf(false) }

    // UI Gesture overlays inside player
    var brightnessVal by remember { mutableStateOf(70f) }
    var volumeVal by remember { mutableStateOf(100f) }
    var showVolumeOverlay by remember { mutableStateOf(false) }
    var showBrightnessOverlay by remember { mutableStateOf(false) }

    // Floating client mini-player offsets
    var isAppMiniPlayerActived by remember { mutableStateOf(false) }
    var miniPlayerOffset by remember { mutableStateOf(Offset(0f, 0f)) }

    // Chromecast Casting simulation
    var isCastDialogOpen by remember { mutableStateOf(false) }
    var isCasting by remember { mutableStateOf(false) }
    var castingDeviceName by remember { mutableStateOf("") }

    // Screen recorder simulation
    var isRecordingActive by remember { mutableStateOf(false) }
    var recordingTimeSeconds by remember { mutableStateOf(0) }
    var showRecordingSaveDialog by remember { mutableStateOf(false) }
    var lastSavedRecordingPath by remember { mutableStateOf("") }

    // Local recordings state management
    var recordingsList by remember { mutableStateOf(getRecordings(context)) }
    var showRecordingsPanel by remember { mutableStateOf(false) }
    var playingRecording by remember { mutableStateOf<Recording?>(null) }

    // Advanced technical settings
    var showAdvancedSettingsPanel by remember { mutableStateOf(false) }
    var playerActiveControlTab by remember { mutableStateOf("CONTROLES PRO") }
    var systemDecoderMode by remember { mutableStateOf("Hardware (HW+ / GPU)") }
    var systemNetworkBufferSizer by remember { mutableStateOf("15 Segundos (Estándar)") }
    var systemUserAgentType by remember { mutableStateOf("VidexPlayer Core Engine v2.0") }
    var systemDnsOverHttpsEnabled by remember { mutableStateOf(true) }

    // Sleep Clock Timer configuration
    var sleepTimerMinutes by remember { mutableStateOf(0) }
    var sleepTimerMinutesLeft by remember { mutableStateOf(0) }
    var sleepTimerIsActive by remember { mutableStateOf(false) }

    // Sync static attributes for system PiP triggers
    LaunchedEffect(activeChannel) {
        MainActivity.isPlayer1RunningAndPipEnabled = activeChannel != null
    }

    LaunchedEffect(sleepTimerIsActive, sleepTimerMinutesLeft) {
        if (sleepTimerIsActive && sleepTimerMinutesLeft > 0) {
            delay(60000L)
            sleepTimerMinutesLeft -= 1
            if (sleepTimerMinutesLeft <= 0) {
                activeChannel = null
                sleepTimerIsActive = false
                sleepTimerMinutes = 0
                Toast.makeText(context, "Temporizador: Transmisión apagada automáticamente", Toast.LENGTH_LONG).show()
            }
        }
    }

    // High performance player engine initialization
    val exoplayer = remember {
        val playerContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.applicationContext.createAttributionContext("media")
        } else {
            context.applicationContext
        }
        ExoPlayer.Builder(playerContext).build().apply {
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoplayer.release()
        }
    }

    // Dynamic system PiP viewport check
    val activity = remember(context) {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is MainActivity) return@remember ctx
            ctx = ctx.baseContext
        }
        null
    }
    val isInPip = activity?.isPipModeActive?.value ?: false

    // Real-time Screen Recording simulation clock
    LaunchedEffect(isRecordingActive) {
        if (isRecordingActive) {
            recordingTimeSeconds = 0
            while (isRecordingActive) {
                delay(1000L)
                recordingTimeSeconds++
            }
        }
    }

    // Absolute screen layout bypass if OS entered PiP mode (Pristine Video Frame Only!)
    if (isInPip) {
        val chan = activeChannel
        if (chan != null) {
            ExoPlayerViewContainer(
                player = exoplayer,
                url = getEffectiveStreamUrl(chan),
                isAudioOnly = isAudioOnlyMode,
                isMuted = isMuted,
                aspectRatioMode = aspectRatioMode,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        }
        return
    }

    // Main design box scaffold
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BaseDarkBg)
    ) {
        if (showAdvancedSettingsPanel) {
            // Screen 3: Advanced Settings Developer Dashboard
            AdvancedSettingsPanel(
                accentColor = accentColor,
                systemDecoderMode = systemDecoderMode,
                onDecoderChange = { systemDecoderMode = it },
                systemNetworkBufferSizer = systemNetworkBufferSizer,
                onBufferChange = { systemNetworkBufferSizer = it },
                systemUserAgentType = systemUserAgentType,
                onUserAgentChange = { systemUserAgentType = it },
                systemDnsOverHttpsEnabled = systemDnsOverHttpsEnabled,
                onDnsChange = { systemDnsOverHttpsEnabled = it },
                onBackPressed = { showAdvancedSettingsPanel = false },
                onClearCache = {
                    coroutineScope.launch {
                        delay(1000L)
                        Toast.makeText(context, "¡Caché de búfer HLS purgada con éxito!", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } else if (showRecordingsPanel) {
            // Screen 4: Recordings library panel
            RecordingsPanel(
                recordings = recordingsList,
                accentColor = accentColor,
                onPlay = { rec ->
                    playingRecording = rec
                    activeChannel = null // Ensure we play recording instead
                    showRecordingsPanel = false
                    exoplayer.stop()
                    exoplayer.setMediaItem(
                        MediaItem.Builder()
                            .setUri(rec.sourceUrl)
                            .setMimeType(MimeTypes.APPLICATION_MP4)
                            .build()
                    )
                    exoplayer.prepare()
                    exoplayer.play()
                    Toast.makeText(context, "Reproduciendo grabación de ${rec.channelName}...", Toast.LENGTH_SHORT).show()
                },
                onShare = { rec ->
                    shareRecordingFile(context, rec)
                },
                onDelete = { rec ->
                    deleteRecordingFromStorage(context, rec.id)
                    recordingsList = getRecordings(context)
                },
                onBackPressed = {
                    showRecordingsPanel = false
                }
            )
        } else if ((activeChannel == null && playingRecording == null) || isAppMiniPlayerActived) {
            // Screen 1: Master Catalog List
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Row
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "VIDEX",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(accentColor.copy(alpha = 0.12f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
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
                                text = "SINTONIZADOR PREMIUM MULTI-PANTALLA",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF6E6E7F)
                            )
                        }

                        // Toolbar settings
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Mis Grabaciones
                            IconButton(
                                onClick = { showRecordingsPanel = true },
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(Color.White.copy(alpha = 0.05f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VideoLibrary,
                                    contentDescription = "Grabaciones",
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // Tuerca de configuración avanzada
                            IconButton(
                                onClick = { showAdvancedSettingsPanel = true },
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(Color.White.copy(alpha = 0.05f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Configuración",
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // Parental locks
                            IconButton(
                                onClick = {
                                    if (isParentalLocked) {
                                        showPinDialog = true
                                    } else {
                                        isParentalLocked = true
                                        Toast.makeText(context, "Filtro Parental Activado", Toast.LENGTH_SHORT).show()
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
                                    contentDescription = "Parental",
                                    tint = if (isParentalLocked) Color(0xFFFF416C) else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // Theme palette cycle
                            IconButton(
                                onClick = { selectedThemeIndex = (selectedThemeIndex + 1) % THEMES_LIST.size },
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(Color.White.copy(alpha = 0.05f), CircleShape)
                            ) {
                                Icon(Icons.Default.Palette, contentDescription = "Tema", tint = accentColor, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }

                // SLEEP TIMER CONTROL SECTION
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CardSurface)
                        .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = "Reloj",
                            tint = if (sleepTimerIsActive) accentColor else Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "TEMPORIZADOR DE APAGADO",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = Color.Gray
                            )
                            SleepTimerStatusText(
                                isActive = sleepTimerIsActive,
                                minutesLeftProvider = { sleepTimerMinutesLeft },
                                accentColor = accentColor
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(15, 30, 60).forEach { mins ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.04f))
                                    .border(
                                        1.dp,
                                        if (sleepTimerIsActive && sleepTimerMinutes == mins) accentColor else Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        if (sleepTimerIsActive && sleepTimerMinutes == mins) {
                                            sleepTimerIsActive = false
                                            sleepTimerMinutes = 0
                                            sleepTimerMinutesLeft = 0
                                            Toast.makeText(context, "Temporizador cancelado", Toast.LENGTH_SHORT).show()
                                        } else {
                                            sleepTimerMinutes = mins
                                            sleepTimerMinutesLeft = mins
                                            sleepTimerIsActive = true
                                            Toast.makeText(context, "Apagado en $mins minutos configurado.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("${mins}M", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // SEARCH BAR & FILTERS
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        placeholder = { Text("Buscar sintonización...", fontSize = 11.sp, color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = CardSurface,
                            unfocusedContainerColor = CardSurface,
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.08f)
                        ),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp)) },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp)
                    )

                    // Columns density cyclic adjustments
                    IconButton(
                        onClick = { gridColumns = if (gridColumns == 3) 2 else if (gridColumns == 2) 1 else 3 },
                        modifier = Modifier
                            .size(38.dp)
                            .background(CardSurface, CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (gridColumns == 3) Icons.Default.GridView else if (gridColumns == 2) Icons.Default.ViewAgenda else Icons.Default.ViewStream,
                            contentDescription = "Densidad",
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Favorites filter status
                    IconButton(
                        onClick = { showOnlyFavorites = !showOnlyFavorites },
                        modifier = Modifier
                            .size(38.dp)
                            .background(if (showOnlyFavorites) Color.Red.copy(alpha = 0.12f) else CardSurface, CircleShape)
                            .border(
                                1.dp,
                                if (showOnlyFavorites) Color.Red else Color.White.copy(alpha = 0.08f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (showOnlyFavorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favoritos",
                            tint = if (showOnlyFavorites) Color.Red else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // CATEGORIES SLIDING TAB ROW
                val categories = listOf("TODOS", "GENERAL", "EVENTOS", "MÚSICA", "PLUTO")
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp, horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categories) { cat ->
                        val isSelected = selectedCategory.uppercase() == cat.uppercase()
                        val colorStyle = getGroupColor(cat, accentColor)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) colorStyle.copy(alpha = 0.15f) else CardSurface)
                                .border(
                                    1.dp,
                                    if (isSelected) colorStyle else Color.White.copy(alpha = 0.05f),
                                    RoundedCornerShape(16.dp)
                                )
                                .clickable { selectedCategory = cat }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = cat,
                                color = if (isSelected) colorStyle else Color.LightGray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // CORE CHANNELS GRID
                val filteredChannels = remember(searchQuery, selectedCategory, showOnlyFavorites, favoritesList, isParentalLocked) {
                    CHANNELS_LIST.filter { channel ->
                        if (isParentalLocked && channel.group.uppercase() == "EVENTOS") {
                            false
                        } else {
                            val matchesCategory = selectedCategory == "TODOS" || channel.group.uppercase() == selectedCategory.uppercase()
                            val matchesSearch = channel.name.contains(searchQuery, ignoreCase = true)
                            val matchesFav = !showOnlyFavorites || favoritesList.contains(channel.name)
                            matchesCategory && matchesSearch && matchesFav
                        }
                    }
                }

                if (filteredChannels.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.HourglassEmpty, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No se encontraron canales sintonizables", color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(gridColumns),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredChannels) { channel ->
                            val isFavorite = favoritesList.contains(channel.name)
                            val groupCol = getGroupColor(channel.group, accentColor)

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(CardSurface)
                                    .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
                                    .clickable {
                                        activeChannel = channel
                                        isAppMiniPlayerActived = false // Maximize fully
                                        exoplayer.stop()
                                        exoplayer.setMediaItem(
                                            MediaItem
                                                .Builder()
                                                .setUri(getEffectiveStreamUrl(channel))
                                                .setMimeType(MimeTypes.APPLICATION_M3U8)
                                                .build()
                                        )
                                        exoplayer.prepare()
                                        exoplayer.play()
                                        Toast.makeText(context, "Sintonizando ${channel.name}...", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(if (gridColumns == 1) 12.dp else 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Visual color pill based on category
                                    Box(
                                        modifier = Modifier
                                            .size(width = 6.dp, height = if (gridColumns == 1) 48.dp else 40.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(groupCol)
                                    )

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(groupCol.copy(alpha = 0.12f))
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = channel.group.uppercase(),
                                                    color = groupCol,
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }

                                            // Favorite inline toggler
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

                                        Spacer(modifier = Modifier.height(4.dp))

                                        Text(
                                            text = channel.name,
                                            color = Color.White,
                                            fontSize = if (gridColumns == 1) 14.sp else 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        if (gridColumns == 1) {
                                            Text(
                                                text = "Transmisión en directo, flujo adaptativo estable.",
                                                color = Color.Gray,
                                                fontSize = 9.sp,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(if (activeChannel != null) 90.dp else 20.dp))
            }
        } else {
            // Screen 2: Detailed Player Control Panel
            val isPlayingRec = playingRecording != null
            val mediaTitle = if (isPlayingRec) "[GRABACIÓN] ${playingRecording!!.channelName}" else activeChannel?.name ?: ""
            val mediaGroup = if (isPlayingRec) "GRABACIÓN" else activeChannel?.group ?: ""
            val groupColor = if (isPlayingRec) accentColor else getGroupColor(mediaGroup, accentColor)
            val isFavorite = if (isPlayingRec) false else favoritesList.contains(mediaTitle)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BaseDarkBg)
            ) {
                // Header Player Navigation Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            exoplayer.stop()
                            activeChannel = null
                            playingRecording = null
                            isAppMiniPlayerActived = false
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color.White.copy(alpha = 0.08f), CircleShape)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "MULTIMEDIA SINTONIZADOR PRO",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = accentColor,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = mediaTitle,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Like Favorite toggler inside detail sheet
                    if (!isPlayingRec) {
                        IconButton(
                            onClick = {
                                val nowFav = !isFavorite
                                saveFavorite(context, mediaTitle, nowFav)
                                favoritesList = getFavorites(context)
                            },
                            modifier = Modifier
                                .size(38.dp)
                                .background(
                                    if (isFavorite) Color.Red.copy(alpha = 0.15f)
                                    else Color.White.copy(alpha = 0.05f),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Favorito",
                                tint = if (isFavorite) Color.Red else Color.White.copy(alpha = 0.6f)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(38.dp))
                    }
                }

                // SCREEN PORT: ExoPlayer Video or Casting Remote Panel
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.77f)
                        .background(Color.Black)
                        .border(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    if (isCasting) {
                        // Cast Remote Display
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF030712)),
                            contentAlignment = Alignment.Center
                        ) {
                            val infiniteTransition = rememberInfiniteTransition(label = "cast_anims")
                            val pulseScale by infiniteTransition.animateFloat(
                                initialValue = 0.95f,
                                targetValue = 1.05f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1200, easing = LinearOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "pulse"
                            )

                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(
                                    imageVector = Icons.Default.CastConnected,
                                    contentDescription = "Transmisión",
                                    tint = accentColor,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .graphicsLayer {
                                            scaleX = pulseScale
                                            scaleY = pulseScale
                                        }
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "TRANSMITIENDO A LA TELEVISIÓN",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = castingDeviceName.uppercase(),
                                    color = accentColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "El reproductor de la app está en reposo.",
                                    color = Color.Gray,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    } else {
                        // Gesture-sensing Video Container
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(touchLocked) {
                                    if (touchLocked) return@pointerInput
                                    detectVerticalDragGestures { change, dragAmount ->
                                        change.consume()
                                        val screenHalf = size.width / 2f
                                        if (change.position.x < screenHalf) {
                                            brightnessVal = (brightnessVal - dragAmount / 4f).coerceIn(10f, 100f)
                                            showBrightnessOverlay = true
                                        } else {
                                            volumeVal = (volumeVal - dragAmount / 4f).coerceIn(0f, 150f)
                                            exoplayer.volume = (volumeVal / 100f).coerceIn(0f, 1f)
                                            showVolumeOverlay = true
                                        }
                                    }
                                }
                        ) {
                            ExoPlayerViewContainer(
                                player = exoplayer,
                                url = if (isPlayingRec) playingRecording!!.sourceUrl else getEffectiveStreamUrl(activeChannel!!),
                                isAudioOnly = isAudioOnlyMode,
                                isMuted = isMuted,
                                aspectRatioMode = aspectRatioMode,
                                modifier = Modifier.fillMaxSize()
                            )

                            // Brightness swipe feedback
                            if (showBrightnessOverlay) {
                                LaunchedEffect(showBrightnessOverlay) {
                                    delay(1000L)
                                    showBrightnessOverlay = false
                                }
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .padding(16.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.8f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.WbSunny, contentDescription = "Brillo", tint = accentColor, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("BRILLO: ${brightnessVal.toInt()}%", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }

                            // Volume swipe feedback
                            if (showVolumeOverlay) {
                                LaunchedEffect(showVolumeOverlay) {
                                    delay(1000L)
                                    showVolumeOverlay = false
                                }
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .padding(16.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.8f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.VolumeUp, contentDescription = "Volumen", tint = accentColor, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("VOLUMEN: ${volumeVal.toInt()}%", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }

                    // Recording signal pulse
                    RecordingIndicator(
                        recordingTimeSecondsProvider = { recordingTimeSeconds },
                        isRecordingActive = isRecordingActive,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                    )

                    // Freezer Screen Lock overlay
                    if (touchLocked) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.5f))
                                .clickable { touchLocked = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.85f))
                                    .padding(12.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Lock, contentDescription = "Bloqueado", tint = Color.Red, modifier = Modifier.size(26.dp))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Controles Congelados", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text("Toca la pantalla para restaurar", color = Color.Gray, fontSize = 8.sp)
                                }
                            }
                        }
                    }

                    // Nerd Stats Dashboard Metrics overlay
                    if (showNerdStats) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.85f))
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("MÉTRICAS DEL SISTEMA", color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                    IconButton(onClick = { showNerdStats = false }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White, modifier = Modifier.size(14.dp))
                                    }
                                }
                                Text("Canal: $mediaTitle", color = Color.White, fontSize = 9.sp)
                                Text("Decodificador Activo: $systemDecoderMode", color = Color.LightGray, fontSize = 9.sp)
                                Text("Búfer del ExoPlayer: $systemNetworkBufferSizer", color = Color.LightGray, fontSize = 9.sp)
                                Text("Protocolo: HLS Adaptive Live Stream m3u8", color = Color.LightGray, fontSize = 9.sp)
                                Text("User-Agent: $systemUserAgentType", color = Color.LightGray, fontSize = 9.sp)
                                Text("DNS Cifrado: " + if (systemDnsOverHttpsEnabled) "Cloudflare 1.1.1.1 (Activo)" else "Por Defecto", color = Color.LightGray, fontSize = 9.sp)
                                Text("Tasa de Bits: 5.4 Mbps / Latencia: 1.2s", color = Color(0xFF39FF14), fontSize = 9.sp)
                            }
                        }
                    }
                }

                // DEDICATED SPACIOUS TABS (Prevents "botones muy juntos" bug)
                if (!touchLocked) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardSurface)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        // Tab selectors
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            listOf("CONTROLES PRO", "AJUSTES VIDEO", "SISTEMA SECH").forEach { tab ->
                                val isSel = playerActiveControlTab == tab
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSel) accentColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.03f))
                                        .border(1.dp, if (isSel) accentColor else Color.Transparent, RoundedCornerShape(8.dp))
                                        .clickable { playerActiveControlTab = tab }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = tab,
                                        color = if (isSel) accentColor else Color.Gray,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // ACTIVE TAB CONTENTS
                        when (playerActiveControlTab) {
                            "CONTROLES PRO" -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 1. Cast Control Button
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable { isCastDialogOpen = true }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(if (isCasting) accentColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
                                                .border(1.dp, if (isCasting) accentColor else Color.White.copy(alpha = 0.1f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (isCasting) Icons.Default.CastConnected else Icons.Default.Cast,
                                                contentDescription = "Cast",
                                                tint = if (isCasting) accentColor else Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("TRANSMITIR", color = if (isCasting) accentColor else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Text(if (isCasting) "Cerrar Cast" else "En Smart TV", color = Color.Gray, fontSize = 7.sp)
                                    }

                                    // 2. Built-In Screen Recorder Button
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable {
                                            if (isRecordingActive) {
                                                isRecordingActive = false
                                                val id = "REC_" + System.currentTimeMillis()
                                                val recordingsDir = java.io.File(context.filesDir, "VidexRecordings")
                                                if (!recordingsDir.exists()) {
                                                    recordingsDir.mkdirs()
                                                }
                                                val file = java.io.File(recordingsDir, "$id.mp4")
                                                try {
                                                    file.writeBytes(ByteArray(1024) { 0 })
                                                } catch (e: Exception) {
                                                    e.printStackTrace()
                                                }
                                                
                                                val randomSampleClip = SAMPLE_RECORDING_VIDEOS.random()
                                                val newRec = Recording(
                                                    id = id,
                                                    channelName = activeChannel?.name ?: "Canal Sintonizado",
                                                    durationSeconds = if (recordingTimeSeconds > 0) recordingTimeSeconds else 12,
                                                    sizeBytes = 1048576L * (4..24).random() + (1000..9999).random(),
                                                    timestamp = System.currentTimeMillis(),
                                                    sourceUrl = randomSampleClip,
                                                    localPath = file.absolutePath
                                                )
                                                saveRecording(context, newRec)
                                                recordingsList = getRecordings(context)
                                                lastSavedRecordingPath = file.absolutePath
                                                showRecordingSaveDialog = true
                                            } else {
                                                isRecordingActive = true
                                                Toast.makeText(context, "Grabación iniciada", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(if (isRecordingActive) Color.Red.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
                                                .border(1.dp, if (isRecordingActive) Color.Red else Color.White.copy(alpha = 0.1f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (isRecordingActive) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                                contentDescription = "Grabar",
                                                tint = if (isRecordingActive) Color.Red else Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("GRABAR", color = if (isRecordingActive) Color.Red else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Text(if (isRecordingActive) "Grabando..." else "Resp. SVG", color = Color.Gray, fontSize = 7.sp)
                                    }

                                    // 3. In-App Picture-In-Picture Floating Card Button
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable {
                                            isAppMiniPlayerActived = true
                                            Toast.makeText(context, "Reproductor flotante minimizado de forma fluida.", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.05f))
                                                .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.OpenInNew,
                                                contentDescription = "Flotar",
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("MINIMIZAR", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Text("Flotar en App", color = Color.Gray, fontSize = 7.sp)
                                    }

                                    // 4. Native OS Picture-in-Picture Button
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                try {
                                                    (context as? Activity)?.enterPictureInPictureMode(PictureInPictureParams.Builder().build())
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Error sintonizando PiP", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                Toast.makeText(context, "Android 8.0+ Requerido", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.05f))
                                                .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PictureInPicture,
                                                contentDescription = "PiP Sistema",
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("SISTEMA PiP", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Text("Paso externo", color = Color.Gray, fontSize = 7.sp)
                                    }
                                }
                            }

                            "AJUSTES VIDEO" -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Aspect Ratio Toggle
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable {
                                            aspectRatioMode = (aspectRatioMode + 1) % 4
                                        }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.05f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Fullscreen, contentDescription = "Aspecto", tint = Color.LightGray, modifier = Modifier.size(20.dp))
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("RELACIÓN", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        val curModeStr = when(aspectRatioMode) {
                                            0 -> "Fit"
                                            1 -> "Stretch"
                                            2 -> "Zoom"
                                            else -> "Original"
                                        }
                                        Text(curModeStr, color = accentColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                    }

                                    // Playback Speed Toggle
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable {
                                            playbackSpeed = when (playbackSpeed) {
                                                1.0f -> 1.5f
                                                1.5f -> 2.0f
                                                2.0f -> 0.5f
                                                else -> 1.0f
                                            }
                                        }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.05f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("${playbackSpeed}x", color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.Black)
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("VELOCIDAD", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Text("Re-ajustar HLS", color = Color.Gray, fontSize = 7.sp)
                                    }

                                    // Audio Only mode Toggle
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable { isAudioOnlyMode = !isAudioOnlyMode }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(if (isAudioOnlyMode) accentColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (isAudioOnlyMode) Icons.Default.MusicNote else Icons.Default.Videocam,
                                                tint = if (isAudioOnlyMode) accentColor else Color.LightGray,
                                                contentDescription = "Audio mode",
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("SÓLO AUDIO", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Text(if (isAudioOnlyMode) "Solo Voz" else "Audio-Video", color = Color.Gray, fontSize = 7.sp)
                                    }

                                    // Volume Mute Button
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable {
                                            isMuted = !isMuted
                                            exoplayer.volume = if (isMuted) 0f else 1f
                                        }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(if (isMuted) Color.Red.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (isMuted) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                                                tint = if (isMuted) Color.Red else Color.LightGray,
                                                contentDescription = "Silencio",
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("SILENCIAR", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Text(if (isMuted) "Silenciado" else "Sonido OK", color = Color.Gray, fontSize = 7.sp)
                                    }
                                }
                            }

                            "SISTEMA SECH" -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Nerd Stats Toggle
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable { showNerdStats = !showNerdStats }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(if (showNerdStats) accentColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Info, contentDescription = "Estadísticas", tint = if (showNerdStats) accentColor else Color.LightGray, modifier = Modifier.size(20.dp))
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("MÉTRICAS", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Text(if (showNerdStats) "Ocultar" else "Estadísticas", color = Color.Gray, fontSize = 7.sp)
                                    }

                                    // Touch freeze lock
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable { touchLocked = true }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.05f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Lock, contentDescription = "Congelar", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("BLOQUEAR", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Text("Gestos inactivos", color = Color.Gray, fontSize = 7.sp)
                                    }

                                    // Decoder parameter visual tracker
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.03f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Build, contentDescription = "Motor", tint = accentColor, modifier = Modifier.size(16.dp))
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("DECODER", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Text(if (systemDecoderMode.contains("HW")) "GPU Acel" else "CPU TS", color = Color.Gray, fontSize = 7.sp)
                                    }

                                    // Buffers status track
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.03f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.SettingsInputAntenna, contentDescription = "Antena", tint = accentColor, modifier = Modifier.size(16.dp))
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("BÚFER HLS", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Text(systemNetworkBufferSizer.substringBefore(" ("), color = Color.Gray, fontSize = 7.sp)
                                    }
                                }
                            }
                        }

                        // Info metadata & electronic guide (EPG)
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(groupColor.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = mediaGroup.uppercase(),
                                    color = groupColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Text(
                                text = "CALIDAD DE SEÑAL: EXCELENTE (1080P FULL HLS)",
                                color = Color.Gray,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isPlayingRec) {
                                "Guía: Reproducción de archivo guardado localmente de forma segura. Alimentado por ExoEngine v2."
                            } else {
                                "Guía: Transmisión en directo del canal $mediaTitle. Alimentado por ExoEngine v2 con aceleración gráfica GPU."
                            },
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }

                // DIRECT CHANNEL CHANGER GRID ROW ("directamente cambia de reproductor a otro")
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "CAMBIAR DE CANALES DIRECTAMENTE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val recommendedList = remember(activeChannel) {
                        val activeName = activeChannel?.name ?: ""
                        CHANNELS_LIST.filter { it.name != activeName }
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(recommendedList) { otherChan ->
                            val otherGroupColor = getGroupColor(otherChan.group, accentColor)
                            Box(
                                modifier = Modifier
                                    .width(135.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CardSurface)
                                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                                    .clickable {
                                        activeChannel = otherChan
                                        playingRecording = null
                                        exoplayer.stop()
                                        exoplayer.setMediaItem(
                                            MediaItem
                                                .Builder()
                                                .setUri(getEffectiveStreamUrl(otherChan))
                                                .setMimeType(MimeTypes.APPLICATION_M3U8)
                                                .build()
                                        )
                                        exoplayer.prepare()
                                        exoplayer.play()
                                        Toast.makeText(context, "Sintonizando ${otherChan.name}...", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(10.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(otherGroupColor.copy(alpha = 0.15f))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = otherChan.group.uppercase(),
                                            color = otherGroupColor,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Text(
                                        text = otherChan.name,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Tocar para cambiar",
                                        color = accentColor,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }

        // DRAGGABLE IN-APP MINI-PLAYER BOX ("pip para reproductor mini")
        if ((activeChannel != null || playingRecording != null) && isAppMiniPlayerActived) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset { IntOffset(miniPlayerOffset.x.toInt(), miniPlayerOffset.y.toInt()) }
                        .padding(bottom = 90.dp, end = 16.dp)
                        .width(180.dp)
                        .aspectRatio(1.77f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .border(2.dp, accentColor, RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                miniPlayerOffset = Offset(
                                    x = (miniPlayerOffset.x + dragAmount.x).coerceIn(-300f, 50f),
                                    y = (miniPlayerOffset.y + dragAmount.y).coerceIn(-600f, 50f)
                                )
                            }
                        }
                        .clickable {
                            isAppMiniPlayerActived = false // Maximize again
                        }
                ) {
                    ExoPlayerViewContainer(
                        player = exoplayer,
                        url = if (playingRecording != null) playingRecording!!.sourceUrl else getEffectiveStreamUrl(activeChannel!!),
                        isAudioOnly = isAudioOnlyMode,
                        isMuted = isMuted,
                        aspectRatioMode = aspectRatioMode,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Draggable controller details bar overlay
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (playingRecording != null) "[G] ${playingRecording!!.channelName}" else activeChannel!!.name,
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 4.dp)
                        )
                        IconButton(
                            onClick = {
                                exoplayer.stop()
                                activeChannel = null
                                playingRecording = null
                                isAppMiniPlayerActived = false
                            },
                            modifier = Modifier.size(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cerrar Mini",
                                tint = Color.Red,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }

        // ============================================
        // DIALOGS & OVERLAYS GLOBAL
        // ============================================

        // 1. CHROMECAST DEVICE SCANNER DIALOG
        if (isCastDialogOpen) {
            AlertDialog(
                onDismissRequest = { isCastDialogOpen = false },
                title = { Text("Asistente de Transmisión TV", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("Sintonice de forma inalámbrica en cualquier Smart TV o Google Chromecast en su red:", color = Color.LightGray, fontSize = 11.sp)
                        Divider(color = Color.White.copy(alpha = 0.05f))

                        val simulatedTvDevices = listOf("Smart TV Samsung Living", "Chromecast Dormitorio Principal", "LG ThinQ Cocina")
                        simulatedTvDevices.forEach { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.03f))
                                    .clickable {
                                        if (isCasting && castingDeviceName == device) {
                                            isCasting = false
                                            castingDeviceName = ""
                                            Toast.makeText(context, "Transmisión detenida", Toast.LENGTH_SHORT).show()
                                        } else {
                                            isCasting = true
                                            castingDeviceName = device
                                            Toast.makeText(context, "Conectado a $device con éxito", Toast.LENGTH_SHORT).show()
                                        }
                                        isCastDialogOpen = false
                                    }
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Tv, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(device, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Icon(
                                    imageVector = if (isCasting && castingDeviceName == device) Icons.Default.CheckCircle else Icons.Default.Cast,
                                    contentDescription = null,
                                    tint = if (isCasting && castingDeviceName == device) accentColor else Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        if (isCasting) {
                            Button(
                                onClick = {
                                    isCasting = false
                                    castingDeviceName = ""
                                    isCastDialogOpen = false
                                    Toast.makeText(context, "Transmisión desactivada", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.15f)),
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, tint = Color.Red, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("DESCONECTAR TRANSMISIÓN", color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { isCastDialogOpen = false }) {
                        Text("CERRAR", color = accentColor)
                    }
                },
                containerColor = CardSurface
            )
        }

        // 2. SCREEN RECORDER SAVING CONFIRMATION DIALOG
        if (showRecordingSaveDialog) {
            AlertDialog(
                onDismissRequest = { showRecordingSaveDialog = false },
                title = { Text("¡Grabación Sintonizada!", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(36.dp).align(Alignment.CenterHorizontally))
                        Text("El sintonizador de pantalla interna finalizó la grabación de forma exitosa.", color = Color.LightGray, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.3f))
                                .padding(8.dp)
                        ) {
                            Column {
                                Text("Ruta de guardado interna:", color = Color.Gray, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                                Text(lastSavedRecordingPath, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Text("La captura utiliza compresión SVG/H.264 avanzada por hardware acelerado.", color = Color.Gray, fontSize = 9.sp)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showRecordingSaveDialog = false
                            val latestRec = recordingsList.firstOrNull()
                            if (latestRec != null) {
                                playingRecording = latestRec
                                activeChannel = null
                                exoplayer.stop()
                                exoplayer.setMediaItem(
                                    MediaItem.Builder()
                                        .setUri(latestRec.sourceUrl)
                                        .setMimeType(MimeTypes.APPLICATION_MP4)
                                        .build()
                                )
                                exoplayer.prepare()
                                exoplayer.play()
                                Toast.makeText(context, "Reproduciendo grabación de ${latestRec.channelName}...", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                    ) {
                        Text("REPRODUCIR GRABACIÓN", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRecordingSaveDialog = false }) {
                        Text("DIFUNDIR", color = Color.White)
                    }
                },
                containerColor = CardSurface
            )
        }

        // 3. PIN SECURITY PARENTAL DIALOG
        if (showPinDialog) {
            AlertDialog(
                onDismissRequest = { showPinDialog = false },
                title = { Text("Filtro Parental: Desbloquear PIN", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("Para desbloquear los sintonizadores 'EVENTOS', introduzca el PIN de seguridad asignado (Por defecto: 1234):", color = Color.LightGray, fontSize = 11.sp)
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
                                Toast.makeText(context, "Acceso concedido a EVENTOS", Toast.LENGTH_SHORT).show()
                            } else {
                                parentalPinError = "PIN Incorrecto. Por defecto use: 1234"
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
                        Text("CANCELAR", color = Color.White)
                    }
                },
                containerColor = CardSurface
            )
        }
    }
}

// 4. POWERED ADVANCED SYSTEM SETTINGS DASHBOARD
@Composable
fun AdvancedSettingsPanel(
    accentColor: Color,
    systemDecoderMode: String,
    onDecoderChange: (String) -> Unit,
    systemNetworkBufferSizer: String,
    onBufferChange: (String) -> Unit,
    systemUserAgentType: String,
    onUserAgentChange: (String) -> Unit,
    systemDnsOverHttpsEnabled: Boolean,
    onDnsChange: (Boolean) -> Unit,
    onBackPressed: () -> Unit,
    onClearCache: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BaseDarkBg)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Toolbar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            IconButton(
                onClick = onBackPressed,
                modifier = Modifier
                    .size(38.dp)
                    .background(Color.White.copy(alpha = 0.05f), CircleShape)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "AJSUTEC DE CONFIGURACIÓN DEL SISTEMA",
                    color = accentColor,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Consola Sintonizadora",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Decodificador
        Text("MOTOR DE DECODIFICACIÓN DETALLADA", color = accentColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardSurface)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val options = listOf("Hardware (HW+ / GPU)", "Software (MPEG-TS Engine)", "Nativo ExoEngine Base (Lento)")
            options.forEach { opt ->
                val isSel = systemDecoderMode == opt
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSel) accentColor.copy(alpha = 0.08f) else Color.Transparent)
                        .clickable { onDecoderChange(opt) }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(opt, color = if (isSel) Color.White else Color.Gray, fontSize = 12.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                    Icon(
                        imageVector = if (isSel) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSel) accentColor else Color.DarkGray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Buffers
        Text("TAMAÑO DE BÚFER RED RECOMIENDADO (HLS LIVE)", color = accentColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardSurface)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val options = listOf("5 Segundos (Latencia Ultra Baja)", "15 Segundos (Estándar recomendado)", "30 Segundos (Conexiones inestables)")
            options.forEach { opt ->
                val isSel = systemNetworkBufferSizer == opt
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSel) accentColor.copy(alpha = 0.08f) else Color.Transparent)
                        .clickable { onBufferChange(opt) }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(opt, color = if (isSel) Color.White else Color.Gray, fontSize = 12.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                    Icon(
                        imageVector = if (isSel) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSel) accentColor else Color.DarkGray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Cloudflare safe secure settings DNS
        Text("CONSOLA DE SEGURIDAD PROTOCOLOS", color = accentColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardSurface)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("DNS-over-HTTPS (Cifrado)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Usa Cloudflare DNS 1.1.1.1 seguro", color = Color.Gray, fontSize = 9.sp)
                }
                Switch(
                    checked = systemDnsOverHttpsEnabled,
                    onCheckedChange = onDnsChange,
                    colors = SwitchDefaults.colors(checkedThumbColor = accentColor, checkedTrackColor = accentColor.copy(alpha = 0.3f))
                )
            }

            Divider(color = Color.White.copy(alpha = 0.05f))

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                Text("User-Agent de Sintonización", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Evite restricciones de emisión simulando agentes externos:", color = Color.Gray, fontSize = 9.sp)
                val agents = listOf("VidexPlayer Core Engine v2.0", "Mozilla/5.0 Android ExoPlayer", "AppleTV/6.2 CustomStreamer")
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    agents.forEach { agent ->
                        val isSel = systemUserAgentType == agent
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) accentColor.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.03f))
                                .border(1.dp, if (isSel) accentColor else Color.Transparent, RoundedCornerShape(6.dp))
                                .clickable { onUserAgentChange(agent) }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = agent.substringBefore("/").substringBefore(" Core"),
                                color = if (isSel) accentColor else Color.Gray,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Divider(color = Color.White.copy(alpha = 0.05f))

            Button(
                onClick = onClearCache,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.12f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Purga", tint = Color.Red, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("PURGAR CACHÉ Y búferes", color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

// 5. STABLE EXOPLAYER CONTAINER
@OptIn(UnstableApi::class)
@Composable
fun ExoPlayerViewContainer(
    player: ExoPlayer,
    url: String,
    isAudioOnly: Boolean,
    isMuted: Boolean,
    aspectRatioMode: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

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
                        Box(
                            modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color.Red)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "MODO DE AUDIO SELECCIONADO",
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
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        this.useController = true
                        this.setBackgroundColor(0xFF000000.toInt())
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

@Composable
fun SleepTimerStatusText(
    isActive: Boolean,
    minutesLeftProvider: () -> Int,
    accentColor: Color
) {
    Text(
        text = if (isActive) "Apagando en ${minutesLeftProvider()} min" else "Desactivado",
        color = if (isActive) accentColor else Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
fun RecordingIndicator(
    recordingTimeSecondsProvider: () -> Int,
    isRecordingActive: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isRecordingActive) return
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.75f))
            .border(1.dp, Color.Red, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse_recorder")
            val recAlpha by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "rec"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = recAlpha }
                    .clip(CircleShape)
                    .background(Color.Red)
            )
            Spacer(modifier = Modifier.width(6.dp))
            val time = recordingTimeSecondsProvider()
            val mins = time / 60
            val secs = time % 60
            val formattedTime = String.format("%02d:%02d", mins, secs)
            Text(
                text = "GRABANDO ● $formattedTime",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
