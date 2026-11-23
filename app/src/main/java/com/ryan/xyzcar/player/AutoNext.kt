package com.ryan.xyzcar.player

import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.data.NextPicker
import kotlinx.coroutines.flow.MutableStateFlow

/** 自动续播的共享状态（播放服务写，界面读；同一进程） */
object AutoNext {
    /** 单集剩 [PLAN_AHEAD_MS] 时提前选好的下一集；[afterEid] = 播完哪一集后接它 */
    data class Plan(val afterEid: String, val next: Episode, val reason: NextPicker.Reason)

    /**
     * 刚自动接上的单集；真正开始出声后 [SWAP_WINDOW_MS] 内可以换一集（方向盘「下一曲」或提示条按钮）。
     * [after] = 当初听完的那集，换一集时仍按它来选（不能把被换掉的这集当成「听完」）
     */
    data class Started(val eid: String, val reason: NextPicker.Reason, val atElapsed: Long, val tried: Set<String>, val after: Episode, val begun: Boolean = false)

    val plan = MutableStateFlow<Plan?>(null)
    /** 用户点了「不要自动播」的那一集：它播完就停 */
    val cancelledAfter = MutableStateFlow<String?>(null)
    val started = MutableStateFlow<Started?>(null)

    const val PLAN_AHEAD_MS = 90_000L
    /** 提示条在单集剩这么多时出现 */
    const val ANNOUNCE_MS = 15_000L
    const val SWAP_WINDOW_MS = 10_000L

    fun reset() { plan.value = null; cancelledAfter.value = null; started.value = null }
}
