package com.ryan.xyzcar.data

import java.time.Instant

/**
 * 播放列表空了、当前单集听完后自动接着播哪一集。听完本身是信号：认可这个节目、还有时间继续听。
 * 按用户主动表达过的意愿从强到弱：
 * 1. 补旧集：刚听完的不是该节目最新一集，紧挨着的更新一集还没听完 → 接着播它
 * 2. 听了一半：收听历史里没听完的，按最后收听时间排；只听了开头（< [MIN_STARTED_SEC]）或超过 [STALE_DAYS] 天没碰的算放弃
 * 3. 订阅新更新（默认关）：[FRESH_DAYS] 天内发布、还没听过的
 * 都没有就停，不去推荐里找。付费单集、用户换掉过的单集、[exclude] 里的不选；
 * 只剩不到 [MIN_LEFT_SEC] 的也不选（多半是片尾 / 广告，用户其实算听完了，接上去几十秒又要再换）。纯函数，方便测试
 */
object NextPicker {
    const val MIN_STARTED_SEC = 120
    const val STALE_DAYS = 14L
    const val FRESH_DAYS = 3L
    const val MIN_LEFT_SEC = 180
    private const val DAY_MS = 24 * 3600_000L

    enum class Reason(val label: String) { Continue("接着补这个节目"), InProgress("接着听没听完的"), Fresh("订阅新更新") }
    data class Pick(val episode: Episode, val reason: Reason)
    /** 收听历史的一项：单集（含进度）和最后收听时间 */
    data class Played(val episode: Episode, val playedAtMs: Long)

    fun choose(
        finished: Episode,
        /** 同一节目的单集，最新在前，带进度；没取到就传空 */
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
