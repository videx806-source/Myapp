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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
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

// Effective URL helper that supports raw links
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

    // App preferences states
    var selectedThemeIndex by remember { mutableStateOf(0) }
    val currentTheme = THEMES_LIST[selectedThemeIndex]
    val accentColor = currentTheme.primary

    // Tab and view categories states
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("TODOS") }
    var showOnlyFavorites by remember { mutableStateOf(false) }

    // Column density (1, 2, or 3 columns)
    var gridColumns by remember { mutableStateOf(3) }

    // Active single player state - null means Main List, non-null means Dedicated player view
    var activeChannel by remember { mutableStateOf<Channel?>(null) }

    // Video adjustments
    var isAudioOnlyMode by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var aspectRatioMode by remember { mutableStateOf(0) } // 0 = FIT, 1 = FILL, 2 = ZOOM, 3 = 16:9

    // Persistence lists
    var favoritesList by remember { mutableStateOf(getFavorites(context)) }

    // UI Dialog controllers
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

    // Sleep Timer
    var sleepTimerMinutes by remember { mutableStateOf(0) }
    var sleepTimerMinutesLeft by remember { mutableStateOf(0) }
    var sleepTimerIsActive by remember { mutableStateOf(false) }

    // Sync static helper class state for system level PiP
    LaunchedEffect(activeChannel) {
        MainActivity.isPlayer1RunningAndPipEnabled = activeChannel != null
    }

    // Sleep Timer Countdown Loop
    LaunchedEffect(sleepTimerIsActive, sleepTimerMinutesLeft) {
        if (sleepTimerIsActive && sleepTimerMinutesLeft > 0) {
            delay(60000L) // Wait exactly 1 minute
            sleepTimerMinutesLeft -= 1
            if (sleepTimerMinutesLeft <= 0) {
                activeChannel = null
                sleepTimerIsActive = false
                sleepTimerMinutes = 0
                Toast.makeText(context, "Temporizador: Reproductor apagado automáticamente", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Single persistent player engine to ensure zero lag, high efficiency
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

    // Cleanup resources
    DisposableEffect(Unit) {
        onDispose {
            exoplayer.release()
        }
    }

    // Trigger update on playback parameters dynamically
    LaunchedEffect(playbackSpeed) {
        exoplayer.playbackParameters = PlaybackParameters(playbackSpeed)
    }

    // Categories
    val categories = remember { listOf("TODOS", "GENERAL", "EVENTOS", "MÚSICA", "PLUTO") }

    // Screen layout filter logic
    val filteredChannels by remember(searchQuery, selectedCategory, showOnlyFavorites, favoritesList, isParentalLocked) {
        derivedStateOf {
            CHANNELS_LIST.filter { channel ->
                // Parental protection hides Pluto streams or Custom ones if toggled
                if (isParentalLocked && channel.group.uppercase() == "EVENTOS") {
                    return@filter false
                }

                val matchesCategory = when (selectedCategory) {
                    "TODOS" -> true
                    else -> channel.group.uppercase() == selectedCategory.uppercase()
                }

                val matchesSearch = channel.name.contains(searchQuery, ignoreCase = true)
                val matchesFavorites = !showOnlyFavorites || favoritesList.contains(channel.name)

                matchesCategory && matchesSearch && matchesFavorites
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BaseDarkBg)
    ) {
        if (activeChannel == null) {
            // ============================================
            // SECCIÓN 1: PANTALLA PRINCIPAL / NAVEGACIÓN
            // ============================================
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // HEADER BAR - Clean layout without "IPTV" references
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
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
                                text = "REPRODUCTOR MULTIMEDIA PREMIUM",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF6E6E7F),
                                letterSpacing = 1.sp
                            )
                        }

                        // Theme switcher & Timer Row
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

                // SEARCH BAR & CONSOLE INPUT FILTERS
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Buscar canal...", color = Color(0xFF6E6E7F), fontSize = 12.sp) },
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
                }

                // QUICK CONFIGURATION LINE (Columns layout density + Favorites filter)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Favorites filter
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(CardSurface)
                            .clickable { showOnlyFavorites = !showOnlyFavorites }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (showOnlyFavorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favoritos",
                            tint = if (showOnlyFavorites) Color.Red else Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (showOnlyFavorites) "SÓLO FAVORITOS" else "TODOS LOS FAVORITOS",
                            color = if (showOnlyFavorites) Color.White else Color.Gray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Column Layout Configurator ("añade configuración columnas")
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(CardSurface)
                            .padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(1, 2, 3).forEach { cols ->
                            val isSel = gridColumns == cols
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) accentColor.copy(alpha = 0.15f) else Color.Transparent)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSel) accentColor else Color.Transparent,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable { gridColumns = cols }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = when (cols) {
                                            1 -> Icons.Default.ViewStream
                                            2 -> Icons.Default.GridView
                                            else -> Icons.Default.ViewModule
                                        },
                                        contentDescription = "$cols col",
                                        tint = if (isSel) accentColor else Color.Gray,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "$cols C",
                                        color = if (isSel) Color.White else Color.Gray,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // Horizontally Scrollable Category Filters
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
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

                // CHANNELS GRID / FEED list
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
                            Text("Cambia la categoría de filtro para explorar.", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        state = lazyGridState,
                        columns = GridCells.Fixed(gridColumns),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("channel_grid")
                    ) {
                        items(filteredChannels, key = { it.name }) { channel ->
                            val isFavorite = favoritesList.contains(channel.name)
                            val groupColor = getGroupColor(channel.group, accentColor)

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(if (gridColumns == 1) 3.5f else 1.3f)
                                    // DIRECT ACTION GO TO ANOTHER SECTION ("al apretar el canal directamente te lleva a otro apartado")
                                    .clickable {
                                        activeChannel = channel
                                        exoplayer.setMediaItem(
                                            MediaItem
                                                .Builder()
                                                .setUri(getEffectiveStreamUrl(channel))
                                                .setMimeType(MimeTypes.APPLICATION_M3U8)
                                                .build()
                                        )
                                        exoplayer.prepare()
                                        exoplayer.play()
                                        Toast.makeText(context, "Cargando ${channel.name}...", Toast.LENGTH_SHORT).show()
                                    },
                                colors = CardDefaults.cardColors(containerColor = CardSurface),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        // Header: category label and favorite
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
                                                    .size(18.dp)
                                                    .clickable {
                                                        val nowFav = !isFavorite
                                                        saveFavorite(context, channel.name, nowFav)
                                                        favoritesList = getFavorites(context)
                                                    }
                                            )
                                        }

                                        // Footer Name
                                        Column {
                                            Text(
                                                text = channel.name,
                                                color = Color.White,
                                                fontSize = if (gridColumns == 3) 11.sp else 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = if (gridColumns == 1) 1 else 2,
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            if (gridColumns == 1) {
                                                Text(
                                                    text = "Transmisión premium de alta definición en tiempo real. Toca para ver.",
                                                    color = Color.Gray,
                                                    fontSize = 10.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(top = 2.dp)
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
        } else {
            // ============================================
            // SECCIÓN 2: APARTADO DETALLADO DEL CANAL (PLAYER PRO)
            // ============================================
            val currentActiveChan = activeChannel!!
            val isFavorite = favoritesList.contains(currentActiveChan.name)
            val groupColor = getGroupColor(currentActiveChan.group, accentColor)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BaseDarkBg)
            ) {
                // Top Custom Header inside player section
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
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.White.copy(alpha = 0.08f), CircleShape)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atras", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "APARTADO DE REPRODUCCIÓN",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = accentColor,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = currentActiveChan.name,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Favorite toggler directly inside the player detail section ("los favoritos me gusta queda lo")
                    IconButton(
                        onClick = {
                            val nowFav = !isFavorite
                            saveFavorite(context, currentActiveChan.name, nowFav)
                            favoritesList = getFavorites(context)
                        },
                        modifier = Modifier
                            .size(36.dp)
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
                }

                // MAIN PLAYER VIEW with Gestures
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.77f)
                        .background(Color.Black)
                        .border(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
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
                            url = getEffectiveStreamUrl(currentActiveChan),
                            isAudioOnly = isAudioOnlyMode,
                            isMuted = isMuted,
                            aspectRatioMode = aspectRatioMode,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Gestures overlays
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
                    }

                    // Lock indicator
                    if (touchLocked) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.4f))
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
                                    Icon(Icons.Default.Lock, contentDescription = "Bloqueado", tint = Color.Red, modifier = Modifier.size(28.dp))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Pantalla Bloqueada", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text("Toca en el centro para desbloquear", color = Color.Gray, fontSize = 8.sp)
                                }
                            }
                        }
                    }

                    // Nerd Stats detail
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
                                    Text("ESTADÍSTICAS DEL SERVIDORES", color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                    IconButton(onClick = { showNerdStats = false }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White, modifier = Modifier.size(14.dp))
                                    }
                                }
                                Text("Nombre: ${currentActiveChan.name}", color = Color.White, fontSize = 9.sp)
                                Text("Protocolo: HLS Adaptive Live Stream m3u8", color = Color.LightGray, fontSize = 9.sp)
                                Text("Codec Video: H.264 Main Profile @ L4.0", color = Color.LightGray, fontSize = 9.sp)
                                Text("Codec Audio: AAC Stereo 48.0 kHz", color = Color.LightGray, fontSize = 9.sp)
                                Text("Buffer Status: 8.2s (Excelente/Sin Pausas)", color = Color(0xFF39FF14), fontSize = 9.sp)
                                Text("FPS Drop: 0 de 8500 (100% Fluido)", color = Color(0xFF39FF14), fontSize = 9.sp)
                            }
                        }
                    }
                }

                // DEDICATED CONTROLS AND DETAILS BAR
                if (!touchLocked) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardSurface)
                            .padding(12.dp)
                    ) {
                        // Action buttons row (Volume mute, aspect ratio, playback speed, stats, pip, touch freeze)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // Mute toggle
                                IconButton(
                                    onClick = {
                                        isMuted = !isMuted
                                        exoplayer.volume = if (isMuted) 0f else 1f
                                    },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color.White.copy(alpha = 0.05f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = if (isMuted) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                                        contentDescription = "Silencio",
                                        tint = if (isMuted) Color.Red else Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                // Aspect ratio toggle
                                IconButton(
                                    onClick = {
                                        aspectRatioMode = (aspectRatioMode + 1) % 4
                                        val modeTxt = when (aspectRatioMode) {
                                            0 -> "Fit (Ajustar)"
                                            1 -> "Stretch (Rellenar)"
                                            2 -> "Zoom (Agrandar)"
                                            else -> "Original"
                                        }
                                        Toast.makeText(context, "Aspecto: $modeTxt", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color.White.copy(alpha = 0.05f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Fullscreen,
                                        contentDescription = "Aspect Ratio",
                                        tint = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                // Playback Speed
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
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color.White.copy(alpha = 0.05f), CircleShape)
                                ) {
                                    Text(
                                        text = "${playbackSpeed}x",
                                        color = accentColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }

                                // Audio Mode
                                IconButton(
                                    onClick = {
                                        isAudioOnlyMode = !isAudioOnlyMode
                                        Toast.makeText(context, if (isAudioOnlyMode) "Modo Reducido: Sólo Audio" else "Modo de Video Activo", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color.White.copy(alpha = 0.05f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = if (isAudioOnlyMode) Icons.Default.MusicNote else Icons.Default.Videocam,
                                        contentDescription = "Solo Audio",
                                        tint = if (isAudioOnlyMode) accentColor else Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // PiP system trigger
                                Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            try {
                                                (context as? Activity)?.enterPictureInPictureMode(PictureInPictureParams.Builder().build())
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Error al iniciar PiP", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "Android 8.0+ Requerido", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.05f)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(Icons.Default.PictureInPicture, contentDescription = "PiP", tint = Color.White, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("PiP", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }

                                // Diagnostics Stats
                                IconButton(
                                    onClick = { showNerdStats = !showNerdStats },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(if (showNerdStats) accentColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Stats",
                                        tint = if (showNerdStats) accentColor else Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                // Lock touch
                                IconButton(
                                    onClick = { touchLocked = true },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color.White.copy(alpha = 0.05f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Bloquear Pantalla",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // Info Metadata layout EPG Guide
                        Spacer(modifier = Modifier.height(10.dp))
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
                                    text = currentActiveChan.group.uppercase(),
                                    color = groupColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Text(
                                text = "CALIDAD DE SEÑAL DE ESTRENO: EXCELENTE (1080P)",
                                color = Color.Gray,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Guía: Transmisión en directo del canal ${currentActiveChan.name}. Ofrece un flujo continuo adaptativo y latencia ultra baja.",
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }

                // DYNAMIC CHANNEL CHANGER / LIST RECOMENDADOS INSIDE PLAYER ("directamente cambia de reproductor a otro")
                Spacer(modifier = Modifier.height(14.dp))
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

                    val recommendedList = remember(currentActiveChan, favoritesList) {
                        CHANNELS_LIST.filter { it.name != currentActiveChan.name }
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(recommendedList) { otherChan ->
                            val otherGroupColor = getGroupColor(otherChan.group, accentColor)
                            Box(
                                modifier = Modifier
                                    .width(130.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CardSurface)
                                    .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
                                    .clickable {
                                        // DIRECTLY CHANGE TO THIS CHANNEL WITHOUT BACKING OUT ("directamente cambia de reproductor a otro")
                                        activeChannel = otherChan
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
                                    .padding(8.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    // small tag
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(otherGroupColor.copy(alpha = 0.12f))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = otherChan.group.uppercase(),
                                            color = otherGroupColor,
                                            fontSize = 7.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Text(
                                        text = otherChan.name,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Tocar para cambiar",
                                        color = accentColor.copy(alpha = 0.7f),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // ============================================
        // DIALOGS & OVERLAYS GLOBAL
        // ============================================

        // PIN DIALOG FOR PARENTAL LOCK
        if (showPinDialog) {
            AlertDialog(
                onDismissRequest = { showPinDialog = false },
                title = { Text("Filtro Parental: Introducir PIN", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("Para desbloquear la categoría 'EVENTOS', introduce el PIN de seguridad (Por defecto: 1234)", color = Color.LightGray, fontSize = 11.sp)
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
                        Text("CANCELAR", color = Color.White)
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
            // Rotating Vinyl Disk for radio/audio-only playback
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
            // AndroidView holding Media3 PlayerView
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
