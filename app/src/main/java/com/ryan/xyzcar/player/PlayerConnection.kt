package com.ryan.xyzcar.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ryan.xyzcar.App
import com.ryan.xyzcar.data.Episode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.await
import kotlin.math.abs

data class PlayerUi(
    val episode: Episode? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val connected: Boolean = false,
)

class PlayerConnection(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app = App.instance
    private var controller: MediaController? = null

    private val _state = MutableStateFlow(
        app.local.lastPlayed?.let {
            PlayerUi(episode = it.episode, positionMs = it.positionMs, durationMs = it.episode.durationSec * 1000L, speed = app.local.speed)
        } ?: PlayerUi(speed = app.local.speed),
    )
    val state: StateFlow<PlayerUi> = _state.asStateFlow()

    /** 不跟随每 0.5 秒的进度变化，播放时列表页不用一秒重组两次 */
    val playingEid: StateFlow<String?> = _state.map { if (it.isPlaying) it.episode?.eid else null }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)
    val currentEid: StateFlow<String?> = _state.map { it.episode?.eid }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, _state.value.episode?.eid)

    /** elapsedRealtime 毫秒，null 表示没开 */
    val sleepEndsAt = MutableStateFlow<Long?>(null)
    private var sleepJob: kotlinx.coroutines.Job? = null

    fun cycleSleep() {
        val left = sleepEndsAt.value?.let { (it - android.os.SystemClock.elapsedRealtime()) / 60_000 }
        val next = when {
            left == null -> 15
            left < 15 -> 30
            left < 30 -> 60
            else -> null
        }
        sleepJob?.cancel()
        if (next == null) { sleepEndsAt.value = null; messages.value = "已关闭定时"; return }
        val end = android.os.SystemClock.elapsedRealtime() + next * 60_000L
        sleepEndsAt.value = end
        messages.value = "$next 分钟后暂停播放"
        sleepJob = scope.launch {
            delay(next * 60_000L)
            controller?.pause()
            sleepEndsAt.value = null
            refresh()
        }
    }

    val messages = MutableStateFlow<String?>(null)

    init {
        scope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val c = runCatching { MediaController.Builder(context, token).buildAsync().await() }.getOrNull() ?: return@launch
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
            })
            refresh()
            syncWithServer()
            while (true) {
                delay(500)
                if (c.isPlaying) refresh()
            }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val ep = c.currentMediaItem?.let { PlaybackService.episodeOf(it) } ?: _state.value.episode
        val dur = c.duration.takeIf { it > 0 } ?: ((ep?.durationSec ?: 0) * 1000L)
        _state.value = PlayerUi(
            episode = ep,
            isPlaying = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = if (c.mediaItemCount > 0) c.currentPosition else _state.value.positionMs,
            durationMs = dur,
            speed = c.playbackParameters.speed,
            connected = true,
        )
    }

    /** 手机上后来听了另一集就切过去（不自动播），同一集则对齐服务端进度；本地有未补发进度时以本地为准 */
    private suspend fun syncWithServer() {
        val c = controller ?: return
        if (c.isPlaying) return
        val local = app.local.lastPlayed
        val latest = runCatching { app.repo.latestPlayed() }.getOrNull()
        if (latest != null && latest.episode.eid != local?.episode?.eid &&
            (local == null || latest.playedAtMs > local.savedAt)
        ) {
            val full = runCatching { app.repo.ensurePlayable(latest.episode) }.getOrDefault(latest.episode)
            if (full.audioUrl.isNotEmpty() && !c.isPlaying) {
                val done = full.durationSec > 0 && latest.progressSec >= full.durationSec - 10
                c.setMediaItem(PlaybackService.buildMediaItem(full), if (done) 0L else latest.progressSec * 1000L)
                c.prepare()
                refresh()
            }
            return
        }
        val ep = _state.value.episode ?: return
        if (app.local.pendingProgress.any { it.eid == ep.eid }) return
        val server = runCatching { app.repo.serverProgress(ep) }.getOrNull() ?: return
        val serverMs = server * 1000L
        if (abs(serverMs - _state.value.positionMs) > 5_000 && c.mediaItemCount > 0 && !c.isPlaying) {
            c.seekTo(serverMs)
            refresh()
        }
    }

    fun cancelAutoNext(afterEid: String) {
        AutoNext.cancelledAfter.value = afterEid
        AutoNext.plan.value = null
    }

    fun swapAutoNext() { PlaybackService.current?.swapAuto(); refresh() }

    fun reloadAccount() {
        _state.value = app.local.lastPlayed?.let {
            PlayerUi(episode = it.episode, positionMs = it.positionMs, durationMs = it.episode.durationSec * 1000L, speed = app.local.speed, connected = controller != null)
        } ?: PlayerUi(speed = app.local.speed, connected = controller != null)
        sleepJob?.cancel(); sleepEndsAt.value = null
        scope.launch { delay(300); refresh(); syncWithServer() }
    }

    /** 手机上可能听过：回到前台时对齐服务端进度 */
    fun onForeground() {
        scope.launch {
            runCatching { app.repo.flushPendingProgress() }
            syncWithServer()
        }
    }

    fun play(ep: Episode) {
        scope.launch {
            val c = controller ?: return@launch
            val full = runCatching { app.repo.ensurePlayable(ep) }.getOrDefault(ep)
            if (full.audioUrl.isEmpty()) {
                messages.value = "无法获取音频地址（可能是付费单集）"
                return@launch
            }
            val server = runCatching { app.repo.serverProgress(full) }.getOrNull() ?: full.progressSec
            val startMs = if (full.durationSec > 0 && server >= full.durationSec - 10) 0L else server * 1000L
            c.setMediaItem(PlaybackService.buildMediaItem(full), startMs)
            c.prepare()
            c.play()
            refresh()
        }
    }

    fun playAt(ep: Episode, ms: Long) {
        val c = controller ?: return
        if (c.currentMediaItem?.mediaId == ep.eid && c.mediaItemCount > 0) {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.seekTo(ms)
            if (!c.isPlaying) c.play()
            refresh()
            return
        }
        scope.launch {
            val full = runCatching { app.repo.ensurePlayable(ep) }.getOrDefault(ep)
            if (full.audioUrl.isEmpty()) { messages.value = "无法获取音频地址"; return@launch }
            c.setMediaItem(PlaybackService.buildMediaItem(full), ms)
            c.prepare()
            c.play()
            refresh()
        }
    }

    fun togglePlay() {
        val c = controller ?: return
        when {
            c.isPlaying -> c.pause()
            c.mediaItemCount == 0 -> _state.value.episode?.let { play(it) }
            else -> {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
                c.play()
            }
        }
        refresh()
    }

    fun seekBack() { controller?.seekBack(); refresh() }
    fun seekForward() { controller?.seekForward(); refresh() }

    fun seekTo(ms: Long) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            _state.value.episode?.let { ep -> scope.launch { play(ep); delay(300); c.seekTo(ms) } }
            return
        }
        c.seekTo(ms)
        refresh()
    }

    fun cycleSpeed() {
        val c = controller ?: return
        val cur = c.playbackParameters.speed
        val next = SPEEDS.firstOrNull { it > cur + 0.01f } ?: SPEEDS.first()
        c.playbackParameters = PlaybackParameters(next)
        refresh()
    }

    fun release() {
        controller?.release()
    }

    companion object {
        val SPEEDS = listOf(0.8f, 1.0f, 1.25f, 1.5f, 2.0f)
    }
}
