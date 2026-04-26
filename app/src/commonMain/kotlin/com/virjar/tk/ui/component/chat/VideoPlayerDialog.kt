package com.virjar.tk.ui.component.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.virjar.tk.util.AppLog
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState

@Composable
fun VideoPlayerDialog(videoUrl: String, onDismiss: () -> Unit) {
    val playerState = rememberVideoPlayerState()

    AppLog.d("VideoPlayer", "Opening video URL: $videoUrl")

    if (videoUrl.isBlank()) {
        AppLog.e("VideoPlayer", "videoUrl is blank, cannot play")
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = Color.Black,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Invalid video URL", color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = Color.White)
                    }
                }
            }
        }
        return
    }

    // openUri must run on Main thread — ExoPlayer (Android) requires main thread access.
    // Desktop uses VLC which is also main-thread-safe for this call.
    LaunchedEffect(videoUrl) {
        try {
            playerState.openUri(videoUrl)
        } catch (e: Exception) {
            AppLog.e("VideoPlayer", "openUri failed: ${e.message}", e)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            playerState.stop()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.large,
            color = Color.Black,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // -- Top bar: close button (outside VideoPlayerSurface) --
                Row(
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                        )
                    }
                }

                // -- Video area --
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    VideoPlayerSurface(
                        playerState = playerState,
                        modifier = Modifier.fillMaxSize(),
                        overlay = {
                            if (playerState.isLoading) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = Color.White)
                                }
                            }

                            playerState.error?.let { err ->
                                val errMsg = when (err) {
                                    is VideoPlayerError.CodecError -> err.message
                                    is VideoPlayerError.NetworkError -> err.message
                                    is VideoPlayerError.SourceError -> err.message
                                    is VideoPlayerError.UnknownError -> err.message
                                }
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(errMsg ?: "Unknown error", color = Color.White, modifier = Modifier.padding(16.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        // Note: avoid calling playerState.clearError() here as it may
                                        // deadlock on main thread due to library's runBlocking usage
                                        TextButton(onClick = onDismiss) {
                                            Text("Close", color = Color.White)
                                        }
                                    }
                                }
                            }
                        },
                    )
                }

                // -- Bottom controls (outside VideoPlayerSurface) --
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                            )
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Column {
                        val sliderPos = playerState.sliderPos
                        val sliderValue = if (sliderPos.isNaN()) 0f else sliderPos.coerceIn(0f, 1000f)
                        Slider(
                            value = sliderValue,
                            onValueChange = {
                                playerState.sliderPos = it
                                playerState.userDragging = true
                            },
                            onValueChangeFinished = {
                                playerState.userDragging = false
                                playerState.seekTo(playerState.sliderPos)
                            },
                            valueRange = 0f..1000f,
                            enabled = !sliderPos.isNaN(),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                            ),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    if (playerState.isPlaying) playerState.pause() else playerState.play()
                                },
                            ) {
                                Icon(
                                    if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${playerState.positionText} / ${playerState.durationText}",
                                color = Color.White,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
