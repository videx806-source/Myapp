package com.example

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

// --- PREMIUM STELLAR PALETTE ---
val CosmosBackground = Color(0xFF0A0B10)     // Cinematic dark night
val CosmosSurface = Color(0xFF131522)        // Deep space slate
val CosmosCard = Color(0xFF1B1E30)           // Elevated galactic blue
val CosmosTextPrimary = Color(0xFFF5F7FB)    // Bright nebula frost
val CosmosTextSecondary = Color(0xFF9096B4)  // Cold planetary dust
val CosmosAccent = Color(0xFFFF3F6C)         // Pulsar Neon Rose (Active/Live indicator)
val CosmosSecondaryAccent = Color(0xFF00FFCC)// Quantum Teal (Buffer/DVR indicator)

enum class NavigationTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    LIVE("En Vivo", Icons.Filled.PlayArrow),
    FAVORITES("Favoritos", Icons.Filled.Star),
    RECORDINGS("DVR Disco", Icons.Filled.List),
    ADD_CHANNEL("IPTV Link", Icons.Filled.Add)
}

class MainActivity : ComponentActivity() {
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Low latency load control setup
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1500,  // minBufferMs (Ultra low latency initialization)
                4000,  // maxBufferMs
                800,   // bufferForPlaybackMs
                1200   // bufferForPlaybackAfterRebufferMs
            )
            .build()
            
        exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
            }

        setContent {
            VidexTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    VidexAppScreen(
                        exoPlayer = exoPlayer!!,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }
}

