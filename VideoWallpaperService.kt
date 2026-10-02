package app.videowall

import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

class VideoWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = VEngine()

    inner class VEngine : Engine() {
        private var player: ExoPlayer? = null
        private var cur: String? = null
        private var holder: SurfaceHolder? = null

        private fun load() {
            val n = getSharedPreferences("wall", 0).getString("f", null) ?: return
            val h = holder ?: return
            if (n == cur && player != null) return
            player?.release()
            player = ExoPlayer.Builder(this@VideoWallpaperService).build().apply {
                setVideoSurface(h.surface)
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                setMediaItem(MediaItem.fromUri(Uri.fromFile(File(filesDir, n))))
                prepare()
                playWhenReady = true
            }
            cur = n
        }

        override fun onSurfaceCreated(h: SurfaceHolder) { holder = h; load() }
        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) load()
            player?.playWhenReady = visible
        }
        override fun onSurfaceDestroyed(h: SurfaceHolder) { player?.release(); player = null; cur = null; holder = null }
    }
}
