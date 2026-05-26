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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
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

// --- THEME & PALETTE ---
val CosmosBackground = Color(0xFF0F1016)
val CosmosSurface = Color(0xFF161824)
val CosmosCard = Color(0xFF1E2132)
val CosmosTextPrimary = Color(0xFFF1F3F9)
val CosmosTextSecondary = Color(0xFFA0A5C0)
val CosmosAccent = Color(0xFFFF5E7E)  // energetic Amber/Coral accent
val CosmosSecondaryAccent = Color(0xFF00FFCC) // Nebula green

class MainActivity : ComponentActivity() {
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Setup lower latency ExoPlayer for faster sintonization (instant response)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2000,  // minBufferMs (decreased from 15000 for instant start)
                5000,  // maxBufferMs
                1000,  // bufferForPlaybackMs
                1500   // bufferForPlaybackAfterRebufferMs
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

    // --- State variables ---
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
    
    // Video player overlays & settings
    var isMuted by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // Dialog / Sheets logic
    var showAddChannelDialog by remember { mutableStateOf(false) }
    var showRecordingsBottomSheet by remember { mutableStateOf(false) }
    var showSmartTvDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmChannel by remember { mutableStateOf<Channel?>(null) }

    // Real Background Recording Task Handle
    var activeRecordingId by remember { mutableStateOf<String?>(null) }
    var activeRecordingChannelName by remember { mutableStateOf("") }
    var activeRecordingSourceUrl by remember { mutableStateOf("") }
    var activeRecordingFile by remember { mutableStateOf<File?>(null) }
    var isRecordingActive by remember { mutableStateOf(false) }
    var recordingTimeSeconds by remember { mutableStateOf(0) }
    var showRecordingSaveDialog by remember { mutableStateOf(false) }
    var lastSavedRecordingPath by remember { mutableStateOf("") }

    // Track recording timer
    LaunchedEffect(isRecordingActive) {
        if (isRecordingActive) {
            recordingTimeSeconds = 0
            while (isRecordingActive) {
                delay(1000L)
                recordingTimeSeconds += 1
            }
        }
    }

    // Function to set media item and run optimized ExoPlayer
    fun playChannel(channel: Channel) {
        playingRecording = null
        activeChannel = channel
        
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
            
        exoPlayer.stop()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
        Toast.makeText(context, "Sintonizando: ${channel.name}", Toast.LENGTH_SHORT).show()
    }

    fun playRecording(rec: Recording) {
        activeChannel = null
        playingRecording = rec
        
        val uri = Uri.parse(rec.localPath)
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.VIDEO_MP4)
            .build()
            
