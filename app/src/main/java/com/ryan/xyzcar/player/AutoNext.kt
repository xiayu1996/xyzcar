package com.ryan.xyzcar.player

import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.data.NextPicker
import kotlinx.coroutines.flow.MutableStateFlow

/** 播放服务写、界面读，同一进程共享 */
object AutoNext {
    /** 播完 [afterEid] 后接 [next] */
    data class Plan(val afterEid: String, val next: Episode, val reason: NextPicker.Reason)

    /** [after] 是当初听完的那集，换一集时仍按它选，不能把被换掉的这集当成听完 */
    data class Started(val eid: String, val reason: NextPicker.Reason, val atElapsed: Long, val tried: Set<String>, val after: Episode, val begun: Boolean = false)

    val plan = MutableStateFlow<Plan?>(null)
    /** 用户点了「不要自动播」的单集，它播完就停 */
    val cancelledAfter = MutableStateFlow<String?>(null)
    val started = MutableStateFlow<Started?>(null)

    const val PLAN_AHEAD_MS = 90_000L
    const val ANNOUNCE_MS = 15_000L
    const val SWAP_WINDOW_MS = 10_000L

    fun reset() { plan.value = null; cancelledAfter.value = null; started.value = null }
}
