package com.ryan.xyzcar.data

import java.time.Instant

/**
 * 按用户意愿从强到弱选：同节目紧挨着的更新一集 → 没听完的 → 订阅新更新；只听了开头或超过 [STALE_DAYS] 天没碰的算放弃。
 * 剩不到 [MIN_LEFT_SEC] 的多半是片尾 / 广告，用户其实算听完了，接上去几十秒又要再换
 */
object NextPicker {
    const val MIN_STARTED_SEC = 120
    const val STALE_DAYS = 14L
    const val FRESH_DAYS = 3L
    const val MIN_LEFT_SEC = 180
    private const val DAY_MS = 24 * 3600_000L

    enum class Reason(val label: String) { Continue("接着补这个节目"), InProgress("接着听没听完的"), Fresh("订阅新更新") }
    data class Pick(val episode: Episode, val reason: Reason)
    data class Played(val episode: Episode, val playedAtMs: Long)

    fun choose(
        finished: Episode,
        /** 最新在前，带进度 */
        sameShow: List<Episode>,
        history: List<Played>,
        inbox: List<Episode>,
        nowMs: Long,
        rejected: Set<String>,
        exclude: Set<String>,
        includeFresh: Boolean,
    ): Pick? {
        fun ok(e: Episode) = e.eid != finished.eid && e.eid !in exclude && e.eid !in rejected && !e.paid &&
            !(e.durationSec > 0 && e.durationSec - e.progressSec < MIN_LEFT_SEC)
        val i = sameShow.indexOfFirst { it.eid == finished.eid }
        if (i > 0) sameShow[i - 1].takeIf { ok(it) && it.isTodo() }?.let { return Pick(it, Reason.Continue) }
        history
            .filter { (e, at) -> ok(e) && e.isTodo() && e.progressSec >= MIN_STARTED_SEC && nowMs - at <= STALE_DAYS * DAY_MS }
            .maxByOrNull { it.playedAtMs }
            ?.let { return Pick(it.episode, Reason.InProgress) }
        if (includeFresh) inbox
            .filter { ok(it) && !it.finished && it.progressSec == 0 && parseTime(it.pubDate)?.let { t -> nowMs - t <= FRESH_DAYS * DAY_MS } == true }
            .maxByOrNull { parseTime(it.pubDate) ?: 0L }
            ?.let { return Pick(it, Reason.Fresh) }
        return null
    }

    fun parseTime(s: String): Long? = runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
}