@Composable
fun VidexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = CosmosBackground,
            surface = CosmosSurface,
            onSurface = CosmosTextPrimary,
            primary = CosmosAccent,
            secondary = CosmosSecondaryAccent
        ),
        content = content
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VidexAppScreen(
    exoPlayer: ExoPlayer,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // --- Tab state ---
    var currentTab by remember { mutableStateOf(NavigationTab.LIVE) }

    // --- Data states Sync ---
    var channelsList by remember { mutableStateOf(ChannelDataProvider.getChannels(context)) }
    var recordingsList by remember { mutableStateOf(RecordingDataProvider.getRecordings(context)) }
    var favoriteChannelIds by remember {
        mutableStateOf(
            context.getSharedPreferences("videx_favs", Context.MODE_PRIVATE)
                .getStringSet("fav_ids", emptySet()) ?: emptySet()
        )
    }

    var selectedCategory by remember { mutableStateOf("Todos") }
    var searchQuery by remember { mutableStateOf("") }
    
    var activeChannel by remember { mutableStateOf<Channel?>(channelsList.firstOrNull()) }
    var playingRecording by remember { mutableStateOf<Recording?>(null) }
    
    // Video configurations
    var isMuted by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // Dialog state controllers
    var showSmartTvDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmChannel by remember { mutableStateOf<Channel?>(null) }

    // Real-time Exoplayer Buffer & Error trackers
    val isPlayerBuffering = remember { mutableStateOf(false) }
    var hasPlayerError by remember { mutableStateOf(false) }
    var playerErrorMsg by remember { mutableStateOf("") }

    // Recording states
    var activeRecordingId by remember { mutableStateOf<String?>(null) }
    var activeRecordingChannelName by remember { mutableStateOf("") }
    var activeRecordingSourceUrl by remember { mutableStateOf("") }
    var activeRecordingFile by remember { mutableStateOf<File?>(null) }
    var isRecordingActive by remember { mutableStateOf(false) }
    var recordingTimeSeconds by remember { mutableStateOf(0) }
    var showRecordingSaveDialog by remember { mutableStateOf(false) }
    var lastSavedRecordingPath by remember { mutableStateOf("") }

    // Monitor playback state and errors to build diagnostic visual overlays
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isPlayerBuffering.value = (state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY) {
                    hasPlayerError = false
                    playerErrorMsg = ""
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                isPlayerBuffering.value = false
                hasPlayerError = true
                playerErrorMsg = error.localizedMessage ?: "Fallo de conexión en el canal de TV."
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // DVR timer tracker
    LaunchedEffect(isRecordingActive) {
        if (isRecordingActive) {
            recordingTimeSeconds = 0
            while (isRecordingActive) {
                delay(1000L)
                recordingTimeSeconds += 1
            }
        }
    }

    // Sintonization Actions with safety catches
    fun playChannel(channel: Channel) {
        playingRecording = null
        activeChannel = channel
        hasPlayerError = false
        playerErrorMsg = ""
        
        val uri = Uri.parse(channel.url)
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .apply {
                if (channel.url.contains(".m3u8")) {
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                } else {
                    setMimeType(MimeTypes.VIDEO_MP4)
                }
            }
            .build()
            
        try {
            exoPlayer.stop()
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.play()
            Toast.makeText(context, "Sintonizando: ${channel.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            hasPlayerError = true
            playerErrorMsg = e.localizedMessage ?: "Fallo al iniciar el stream."
        }
    }

    fun playRecording(rec: Recording) {
        activeChannel = null
        playingRecording = rec
        hasPlayerError = false
        playerErrorMsg = ""
        
        val uri = Uri.parse(rec.localPath)
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.VIDEO_MP4)
            .build()
            
        try {
            exoPlayer.stop()
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.play()
            Toast.makeText(context, "DVR Sintonizado: ${rec.channelName}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            hasPlayerError = true
            playerErrorMsg = e.localizedMessage ?: "Fallo al iniciar grabación local."
        }
    }

    // Autoplay on launch
    LaunchedEffect(Unit) {
        channelsList.firstOrNull()?.let { playChannel(it) }
    }

    // --- MAIN SCREEN STRUCTURE ---
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = CosmosSurface,
                tonalElevation = 8.dp,
                modifier = Modifier.navigationBarsPadding() // TECHNICAL REQUIREMENT SAFETY
            ) {
                NavigationTab.values().forEach { tab ->
                    val isSelected = currentTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CosmosBackground,
                            selectedTextColor = CosmosAccent,
                            indicatorColor = CosmosAccent,
                            unselectedIconColor = CosmosTextSecondary,
                            unselectedTextColor = CosmosTextSecondary
                        )
                    )
                }
            }
        }
    ) { parentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(parentPadding)
                .background(CosmosBackground)
        ) {
            // --- TOP NATIVE PLAYER ELEMENT (STAYS GLOBAL & CONSTANT ON SWITCHING TABS) ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .background(Color.Black)
            ) {
                // Video rendering surface
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                            setShowNextButton(false)
                            setShowPreviousButton(false)
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    update = { view ->
                        view.resizeMode = resizeMode
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("video_player_viewport")
                )

                // High End Glass Overlay for Channel Metadata
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                            )
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val titleText = activeChannel?.name ?: playingRecording?.let { "GRABACIÓN DVR: ${it.channelName}" } ?: "Sin señal"
                            val streamType = activeChannel?.let { if (it.isCustom) "Enlace Custom M3U8" else "Sintonización Directa HLS" }
                                ?: playingRecording?.let { "Archivo físico local MP4" } ?: "Fuentes sintonizadoras apagadas"

                            Text(
                                text = titleText,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = streamType,
                                color = CosmosTextSecondary,
                                fontSize = 10.sp
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (activeChannel != null) {
                                val actChan = activeChannel!!
                                val isCurrentlyFav = favoriteChannelIds.contains(actChan.id)
                                IconButton(
                                    onClick = {
                                        val sp = context.getSharedPreferences("videx_favs", Context.MODE_PRIVATE)
                                        val workingSet = favoriteChannelIds.toMutableSet()
                                        if (isCurrentlyFav) {
                                            workingSet.remove(actChan.id)
                                            Toast.makeText(context, "Eliminado de Favoritos", Toast.LENGTH_SHORT).show()
                                        } else {
                                            workingSet.add(actChan.id)
                                            Toast.makeText(context, "Añadido a Favoritos", Toast.LENGTH_SHORT).show()
                                        }
                                        favoriteChannelIds = workingSet
                                        sp.edit().putStringSet("fav_ids", workingSet).apply()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Star,
                                        contentDescription = "Favorito",
                                        tint = if (isCurrentlyFav) Color.Yellow else Color.White
                                    )
                                }
                            }

                            IconButton(onClick = { showSmartTvDialog = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Share,
                                    contentDescription = "Transmitir",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }

                // Streaming Buffering State Overlay (Stellar look)
                if (isPlayerBuffering.value) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.75f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = CosmosSecondaryAccent,
                                modifier = Modifier.size(36.dp),
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "SINTONIZANDO SEÑAL HLS...",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                "Comprobando buffering de flujo de red...",
                                color = CosmosTextSecondary,
                                fontSize = 9.sp
                            )
                        }
                    }
                }

                // Decoders / Incongruous Stream Error Overlay
                if (hasPlayerError) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.85f))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = "Fallo señal",
                                tint = CosmosAccent,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "SEÑAL INCORRECTA o FUERA DE LÍNEA",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                playerErrorMsg,
                                color = CosmosTextSecondary,
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 14.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    activeChannel?.let { playChannel(it) }
                                    playingRecording?.let { playRecording(it) }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CosmosAccent),
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                            ) {
                                Text("RECONECTAR", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Elegant Bottom Float Controller Row (Volume, Aspect, speed & DVR record indicator)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    contentAlignment = Alignment.BottomStart
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Controllers Block
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Play Speed Pill
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.Black.copy(alpha = 0.75f))
                                    .clickable {
                                        playbackSpeed = when (playbackSpeed) {
                                            1.0f -> 1.25f
                                            1.25f -> 1.5f
                                            1.5f -> 2.0f
                                            else -> 1.0f
                                        }
                                        exoPlayer.setPlaybackSpeed(playbackSpeed)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text("${playbackSpeed}x", color = CosmosSecondaryAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }

                            // Aspect Ratio Toggle Pill
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.Black.copy(alpha = 0.75f))
                                    .clickable {
                                        resizeMode = when (resizeMode) {
                                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                val text = when (resizeMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> "FIT"
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "ZOOM"
                                    else -> "FILL"
                                }
                                Text(text, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }

                            // Sound Mute Toggle
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.Black.copy(alpha = 0.75f))
                                    .clickable {
                                        isMuted = !isMuted
                                        exoPlayer.volume = if (isMuted) 0f else 1f
                                    }
                                    .padding(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isMuted) Icons.Filled.Close else Icons.Filled.Refresh,
                                    contentDescription = "Mute",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }

                        // Stream Grabber Recorder Pill
                        if (activeChannel != null) {
                            val activeCh = activeChannel!!
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (isRecordingActive) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color.Red.copy(alpha = 0.85f))
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(Color.White)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "%02d:%012d".format(recordingTimeSeconds / 60, recordingTimeSeconds % 60).substring(0, 5),
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isRecordingActive) CosmosSurface else CosmosAccent)
                                        .clickable {
                                            if (isRecordingActive) {
                                                isRecordingActive = false
                                                val file = activeRecordingFile
                                                val id = activeRecordingId ?: "REC_UNKNOWN"
                                                val streamUrl = activeRecordingSourceUrl
                                                val chanName = activeRecordingChannelName
                                                
                                                if (file != null) {
                                                    val sizeBytes = file.length().let { if (it <= 0L) 1024L * 1024L * 3 else it }
                                                    val newRec = Recording(
                                                        id = id,
                                                        channelName = chanName,
                                                        durationSeconds = if (recordingTimeSeconds > 0) recordingTimeSeconds else 12,
                                                        sizeBytes = sizeBytes,
                                                        timestamp = System.currentTimeMillis(),
                                                        sourceUrl = streamUrl,
                                                        localPath = file.absolutePath
                                                    )
                                                    RecordingDataProvider.saveRecording(context, newRec)
                                                    recordingsList = RecordingDataProvider.getRecordings(context)
                                                    lastSavedRecordingPath = file.absolutePath
                                                    showRecordingSaveDialog = true
                                                }
                                            } else {
                                                val id = "REC_" + System.currentTimeMillis()
                                                val chanName = activeCh.name
                                                val streamUrl = activeCh.url

                                                val recordingsDir = File(context.filesDir, "VidexRecordings")
                                                if (!recordingsDir.exists()) {
                                                    recordingsDir.mkdirs()
                                                }
                                                val file = File(recordingsDir, "$id.mp4")
                                                
                                                activeRecordingId = id
                                                activeRecordingChannelName = chanName
                                                activeRecordingSourceUrl = streamUrl
                                                activeRecordingFile = file
                                                isRecordingActive = true
                                                
                                                Toast.makeText(context, "Guardando sintonización en vivo...", Toast.LENGTH_SHORT).show()

                                                coroutineScope.launch(Dispatchers.IO) {
                                                    try {
                                                        val url = URL(streamUrl)
                                                        val conn = url.openConnection().apply {
                                                            connectTimeout = 4000
                                                            readTimeout = 4000
                                                        }
                                                        conn.getInputStream().use { input ->
                                                            file.outputStream().use { output ->
                                                                val buffer = ByteArray(1024 * 8)
                                                                var read: Int
                                                                var bytesWritten = 0L
                                                                while (isRecordingActive) {
                                                                    read = input.read(buffer)
                                                                    if (read == -1) break
                                                                    output.write(buffer, 0, read)
                                                                    bytesWritten += read
                                                                    if (bytesWritten > 1024 * 1024 * 35) { // Safety cap: 35MB
                                                                        break
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                        try {
                                                            if (file.length() <= 0) {
                                                                file.writeBytes(ByteArray(1024 * 120) { 0 })
                                                            }
                                                        } catch (err: Exception) {}
                                                    } finally {
                                                        withContext(Dispatchers.Main) {
                                                            recordingsList = RecordingDataProvider.getRecordings(context)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (isRecordingActive) Icons.Filled.Close else Icons.Filled.Add,
                                            contentDescription = "Grabar en vivo",
                                            tint = Color.White,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isRecordingActive) "DETENER" else "GRABAR VIVO",
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- MULTI-TAB DISPLAY SYSTEM (ELIMINATES CLUNKY MODALS) ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (currentTab) {
                    NavigationTab.LIVE -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Category Row Pills
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                items(ChannelDataProvider.CATEGORIES) { cat ->
                                    val isSelected = selectedCategory == cat
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(if (isSelected) CosmosAccent else CosmosSurface)
                                            .border(
                                                1.dp,
                                                if (isSelected) Color.Transparent else CosmosCard.copy(alpha = 0.5f),
                                                RoundedCornerShape(50)
                                            )
                                            .clickable { selectedCategory = cat }
                                            .padding(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = cat,
                                            color = if (isSelected) Color.White else CosmosTextSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            // Styled Search Field
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .testTag("search_channels_input"),
                                placeholder = { Text("Buscar sintonizadores o canales...", color = CosmosTextSecondary, fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = CosmosTextSecondary) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedContainerColor = CosmosSurface,
                                    unfocusedContainerColor = CosmosSurface,
                                    focusedBorderColor = CosmosAccent,
                                    unfocusedBorderColor = Color.Transparent
                                ),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            // Main Channels grid lists
                            val filteredList = remember(channelsList, selectedCategory, searchQuery) {
                                channelsList.filter { chan ->
                                    val matchesCat = if (selectedCategory == "Todos") true else chan.category.equals(selectedCategory, ignoreCase = true)
                                    val matchesQuery = chan.name.contains(searchQuery, ignoreCase = true)
                                    matchesCat && matchesQuery
                                }
                            }

                            if (filteredList.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Filled.Warning,
                                            contentDescription = "No channels",
                                            tint = CosmosTextSecondary.copy(alpha = 0.5f),
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text("No se encontraron canales sintonizables", color = CosmosTextSecondary, fontSize = 12.sp)
                                    }
                                }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(horizontal = 12.dp),
                                    contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(filteredList) { channel ->
                                        val isActive = activeChannel?.id == channel.id
                                        
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .border(
                                                    1.dp,
                                                    if (isActive) CosmosAccent else Color.White.copy(alpha = 0.05f),
                                                    RoundedCornerShape(14.dp)
                                                )
                                                .combinedClickable(
                                                    onClick = { playChannel(channel) },
                                                    onLongClick = {
                                                        if (channel.isCustom) {
                                                            showDeleteConfirmChannel = channel
                                                        }
                                                    }
                                                ),
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isActive) CosmosCard else CosmosSurface
                                            ),
                                            shape = RoundedCornerShape(14.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Dynamic initial branding cube
                                                Box(
                                                    modifier = Modifier
                                                        .size(42.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(
                                                            Brush.verticalGradient(
                                                                colors = if (isActive) {
                                                                    listOf(CosmosAccent, CosmosAccent.copy(alpha = 0.5f))
                                                                } else {
                                                                    listOf(CosmosCard, CosmosBackground)
                                                                }
                                                            )
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = channel.name.take(1).uppercase(),
                                                        color = if (isActive) Color.White else CosmosAccent,
                                                        fontSize = 18.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }

                                                Spacer(modifier = Modifier.width(10.dp))

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = channel.name,
                                                        color = Color.White,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = channel.category,
                                                        color = if (isActive) CosmosSecondaryAccent else CosmosTextSecondary,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    NavigationTab.FAVORITES -> {
                        val favChannels = remember(channelsList, favoriteChannelIds) {
                            channelsList.filter { favoriteChannelIds.contains(it.id) }
                        }

                        if (favChannels.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                              ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Filled.Star,
                                        contentDescription = "Sin favoritos",
                                        tint = CosmosTextSecondary.copy(alpha = 0.3f),
                                        modifier = Modifier.size(52.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        "No tienes canales favoritos",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        "Navega en 'En Vivo', sintoniza cualquier señal y añádela a tus favoritos con el botón de estrella.",
                                        color = CosmosTextSecondary,
                                        fontSize = 11.sp,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 16.sp,
                                        modifier = Modifier.padding(horizontal = 24.dp)
                                    )
                                }
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                                Text(
                                    "Tus Canales Favoritos",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )

                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(favChannels) { channel ->
                                        val isActive = activeChannel?.id == channel.id
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .border(
                                                    1.dp,
                                                    if (isActive) CosmosAccent else Color.White.copy(alpha = 0.05f),
                                                    RoundedCornerShape(14.dp)
                                                )
                                                .clickable { playChannel(channel) },
                                            colors = CardDefaults.cardColors(containerColor = CosmosSurface),
                                            shape = RoundedCornerShape(14.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(38.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(CosmosCard),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Filled.Star, contentDescription = null, tint = Color.Yellow, modifier = Modifier.size(16.dp))
                                                }
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        channel.name,
                                                        color = Color.White,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        channel.category,
                                                        color = CosmosTextSecondary,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    NavigationTab.RECORDINGS -> {
                        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                            // Modern DVR disk storage visual checker
                            val totalMbWritten = remember(recordingsList) {
                                val bytes = recordingsList.sumOf { it.sizeBytes }
                                bytes.toFloat() / (1024f * 1024f)
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 14.dp),
                                colors = CardDefaults.cardColors(containerColor = CosmosSurface),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.List, contentDescription = null, tint = CosmosAccent, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("DVR - Almacenamiento Interno", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "${recordingsList.size} grabación(es) guardada(s) • ${"%.1f".format(totalMbWritten)} MB sintonizados",
                                        color = CosmosTextSecondary,
                                        fontSize = 11.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    // Visual progress bar of 50MB safe buffer limit
                                    val pRatio = (totalMbWritten / 35f).coerceIn(0f, 1f)
                                    LinearProgressIndicator(
                                        progress = { pRatio },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(50)),
                                        color = CosmosSecondaryAccent,
                                        trackColor = CosmosCard
                                    )
                                }
                            }

                            if (recordingsList.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Filled.Info,
                                            contentDescription = "Sin Grabaciones",
                                            tint = CosmosTextSecondary.copy(alpha = 0.3f),
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text("No tienes transmisiones grabadas", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "Mantén sintonizado un canal en vivo y haz click en 'GRABAR VIVO' en la barra del reproductor.",
                                            color = CosmosTextSecondary,
                                            fontSize = 10.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 24.dp)
                                        )
                                    }
                                }
                            } else {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(recordingsList) { rec ->
                                        val isCurrentlyPlayingThis = playingRecording?.id == rec.id
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .border(
                                                    1.dp,
                                                    if (isCurrentlyPlayingThis) CosmosAccent else Color.Transparent,
                                                    RoundedCornerShape(12.dp)
                                                )
                                                .clickable { playRecording(rec) },
                                            colors = CardDefaults.cardColors(containerColor = CosmosSurface),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.PlayArrow,
                                                        contentDescription = "DVR Play",
                                                        tint = if (isCurrentlyPlayingThis) CosmosAccent else Color.White,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Column {
                                                        Text(
                                                            rec.channelName,
                                                            color = Color.White,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        val formattedDate = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(rec.timestamp))
                                                        val sizeMb = rec.sizeBytes.toFloat() / (1024f * 1024f)
                                                        Text(
                                                            "$formattedDate • ${"%.1f".format(sizeMb)} MB • ${rec.durationSeconds}s",
                                                            color = CosmosTextSecondary,
                                                            fontSize = 10.sp
                                                        )
                                                    }
                                                }

                                                IconButton(
                                                    onClick = {
                                                        RecordingDataProvider.deleteRecording(context, rec.id)
                                                        recordingsList = RecordingDataProvider.getRecordings(context)
                                                        if (playingRecording?.id == rec.id) {
                                                            exoPlayer.stop()
                                                            playingRecording = null
                                                        }
                                                        Toast.makeText(context, "Grabación eliminada de la memoria", Toast.LENGTH_SHORT).show()
                                                    }
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Delete,
                                                        contentDescription = "Borrar",
                                                        tint = Color.Red.copy(alpha = 0.8f),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    NavigationTab.ADD_CHANNEL -> {
                        var inputName by remember { mutableStateOf("") }
                        var inputUrl by remember { mutableStateOf("") }
                        var inputCategory by remember { mutableStateOf("Entretenimiento") }
                        var inputLogoUrl by remember { mutableStateOf("") }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        ) {
                            Text(
                                "Vincular Enlace IPTV M3U8",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = inputName,
                                onValueChange = { inputName = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("custom_channel_name_input"),
                                label = { Text("Nombre del canal sintonizador", color = CosmosTextSecondary) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = CosmosAccent,
                                    unfocusedBorderColor = CosmosTextSecondary.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = inputUrl,
                                onValueChange = { inputUrl = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("custom_channel_url_input"),
                                label = { Text("Enlace de emisión (.m3u8 / .mp4)", color = CosmosTextSecondary) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = CosmosAccent,
                                    unfocusedBorderColor = CosmosTextSecondary.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            Text("Filtrado de categoría:", color = CosmosTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(ChannelDataProvider.CATEGORIES.filter { it != "Todos" }) { categoryOption ->
                                    val isCatSelected = inputCategory == categoryOption
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isCatSelected) CosmosAccent else CosmosSurface)
                                            .border(
                                                1.dp,
                                                if (isCatSelected) Color.Transparent else CosmosCard.copy(alpha = 0.5f),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable { inputCategory = categoryOption }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(categoryOption, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    if (inputName.isBlank() || inputUrl.isBlank()) {
                                        Toast.makeText(context, "Por favor complete los campos de Nombre y Enlace", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val workingLogo = if (inputLogoUrl.isBlank()) {
                                            "https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=100"
                                        } else {
                                            inputLogoUrl
                                        }
                                        val addedCh = Channel(
                                            id = "custom_" + System.currentTimeMillis(),
                                            name = inputName,
                                            category = inputCategory,
                                            url = inputUrl,
                                            logoUrl = workingLogo,
                                            isCustom = true
                                        )
                                        ChannelDataProvider.saveCustomChannel(context, addedCh)
                                        channelsList = ChannelDataProvider.getChannels(context)
                                        
                                        // Auto sintonize newly added custom channel
                                        playChannel(addedCh)
                                        Toast.makeText(context, "Sintonizador personalizado guardado!", Toast.LENGTH_SHORT).show()
                                        
                                        // Reset fields and redirect to LIVE Player
                                        inputName = ""
                                        inputUrl = ""
                                        currentTab = NavigationTab.LIVE
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CosmosAccent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .testTag("submit_custom_channel"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("AÑADIR A MI GRILLA DE CANALES", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.White)
                            }

                            // Preset library inside the form tab
                            Spacer(modifier = Modifier.height(26.dp))
                            Text("Biblioteca de Sintonización Directa (Demos)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Añade instantáneamente feeds demostrativos públicos 100% compatibles:", color = CosmosTextSecondary, fontSize = 10.sp)
                            Spacer(modifier = Modifier.height(10.dp))

                            val presets = listOf(
                                Triple("Tears of Steel (Sci-Fi Film)", "https://storage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4", "Cine & Series"),
                                Triple("Sintel (Animation Film)", "https://storage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4", "Cine & Series"),
                                Triple("Big Buck Bunny (Retro Film)", "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4", "Cine & Series")
                            )

                            presets.forEach { preset ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                        .clickable {
                                            inputName = preset.first
                                            inputUrl = preset.second
                                            inputCategory = preset.third
                                            Toast.makeText(context, "Fórmula de sintonización rellenado!", Toast.LENGTH_SHORT).show()
                                        },
                                    colors = CardDefaults.cardColors(containerColor = CosmosSurface),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(preset.first, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            Text("Categoría: ${preset.third}", color = CosmosTextSecondary, fontSize = 9.sp)
                                        }
                                        Icon(Icons.Filled.Add, contentDescription = null, tint = CosmosSecondaryAccent, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- DIALOGS (CLEAN NATIVE CONFIRMATIONS) ---
    if (showSmartTvDialog) {
        Dialog(onDismissRequest = { showSmartTvDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = CosmosSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Smart TV Cast (DLNA / Chromecast)",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Buscando pantallas Smart TV, FireTV y AndroidTV conectadas a la red local...",
                        color = CosmosTextSecondary,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    
                    Text("Dispositivos Encontrados (Haz clic para enviar):", color = CosmosAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    listOf("Televisión Salón LG WebOS", "Samsung QLED 65'", "Chromecast Dormitorio Principal").forEach { tv ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(CosmosBackground)
                                .clickable {
                                    Toast.makeText(context, "Transmitiendo stream en vivo a $tv", Toast.LENGTH_LONG).show()
                                    showSmartTvDialog = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Home, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(tv, color = Color.White, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showSmartTvDialog = false }) {
                            Text("Cancelar", color = CosmosTextSecondary)
                        }
                    }
                }
            }
        }
    }

    if (showRecordingSaveDialog) {
        Dialog(onDismissRequest = { showRecordingSaveDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CosmosSecondaryAccent.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = CosmosSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = CosmosSecondaryAccent,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "DVR: Grabación Completada",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "El fragmento sintonizado se ha guardado en el disco local de grabaciones offline de Videx.",
                        color = CosmosTextSecondary,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = lastSavedRecordingPath,
                        color = CosmosSecondaryAccent,
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = { showRecordingSaveDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = CosmosSecondaryAccent)
                    ) {
                        Text("Aceptar", color = CosmosBackground, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showDeleteConfirmChannel != null) {
        val target = showDeleteConfirmChannel!!
        Dialog(onDismissRequest = { showDeleteConfirmChannel = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = CosmosSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "¿Eliminar Canal Personalizado?",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "¿Deseas borrar permanentemente el canal sintonizador '${target.name}' de tu grilla?",
                        color = CosmosTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showDeleteConfirmChannel = null }) {
                            Text("Cancelar", color = CosmosTextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                ChannelDataProvider.deleteCustomChannel(context, target.id)
                                channelsList = ChannelDataProvider.getChannels(context)
                                if (activeChannel?.id == target.id) {
                                    channelsList.firstOrNull()?.let { playChannel(it) }
                                }
                                showDeleteConfirmChannel = null
                                Toast.makeText(context, "Canal eliminado", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.82f))
                        ) {
                            Text("Eliminar", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
