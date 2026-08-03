package com.bootstudio.ui.components

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * A simple pool for ExoPlayer instances to avoid heavy allocation during scrolling.
 */
object VideoPlayerPool {
    private val pool = mutableListOf<ExoPlayer>()
    private const val MAX_POOL_SIZE = 5

    @OptIn(UnstableApi::class)
    fun acquire(context: Context): ExoPlayer {
        return if (pool.isNotEmpty()) {
            pool.removeAt(0)
        } else {
            // Force hardware acceleration by ensuring default renderers are used
            // and avoiding software fallbacks if possible for better scrolling performance.
            val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
                .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true)

            ExoPlayer.Builder(context, renderersFactory)
                .build().apply {
                    repeatMode = Player.REPEAT_MODE_ALL
                    volume = 0f
                }
        }
    }

    fun release(player: ExoPlayer) {
        if (pool.size < MAX_POOL_SIZE) {
            player.stop()
            player.clearMediaItems()
            // Clear all listeners to prevent memory leaks and duplicate calls
            player.clearVideoSurface()
            pool.add(player)
        } else {
            player.release()
        }
    }
}

@Composable
fun rememberVideoPlayer(): ExoPlayer {
    val context = LocalContext.current
    val player = remember { VideoPlayerPool.acquire(context) }

    DisposableEffect(player) {
        onDispose {
            VideoPlayerPool.release(player)
        }
    }

    return player
}
