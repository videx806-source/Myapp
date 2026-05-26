package com.example

import android.util.Log
import java.io.File
import java.io.OutputStream
import java.net.URL
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object HlsRecorder {
    private const val TAG = "HlsRecorder"

    suspend fun recordStream(
        streamUrl: String,
        outputFile: File,
        isActiveProvider: () -> Boolean,
        onProgress: suspend (Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        var currentUrl = streamUrl
        val downloadedSegments = mutableSetOf<String>()
        
        // Limit total size to 15MB to protect low-end devices' storage and RAM
        val maxBytes = 15 * 1024 * 1024L 
        var totalBytesWritten = 0L

        try {
            outputFile.outputStream().use { output ->
                while (isActiveProvider() && totalBytesWritten < maxBytes) {
                    if (currentUrl.contains(".m3u8")) {
                        // For HLS .m3u8 playlists
                        val playlistText = fetchText(currentUrl)
                        if (playlistText == null || playlistText.trim().isEmpty()) {
                            delay(2000L)
                            continue
                        }

                        // Check if Master Playlist
                        if (playlistText.contains("#EXT-X-STREAM-INF")) {
                            val subPlaylistUrl = parseSubPlaylist(playlistText, currentUrl)
                            if (subPlaylistUrl != null && subPlaylistUrl != currentUrl) {
                                currentUrl = subPlaylistUrl
                                continue
                            }
                        }

                        // Parse the media playlist segments
                        val segments = parseSegments(playlistText, currentUrl)
                        if (segments.isEmpty()) {
                            delay(2000L)
                            continue
                        }

                        var downloadedAny = false
                        for (segmentUrl in segments) {
                            if (!isActiveProvider() || totalBytesWritten >= maxBytes) break
                            
                            if (!downloadedSegments.contains(segmentUrl)) {
                                val success = downloadSegmentToStream(segmentUrl, output) { bytesAdded ->
                                    totalBytesWritten += bytesAdded
                                }
                                if (success) {
                                    downloadedSegments.add(segmentUrl)
                                    downloadedAny = true
                                    onProgress(totalBytesWritten)
                                }
                            }
                        }

                        if (!downloadedAny) {
                            // Wait for new segments to be appended to the live playlist
                            delay(4000L)
                        } else {
                            delay(2000L)
                        }
                    } else {
                        // Direct progressive download (for MP4 stream or similar media)
                        downloadDirectStream(currentUrl, output, maxBytes, isActiveProvider, onProgress)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during stream recording: ${e.message}", e)
        }
    }

    private fun fetchText(urlStr: String): String? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection().apply {
                connectTimeout = 4000
                readTimeout = 4000
            }
            conn.getInputStream().bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch playlist text: ${e.message}")
            null
        }
    }

    private fun parseSubPlaylist(playlistText: String, parentUrl: String): String? {
        val lines = playlistText.lineSequence().map { it.trim() }.toList()
        var bestBandwidth = Long.MAX_VALUE
        var selectedUrl: String? = null

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                // Find bandwidth in string: e.g. BANDWIDTH=1280000
                val bandwidthMatch = Regex("BANDWIDTH=(\\d+)").find(line)
                val bw = bandwidthMatch?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE

                if (i + 1 < lines.size) {
                    val nextLine = lines[i + 1]
                    if (!nextLine.startsWith("#") && nextLine.isNotEmpty()) {
                        // For low-end devices, always choose the lowest bandwidth track for absolute buffer safety and speed
                        if (selectedUrl == null || bw < bestBandwidth) {
                            bestBandwidth = bw
                            selectedUrl = nextLine
                        }
                    }
                }
            }
            i++
        }
        return selectedUrl?.let { resolveUri(parentUrl, it) }
    }

    private fun parseSegments(playlistText: String, parentUrl: String): List<String> {
        val segments = mutableListOf<String>()
        val lines = playlistText.lineSequence().map { it.trim() }.toList()
        for (line in lines) {
            if (line.isNotEmpty() && !line.startsWith("#")) {
                segments.add(resolveUri(parentUrl, line))
            }
        }
        return segments
    }

    private fun resolveUri(baseUri: String, relativeUri: String): String {
        return try {
            if (relativeUri.startsWith("http://") || relativeUri.startsWith("https://")) {
                relativeUri
            } else {
                val base = URI(baseUri)
                base.resolve(relativeUri).toString()
            }
        } catch (e: Exception) {
            relativeUri
        }
    }

    private fun downloadSegmentToStream(
        segmentUrl: String,
        outputStream: OutputStream,
        onBytesWritten: (Int) -> Unit
    ): Boolean {
        return try {
            val url = URL(segmentUrl)
            val conn = url.openConnection().apply {
                connectTimeout = 4000
                readTimeout = 4000
            }
            conn.getInputStream().use { input ->
                val buffer = ByteArray(1024 * 8)
                var read: Int
                while (true) {
                    read = input.read(buffer)
                    if (read == -1) break
                    outputStream.write(buffer, 0, read)
                    onBytesWritten(read)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download segment $segmentUrl: ${e.message}")
            false
        }
    }

    private suspend fun downloadDirectStream(
        streamUrl: String,
        outputStream: OutputStream,
        maxBytes: Long,
        isActiveProvider: () -> Boolean,
        onProgress: suspend (Long) -> Unit
    ) {
        try {
            val url = URL(streamUrl)
            val conn = url.openConnection().apply {
                connectTimeout = 5000
                readTimeout = 5000
            }
            var totalBytes = 0L
            conn.getInputStream().use { input ->
                val buffer = ByteArray(1024 * 8)
                var read: Int
                while (isActiveProvider() && totalBytes < maxBytes) {
                    read = input.read(buffer)
                    if (read == -1) break
                    outputStream.write(buffer, 0, read)
                    totalBytes += read
                    onProgress(totalBytes)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct stream download error: ${e.message}")
        }
    }
}
