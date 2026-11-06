package com.ryan.xyzcar.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.ui.AppViewModel
import com.ryan.xyzcar.ui.NavDir
import com.ryan.xyzcar.ui.Route
import com.ryan.xyzcar.ui.Tab
import com.ryan.xyzcar.ui.components.EmptyBox
import com.ryan.xyzcar.ui.components.ScreenHeader

private const val MS = 360

/**
 * 转场：
 * - 进入下一级：新页面从右侧滑入，旧页面向左轻移并淡出；返回时反过来
 * - 切换主入口：按导航顺序上下滑动 + 淡入淡出（和左侧竖向导航的方向一致）
 */
private fun AnimatedContentTransitionScope<Route>.transition(dir: NavDir): ContentTransform {
    val ease = tween<androidx.compose.ui.unit.IntOffset>(MS, easing = FastOutSlowInEasing)
    val fade = tween<Float>(MS - 60)
    return when (dir) {
        NavDir.Forward -> (slideInHorizontally(ease) { it / 3 } + fadeIn(fade)) togetherWith
            (slideOutHorizontally(ease) { -it / 8 } + fadeOut(fade))
        NavDir.Back -> (slideInHorizontally(ease) { -it / 8 } + fadeIn(fade)) togetherWith
            (slideOutHorizontally(ease) { it / 3 } + fadeOut(fade) + scaleOut(targetScale = 0.98f))
        NavDir.TabDown -> (slideInVertically(ease) { it / 10 } + fadeIn(fade)) togetherWith
            (slideOutVertically(ease) { -it / 14 } + fadeOut(tween(MS / 2)))
        NavDir.TabUp -> (slideInVertically(ease) { -it / 10 } + fadeIn(fade)) togetherWith
            (slideOutVertically(ease) { it / 14 } + fadeOut(tween(MS / 2)))
    }
}

/** 内容区：按当前路由显示页面，切换时有转场动画 */
@Composable
fun ContentArea(vm: AppViewModel) {
    AnimatedContent(
        targetState = vm.current,
        contentKey = { it.key },
        transitionSpec = { transition(vm.navDir) },
        label = "route",
        modifier = Modifier.fillMaxSize(),
    ) { route ->
        val onBack: (() -> Unit)? = if (route !is Route.Home) ({ vm.back() }) else null
        Column(Modifier.fillMaxSize()) {
            when (route) {
                is Route.Home -> when (route.tab) {
                    Tab.Discover -> DiscoverScreen(vm)
                    Tab.Recommend -> RecommendScreen(vm)
                    Tab.Subscriptions -> SubscriptionsScreen(vm)
                    Tab.Search -> SearchScreen(vm)
                    Tab.Me -> MeScreen(vm)
                }
                is Route.PodcastDetail -> PodcastScreen(vm, route.pid, onBack)
                is Route.EpisodeDetail -> EpisodeScreen(vm, route.episode, onBack)
                is Route.TopListRoute -> TopListScreen(vm, route.category, route.title, onBack)
                is Route.EditorPicks -> EditorPicksScreen(vm, onBack)
                is Route.SubsShows -> SubsShowsScreen(vm, onBack)
                is Route.History -> HistoryScreen(vm, onBack)
                is Route.Queue -> QueueScreen(vm, onBack)
                is Route.Diagnostics -> DiagnosticsScreen(vm, onBack)
            }
        }
    }
}

/** 单集行的通用操作：打开详情 / 播放（正在播放的那集再点是暂停）/ 加入队列 */
class EpisodeActions(private val vm: AppViewModel) {
    fun open(ep: Episode) = vm.push(Route.EpisodeDetail(ep))
    fun play(ep: Episode) {
        if (vm.player.state.value.episode?.eid == ep.eid) vm.player.togglePlay() else vm.player.play(ep)
    }
    fun enqueue(ep: Episode) {
        vm.repo.enqueue(ep)
        vm.bumpQueue()
        vm.player.messages.value = "已加入播放列表：${ep.title}"
    }
}

@Composable
fun Placeholder(title: String, onBack: (() -> Unit)?) {
    ScreenHeader(title, onBack)
    EmptyBox("即将推出")
}