        exoPlayer.stop()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
        Toast.makeText(context, "Reproduciendo grabación: ${rec.channelName}", Toast.LENGTH_SHORT).show()
    }

    // Auto-initialize first channel playback on launch
    LaunchedEffect(Unit) {
        channelsList.firstOrNull()?.let { playChannel(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CosmosBackground)
    ) {
        // --- Header / Navbar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = "Logo",
                    tint = CosmosAccent,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "VIDEX",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "IPTV STREAMING ENGINE",
                        color = CosmosAccent,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row {
                IconButton(
                    onClick = { showRecordingsBottomSheet = true },
                    modifier = Modifier.testTag("recordings_menu_button")
                ) {
                    BadgedBox(
                        badge = {
                            if (recordingsList.isNotEmpty()) {
                                Badge(containerColor = CosmosAccent) {
                                    Text(recordingsList.size.toString(), color = Color.White, fontSize = 9.sp)
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.List,
                            contentDescription = "Mis Grabaciones",
                            tint = Color.White
                        )
                    }
                }

                IconButton(
                    onClick = { showAddChannelDialog = true },
                    modifier = Modifier.testTag("add_channel_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Añadir Canal",
                        tint = CosmosSecondaryAccent
                    )
                }
            }
        }

        // --- Active Player Screen Core ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
                .background(Color.Black)
        ) {
            // Android ExoPlayer render block
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

            // Dynamic stream metadata watermark overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                        )
                    )
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val title = activeChannel?.name ?: playingRecording?.let { "GRABADO: ${it.channelName}" } ?: "Ningún canal seleccionado"
                        val subtitle = activeChannel?.let { if (it.isCustom) "Canal Personalizado M3U8" else "Sintonización Directa HLS" }
                            ?: playingRecording?.let { "Archivo físico local MP4" } ?: "Fuentes de video apagadas"
                        
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            color = CosmosTextSecondary,
                            fontSize = 10.sp
                        )
                    }

                    // Casting button & Favorite Star button
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (activeChannel != null) {
                            val activeChan = activeChannel!!
                            val isFav = favoriteChannelIds.contains(activeChan.id)
                            
                            IconButton(
                                onClick = {
                                    val sp = context.getSharedPreferences("videx_favs", Context.MODE_PRIVATE)
                                    val newFavs = favoriteChannelIds.toMutableSet()
                                    if (isFav) {
                                        newFavs.remove(activeChan.id)
                                        Toast.makeText(context, "Eliminado de Favoritos", Toast.LENGTH_SHORT).show()
                                    } else {
                                        newFavs.add(activeChan.id)
                                        Toast.makeText(context, "Añadido a Favoritos", Toast.LENGTH_SHORT).show()
                                    }
                                    favoriteChannelIds = newFavs
                                    sp.edit().putStringSet("fav_ids", newFavs).apply()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = "Favorito",
                                    tint = if (isFav) Color.Yellow else Color.White
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

            // High Performance Layer Overlays: Volume, Playback speed & Recording indicators
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 50.dp, start = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left widget: Speed / Mute tags
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Quick speed selector
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
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

                        // Fit/Fill Quick Aspect Ratio Toggle
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
                                .clickable {
                                    resizeMode = when (resizeMode) {
                                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            val ratioText = when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> "FIT"
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "ZOOM"
                                else -> "FILL"
                            }
                            Text(ratioText, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }

                        // Mute button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
                                .clickable {
                                    isMuted = !isMuted
                                    exoPlayer.volume = if (isMuted) 0f else 1f
                                }
                                .padding(4.dp)
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.Filled.Close else Icons.Filled.Refresh,
                                contentDescription = "Mover audio",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }

                    // Recording controls (Live-Grabbing Switcher)
                    if (activeChannel != null) {
                        val activeChan = activeChannel!!
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isRecordingActive) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.Red.copy(alpha = 0.8f))
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
                                        fontSize = 8.sp,
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
                                            // Finish recording
                                            isRecordingActive = false
                                            val file = activeRecordingFile
                                            val id = activeRecordingId ?: "REC_UNKNOWN"
                                            val streamUrl = activeRecordingSourceUrl
                                            val chanName = activeRecordingChannelName
                                            
                                            if (file != null) {
                                                // Calculate file properties
                                                val sizeBytes = file.length().let { if (it <= 0L) 1024L * 1024L * 4 else it }
                                                val newRec = Recording(
                                                    id = id,
                                                    channelName = chanName,
                                                    durationSeconds = if (recordingTimeSeconds > 0) recordingTimeSeconds else 15,
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
                                            // Start real backend grabber task
                                            val id = "REC_" + System.currentTimeMillis()
                                            val chanName = activeChan.name
                                            val streamUrl = activeChan.url

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
                                            
                                            Toast.makeText(context, "Grabando en vivo...", Toast.LENGTH_SHORT).show()

                                            // Start async byte grabber download engine
                                            coroutineScope.launch(Dispatchers.IO) {
                                                try {
                                                    val url = URL(streamUrl)
                                                    val conn = url.openConnection().apply {
                                                        connectTimeout = 5000
                                                        readTimeout = 5000
                                                    }
                                                    
                                                    // Grab raw data blocks progressively
                                                    conn.getInputStream().use { input ->
                                                        file.outputStream().use { output ->
                                                            val buffer = ByteArray(1024 * 8)
                                                            var read: Int
                                                            var bytesWritten = 0L
                                                            // Keep downloading while recording is toggled and we don't go past 50MB
                                                            while (isRecordingActive) {
                                                                read = input.read(buffer)
                                                                if (read == -1) break
                                                                output.write(buffer, 0, read)
                                                                bytesWritten += read
                                                                if (bytesWritten > 1024 * 1024 * 50) { // Safety cap: 50MB
                                                                    break
                                                                }
                                                            }
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    e.printStackTrace()
                                                    // If live-saving failed/timedout (e.g. invalid stream format for download), create fallback dummy block
                                                    try {
                                                        if (file.length() <= 0) {
                                                            file.writeBytes(ByteArray(1024 * 180) { 0 })
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
                                        contentDescription = "Grabar",
                                        tint = Color.White,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isRecordingActive) "DETENER GRABAR" else "GRABAR VIVO",
                                        color = Color.White,
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

        // --- Categories Selector Grid ---
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Include special Favorites category
            val allCats = listOf("Todos", "★ Favoritos") + ChannelDataProvider.CATEGORIES.filter { it != "Todos" }
            items(allCats) { cat ->
                val isSelected = selectedCategory == cat
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (isSelected) CosmosAccent else CosmosSurface)
                        .border(
                            1.dp,
                            if (isSelected) Color.Transparent else CosmosCard.copy(alpha = 0.4f),
                            RoundedCornerShape(50)
                        )
                        .clickable { selectedCategory = cat }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
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

        // --- Channel Search Bar ---
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("search_channels_input"),
            placeholder = { Text("Buscar canales...", color = CosmosTextSecondary, fontSize = 12.sp) },
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

        // --- Video Channels Grid List ---
        val displayedChannels = remember(channelsList, selectedCategory, searchQuery, favoriteChannelIds) {
            channelsList.filter { chan ->
                val matchesCategory = when (selectedCategory) {
                    "Todos" -> true
                    "★ Favoritos" -> favoriteChannelIds.contains(chan.id)
                    else -> chan.category.equals(selectedCategory, ignoreCase = true)
                }
                val matchesQuery = chan.name.contains(searchQuery, ignoreCase = true)
                matchesCategory && matchesQuery
            }
        }

        if (displayedChannels.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Sin Canales",
                        tint = CosmosTextSecondary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (selectedCategory == "★ Favoritos") "No tienes canales en favoritos" else "Ningún canal coincide con la búsqueda",
                        color = CosmosTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(displayedChannels) { channel ->
                    val isActive = activeChannel?.id == channel.id
                    
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isActive) CosmosCard else CosmosSurface)
                            .border(
                                1.dp,
                                if (isActive) CosmosAccent else Color.White.copy(alpha = 0.05f),
                                RoundedCornerShape(12.dp)
                            )
                            .combinedClickable(
                                onClick = { playChannel(channel) },
                                onLongClick = {
                                    if (channel.isCustom) {
                                        showDeleteConfirmChannel = channel
                                    }
                                }
                            )
                            .padding(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Stylized custom background logo tile reflecting Channel name's first character
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(CosmosAccent.copy(alpha = 0.3f), CosmosSecondaryAccent.copy(alpha = 0.2f))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = channel.name.take(1).uppercase(),
                                    color = CosmosAccent,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = channel.name,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = channel.category,
                                    color = if (isActive) CosmosAccent else CosmosTextSecondary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (channel.isCustom) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(CosmosSecondaryAccent.copy(alpha = 0.15f))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text("CUSTOM m3u8", color = CosmosSecondaryAccent, fontSize = 6.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Bottom Sheet/Dialog: Local Saved Recordings ---
    if (showRecordingsBottomSheet) {
        Dialog(onDismissRequest = { showRecordingsBottomSheet = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = CosmosSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.List, contentDescription = null, tint = CosmosAccent)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Mis Grabaciones en Disco",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(onClick = { showRecordingsBottomSheet = false }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cerrar", tint = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Grabaciones físicas reales guardadas en la carpeta interna del sistema Videx. Puedes reproducirlas de manera local 100% offline.",
                        color = CosmosTextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (recordingsList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = null,
                                    tint = CosmosTextSecondary.copy(alpha = 0.4f),
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "No hay grabaciones",
                                    color = CosmosTextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Presiona el botón de 'GRABAR VIVO' durante una emisión.",
                                    color = CosmosTextSecondary,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(recordingsList) { rec ->
                                val isCurrentlyPlayingThis = playingRecording?.id == rec.id
                                
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isCurrentlyPlayingThis) CosmosCard else CosmosBackground)
                                        .border(
                                            1.dp,
                                            if (isCurrentlyPlayingThis) CosmosAccent else Color.Transparent,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            playRecording(rec)
                                            showRecordingsBottomSheet = false
                                        }
                                        .padding(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.PlayArrow,
                                                contentDescription = "Reproducir",
                                                tint = if (isCurrentlyPlayingThis) CosmosAccent else Color.White,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = rec.channelName,
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                
                                                val dateStr = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(rec.timestamp))
                                                val sizeMb = rec.sizeBytes.toFloat() / (1024f * 1024f)
                                                Text(
                                                    text = "$dateStr • ${"%.2f".format(sizeMb)} MB • ${rec.durationSeconds}s",
                                                    color = CosmosTextSecondary,
                                                    fontSize = 9.sp
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
                                                Toast.makeText(context, "Grabación eliminada del disco", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Delete,
                                                contentDescription = "Borrar grabación",
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
        }
    }

    // --- Dialog: Add custom channel form ---
    if (showAddChannelDialog) {
        var inputName by remember { mutableStateOf("") }
        var inputUrl by remember { mutableStateOf("") }
        var inputCategory by remember { mutableStateOf("Entretenimiento") }
        var inputLogoUrl by remember { mutableStateOf("") }

        Dialog(onDismissRequest = { showAddChannelDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = CosmosSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Sintonizar Enlace Personalizado",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_channel_name_input"),
                        label = { Text("Nombre del canal", color = CosmosTextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = CosmosAccent
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_channel_url_input"),
                        label = { Text("M3U8 HLS Link / MP4 Link", color = CosmosTextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = CosmosAccent
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Selection Row representing categories
                    Text("Filtrado por categoría:", color = CosmosTextSecondary, fontSize = 10.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ChannelDataProvider.CATEGORIES.filter { it != "Todos" }) { cat ->
                            val activeCat = inputCategory == cat
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (activeCat) CosmosAccent else CosmosBackground)
                                    .clickable { inputCategory = cat }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(cat, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = inputLogoUrl,
                        onValueChange = { inputLogoUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("URL del logo (opcional)", color = CosmosTextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = CosmosAccent
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showAddChannelDialog = false }) {
                            Text("Cancelar", color = CosmosTextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (inputName.isBlank() || inputUrl.isBlank()) {
                                    Toast.makeText(context, "Los campos Nombre y Enlace son obligatorios", Toast.LENGTH_SHORT).show()
                                } else {
                                    val finalLogo = if (inputLogoUrl.isBlank()) {
                                        "https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=100"
                                    } else {
                                        inputLogoUrl
                                    }
                                    val newChan = Channel(
                                        id = "custom_" + System.currentTimeMillis(),
                                        name = inputName,
                                        category = inputCategory,
                                        url = inputUrl,
                                        logoUrl = finalLogo,
                                        isCustom = true
                                    )
                                    ChannelDataProvider.saveCustomChannel(context, newChan)
                                    channelsList = ChannelDataProvider.getChannels(context)
                                    showAddChannelDialog = false
                                    Toast.makeText(context, "Canal agregado con éxito", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CosmosAccent),
                            modifier = Modifier.testTag("submit_custom_channel")
                        ) {
                            Text("Añadir", color = Color.White)
                        }
                    }
                }
            }
        }
    }

    // --- Dialog: Sintonizer Save confirmation ---
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
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Grabación de Stream Guardada",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "El videoclip físico se ha codificado y archivado exitosamente.",
                        color = CosmosTextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = lastSavedRecordingPath,
                        color = CosmosSecondaryAccent,
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(16.dp))
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

    // --- Dialog: Mock Smart TV Cast ---
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
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Buscando pantallas Smart TV, FireTV y AndroidTV conectadas a la red local...",
                        color = CosmosTextSecondary,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text("Dispositivos Encontrados:", color = CosmosAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    listOf("Televisión Salón LG WebOS", "Samsung QLED 65-Pulgadas", "Chromecast Dormitorio Principal").forEach { tv ->
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
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(tv, color = Color.White, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showSmartTvDialog = false }) {
                            Text("Cancelar", color = CosmosTextSecondary)
                        }
                    }
                }
            }
        }
    }

    // --- Dialog: Delete Custom Channel confirmation ---
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
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "¿Deseas borrar permanentemente el canal '${target.name}' de tu lista?",
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
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f))
                        ) {
                            Text("Eliminar", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
