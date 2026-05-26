package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

// Custom Theme Colors as specified in user guidelines
val BaseDarkBg = Color(0xFF0A0A0F)
val CardSurface = Color(0xFF111118)
val AccentCyan = Color(0xFF00D4FF)

fun getGroupColor(group: String): Color {
    return when (group.uppercase()) {
        "GENERAL" -> Color(0xFF4A90D9)
        "EVENTOS" -> Color(0xFFE53935)
        "MÚSICA", "MUSICA" -> Color(0xFF00D4FF)
        "PLUTO" -> Color(0xFF3D5AFE)
        else -> Color(0xFFE0E0E0)
    }
}

class MainActivity : ComponentActivity() {
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
}

@Composable
fun VidexAppScreen(
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("TODOS") }
    var activeChannel by remember { mutableStateOf<Channel?>(null) }

    val categories = listOf("TODOS", "GENERAL", "EVENTOS", "MÚSICA", "PLUTO")

    // Filtered channels calculated reactively
    val filteredChannels by remember {
        derivedStateOf {
            CHANNELS_LIST.filter { channel ->
                // First filter by group category
                val matchesCategory = if (selectedCategory == "TODOS") {
                    true
                } else {
                    channel.group.uppercase() == selectedCategory.uppercase()
                }

                // Second filter by search query
                val matchesSearch = channel.name.contains(searchQuery, ignoreCase = true)

                matchesCategory && matchesSearch
            }
        }
    }

    val lazyGridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

    LazyVerticalGrid(
        state = lazyGridState,
        columns = GridCells.Adaptive(minSize = 135.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxSize()
            .testTag("channel_grid")
    ) {
        // 1. Header (glowing cyber logo)
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "VIDEX",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.SansSerif,
                            color = AccentCyan,
                            letterSpacing = 2.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            text = "IPTV STREAM CONTROLLER",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFA0A0AF),
                            letterSpacing = 1.sp
                        )
                    }

                    // A subtle live badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFFF1744))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "LIVE TV",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(AccentCyan, Color.Transparent, Color.Transparent)
                            )
                        )
                )
            }
        }

        // 2. Player (If a stream is active, positioned at the top of controls)
        item(span = { GridItemSpan(maxLineSpan) }) {
            AnimatedVisibility(
                visible = activeChannel != null,
                enter = fadeIn(animationSpec = tween(400)),
                exit = fadeOut(animationSpec = tween(300))
            ) {
                activeChannel?.let { channel ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 20.dp)
                    ) {
                        // Header on top of the player
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                                .background(CardSurface)
                                .border(
                                    border = BorderStroke(1.dp, getGroupColor(channel.group).copy(alpha = 0.3f)),
                                    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = channel.name.uppercase(),
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(AccentCyan)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${channel.group} STREAMING ACTIVE",
                                        color = getGroupColor(channel.group),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            IconButton(
                                onClick = { activeChannel = null },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Stop Stream",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Real ExoPlayer viewport
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                                .background(Color.Black)
                                .border(
                                    border = BorderStroke(1.dp, getGroupColor(channel.group).copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                                )
                        ) {
                            RealVideoPlayer(
                                url = channel.streamUrl,
                                modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("video_player")
                            )
                        }
                    }
                }
            }
        }

        // 3. Search and Categories Group Controller
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = "Buscar canal...",
                            color = Color(0xFF7E7E8F)
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Buscador",
                            tint = AccentCyan
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear Search",
                                    tint = Color.White
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = CardSurface,
                        unfocusedContainerColor = CardSurface,
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Group filter chips (Horizontal Scrollable Tabs)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { category ->
                        val isSelected = selectedCategory.uppercase() == category.uppercase()
                        val chipColor = if (category == "TODOS") AccentCyan else getGroupColor(category)

                        Box(
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(50.dp))
                                .background(
                                    if (isSelected) {
                                        chipColor.copy(alpha = 0.2f)
                                    } else {
                                        CardSurface
                                    }
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) chipColor else Color.White.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(50.dp)
                                )
                                .clickable {
                                    selectedCategory = category
                                }
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                                .testTag("category_tab_${category.lowercase()}")
                        ) {
                            Text(
                                text = category,
                                color = if (isSelected) chipColor else Color(0xFF8E8E9F),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }
        }

        // 4. Grid of Channels
        if (filteredChannels.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Ningún canal coincide",
                        color = Color(0xFF7E7E8F),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Intenta buscar con otros términos",
                        color = Color(0xFF5E5E6F),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            items(filteredChannels, key = { it.name }) { channel ->
                val isActive = activeChannel?.name == channel.name
                val borderGroupColor = getGroupColor(channel.group)

                // Optional flashing/pulsing effect for selected card
                val infiniteTransition = rememberInfiniteTransition(label = "pulse_effect")
                val animatedAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, delayMillis = 100),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "border_alpha"
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.6f)
                        .testTag("channel_item_${channel.name}")
                        .clickable {
                            activeChannel = channel
                            // Auto-scroll seamlessly to top (Player view layout index)
                            coroutineScope.launch {
                                lazyGridState.animateScrollToItem(1)
                            }
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) CardSurface.copy(alpha = 0.85f) else CardSurface
                    ),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        width = if (isActive) 2.dp else 1.dp,
                        color = if (isActive) borderGroupColor.copy(alpha = animatedAlpha) else Color.White.copy(alpha = 0.08f)
                    )
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
                            // Subtitle Group Capsule
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(borderGroupColor.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = channel.group,
                                    color = borderGroupColor,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.5.sp
                                )
                            }

                            // Dynamic channel title
                            Text(
                                text = channel.name,
                                color = if (isActive) borderGroupColor else Color(0xFFE0E0E0),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        // Play/Pause subtle indicators
                        if (isActive) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Active Channel indicator",
                                tint = borderGroupColor,
                                modifier = Modifier
                                    .size(16.dp)
                                    .align(Alignment.TopEnd)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Media3 HLS ExoPlayer wrapper
@OptIn(UnstableApi::class)
@Composable
fun RealVideoPlayer(
    url: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Initialize and remember ExoPlayer
    val exoPlayer = remember {
        val playerContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            context.applicationContext.createAttributionContext("media")
        } else {
            context.applicationContext
        }
        ExoPlayer.Builder(playerContext).build().apply {
            playWhenReady = true
        }
    }

    // React to stream URL modifications natively
    LaunchedEffect(url) {
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    // Lifecycle clean ups
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    // Android lifecycle listener
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    exoPlayer.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    exoPlayer.play()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Output AndroidView holding PlayerView
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                setBackgroundColor(0xFF000000.toInt())
            }
        },
        modifier = modifier
    )
}
