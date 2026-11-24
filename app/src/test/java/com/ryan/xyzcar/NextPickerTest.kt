package com.ryan.xyzcar

import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.data.NextPicker
import com.ryan.xyzcar.data.NextPicker.Played
import com.ryan.xyzcar.data.NextPicker.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** 自动续播选哪一集：补旧集 > 听了一半 > 订阅新更新（默认关）> 停 */
class NextPickerTest {
    private val now = Instant.parse("2026-11-23T12:00:00Z").toEpochMilli()
    private val day = 24 * 3600_000L
    private fun ep(id: String, pid: String = "p", progress: Int = 0, dur: Int = 3600, finished: Boolean = false, pub: String = "", paid: Boolean = false) =
        Episode(id, pid, "t$id", durationSec = dur, progressSec = progress, finished = finished, pubDate = pub, paid = paid)
    private val done = ep("cur", progress = 3600)

    private fun pick(sameShow: List<Episode> = emptyList(), history: List<Played> = emptyList(), inbox: List<Episode> = emptyList(),
                     rejected: Set<String> = emptySet(), exclude: Set<String> = emptySet(), fresh: Boolean = false) =
        NextPicker.choose(done, sameShow, history, inbox, now, rejected, exclude, fresh)

    @Test fun bingingOlderEpisodeContinuesWithNextNewer() {
        val r = pick(sameShow = listOf(ep("newer"), done), history = listOf(Played(ep("half", "q", 600), now - day)))
        assertEquals("newer", r?.episode?.eid)
        assertEquals(Reason.Continue, r?.reason)
    }

    @Test fun latestEpisodeFallsBackToInProgress() {
        val r = pick(sameShow = listOf(done), history = listOf(Played(ep("old", "q", 600), now - 3 * day), Played(ep("recent", "q", 600), now - day)))
        assertEquals("按最后收听时间", "recent", r?.episode?.eid)
        assertEquals(Reason.InProgress, r?.reason)
    }

    @Test fun nextNewerAlreadyFinishedIsSkipped() {
        assertNull(pick(sameShow = listOf(ep("newer", finished = true), done)))
    }

    @Test fun abandonedEpisodesAreNotPicked() {
        val r = pick(history = listOf(
            Played(ep("tried", progress = 60), now - day),
            Played(ep("stale", progress = 900), now - 20 * day),
            Played(ep("nearlyDone", progress = 3590), now),
            Played(ep("cur", progress = 100), now),               // 刚听完，进度可能还没上报
        ))
        assertNull(r)
    }

    /** 只剩不到 3 分钟的算听完了 */
    @Test fun almostFinishedIsSkipped() {
        val r = pick(history = listOf(Played(ep("tail", progress = 3500), now), Played(ep("ok", progress = 600), now - day)))
        assertEquals("ok", r?.episode?.eid)
        assertNull(pick(sameShow = listOf(ep("newer", progress = 3450), done)))
    }

    @Test fun rejectedPaidAndTriedAreSkipped() {
        val r = pick(history = listOf(
            Played(ep("rej", progress = 600), now), Played(ep("paid", progress = 600, paid = true), now),
            Played(ep("tried", progress = 600), now), Played(ep("ok", progress = 600), now - day),
        ), rejected = setOf("rej"), exclude = setOf("tried"))
        assertEquals("ok", r?.episode?.eid)
    }

    @Test fun freshUpdatesOnlyWhenEnabledAndRecent() {
        val inbox = listOf(ep("new", pub = "2026-11-22T08:00:00Z"), ep("old", pub = "2026-11-10T08:00:00Z"), ep("heard", progress = 30, pub = "2026-11-23T08:00:00Z"))
        assertNull(pick(inbox = inbox))
        val r = pick(inbox = inbox, fresh = true)
        assertEquals("new", r?.episode?.eid)
        assertEquals(Reason.Fresh, r?.reason)
    }
}
