package com.ryan.xyzcar.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ryan.xyzcar.App
import com.ryan.xyzcar.MainActivity
import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.data.LastPlayed
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * 播放服务：Media3 ExoPlayer + MediaSession。
 * - 方向盘「上一曲 / 下一曲」= 后退 15 秒 / 前进 30 秒（CarPlayer 改写）
 * - 播放中每 30 秒上报一次进度；暂停、结束时立即上报
 * - 单集结束后自动播放队列里的下一集
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var exo: ExoPlayer
    private val scope = MainScope()
    private var tickJob: Job? = null
    private var lastReportAt = 0L
    private var lastSaveAt = 0L

    override fun onCreate() {
        super.onCreate()
        val app = App.instance
        val dataSource = OkHttpDataSource.Factory(app.api.mediaHttp)
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    // MUSIC：导航播报时自动降低音量（SPEECH 会直接暂停）
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // 预缓冲 ≥ 60 秒，弱网不卡
                    .setBufferDurationsMs(60_000, 180_000, 2_500, 5_000)
                    .build(),
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
        exo.playbackParameters = PlaybackParameters(app.local.speed)
        exo.addListener(listener)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, CarPlayer(exo))
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()

        // 启动即恢复上次的单集（不自动播放），车机媒体卡片能立刻显示
        app.local.lastPlayed?.let { last ->
            exo.setMediaItem(buildMediaItem(last.episode), last.positionMs)
            exo.prepare()
        }
        scope.launch { runCatching { app.repo.flushPendingProgress() } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveAndReport(force = true)
        if (!exo.playWhenReady || exo.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        saveAndReport(force = true)
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            tickJob?.cancel()
            if (isPlaying) {
                tickJob = scope.launch {
                    while (isActive) {
                        delay(1_000)
                        saveAndReport(force = false)
                    }
                }
            } else {
                saveAndReport(force = true)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                val ep = currentEpisode() ?: return
                // 听完：进度上报为总时长
                val dur = (exo.duration.takeIf { it > 0 } ?: (ep.durationSec * 1000L)) / 1000
                scope.launch { runCatching { App.instance.repo.reportProgress(ep, dur.toInt()) } }
                playNextInQueue()
            }
        }

        override fun onPlaybackParametersChanged(params: PlaybackParameters) {
            App.instance.local.speed = params.speed
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            lastSaveAt = 0
            saveAndReport(force = false)
        }
    }

    private fun playNextInQueue() {
        val local = App.instance.local
        val queue = local.queue
        val next = queue.firstOrNull() ?: return
        local.queue = queue.drop(1)
        scope.launch {
            val startSec = runCatching { App.instance.repo.serverProgress(next) }.getOrNull() ?: 0
            val startMs = if (next.durationSec > 0 && startSec >= next.durationSec - 5) 0L else startSec * 1000L
            exo.setMediaItem(buildMediaItem(next), startMs)
            exo.prepare()
            exo.play()
        }
    }

    private fun currentEpisode(): Episode? = exo.currentMediaItem?.let { episodeOf(it) }

    /** 本地每 5 秒保存一次续播点；服务端每 30 秒最多上报一次 */
    private fun saveAndReport(force: Boolean) {
        if (!::exo.isInitialized) return
        val ep = currentEpisode() ?: return
        val pos = exo.currentPosition.coerceAtLeast(0)
        val now = System.currentTimeMillis()
        val app = App.instance
        if (force || now - lastSaveAt >= 5_000) {
            lastSaveAt = now
            app.local.lastPlayed = LastPlayed(ep, pos, exo.playbackParameters.speed)
        }
        if (pos > 0 && (force || now - lastReportAt >= REPORT_INTERVAL_MS)) {
            lastReportAt = now
            scope.launch { runCatching { app.repo.reportProgress(ep, (pos / 1000).toInt()) } }
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        // 排查用：记下哪些系统组件（媒体卡片、小迪、方控）连了播放服务、发了什么按键
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName != packageName) {
                com.ryan.xyzcar.data.DebugLog.kv("media", "controller_connect", "pkg" to controller.packageName, "uid" to controller.uid, "version" to controller.controllerVersion, "trusted" to controller.isTrusted)
            }
            return super.onConnect(session, controller)
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (controller.packageName != packageName) com.ryan.xyzcar.data.DebugLog.kv("media", "controller_disconnect", "pkg" to controller.packageName)
        }

        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: android.content.Intent): Boolean {
            val key = runCatching {
                androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
            }.getOrNull()
            com.ryan.xyzcar.data.DebugLog.kv("media", "media_button", "from" to controllerInfo.packageName, "key" to key?.keyCode, "action" to key?.action)
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        // 控制器发来的 MediaItem 会丢掉 uri，这里用 extras 里的单集信息重建
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { item -> episodeOf(item)?.let { buildMediaItem(it) } ?: item }.toMutableList())

        // 系统「继续播放」（例如车机开机后点媒体卡片）
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            com.ryan.xyzcar.data.DebugLog.kv("media", "playback_resumption", "from" to controller.packageName)
            val last = App.instance.local.lastPlayed
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("no last played"))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(listOf(buildMediaItem(last.episode)), 0, last.positionMs),
            )
        }
    }

    companion object {
        const val SEEK_BACK_MS = 15_000L
        const val SEEK_FORWARD_MS = 30_000L
        const val REPORT_INTERVAL_MS = 30_000L
        private const val KEY_EPISODE = "episode_json"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun buildMediaItem(ep: Episode): MediaItem {
            val extras = Bundle().apply { putString(KEY_EPISODE, json.encodeToString(Episode.serializer(), ep)) }
            val meta = MediaMetadata.Builder()
                .setTitle(ep.title)
                .setArtist(ep.podcastTitle)
                .setAlbumTitle(ep.podcastTitle)
                .setArtworkUri(ep.coverUrl.takeIf { it.isNotEmpty() }?.let { android.net.Uri.parse(it) })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .setExtras(extras)
                .build()
            return MediaItem.Builder()
                .setMediaId(ep.eid)
                .setUri(ep.audioUrl)
                .setMediaMetadata(meta)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(extras).build())
                .build()
        }

        fun episodeOf(item: MediaItem): Episode? {
            val s = item.mediaMetadata.extras?.getString(KEY_EPISODE)
                ?: item.requestMetadata.extras?.getString(KEY_EPISODE)
                ?: return null
            return runCatching { json.decodeFromString(Episode.serializer(), s) }.getOrNull()
        }
    }
}

/** 把「上一曲 / 下一曲」改写成「后退 15 秒 / 前进 30 秒」，并始终对系统声明这两个命令可用 */
@OptIn(UnstableApi::class)
class CarPlayer(player: Player) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            ).build()

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun seekToNext() = seekForward()
    override fun seekToNextMediaItem() = seekForward()
    override fun seekToPrevious() = seekBack()
    override fun seekToPreviousMediaItem() = seekBack()
    override fun hasNextMediaItem(): Boolean = true
    override fun hasPreviousMediaItem(): Boolean = true
}
