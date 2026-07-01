package com.bootstudio.ui.components

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
@Composable
fun VideoPreview(uri: Uri, modifier: Modifier = Modifier.fillMaxSize(), onLoadingFailed: () -> Unit = {}) {
    val context = LocalContext.current
    val exoPlayer = rememberVideoPlayer()

    LaunchedEffect(uri) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    exoPlayer.play()
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                onLoadingFailed()
            }
        }

        exoPlayer.addListener(listener)
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()

        try {
            // Wait until cancelled
            kotlinx.coroutines.awaitCancellation()
        } finally {
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
        }
    }

    AndroidView(
        factory = {
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

                // Revert to SurfaceView (Type 0) for maximum hardware performance.
                // SurfaceView is more efficient for hardware decoding as it renders directly
                // to its own hardware layer.
                try {
                    val setSurfaceType = PlayerView::class.java.getDeclaredMethod("setSurfaceType", Int::class.javaPrimitiveType)
                    setSurfaceType.isAccessible = true
                    setSurfaceType.invoke(this, 0) // 0 is SURFACE_TYPE_SURFACE_VIEW
                } catch (e: Exception) {
                    // Fallback to default if reflection fails
                }

                player = exoPlayer
                isClickable = false
                isFocusable = false
                isEnabled = false
            }
        },
        update = {
            it.player = exoPlayer
        },
        modifier = modifier
    )
}
