package com.ryan.xyzcar.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.ui.AppViewModel
import com.ryan.xyzcar.ui.Route
import com.ryan.xyzcar.ui.Tab
import com.ryan.xyzcar.ui.components.EmptyBox
import com.ryan.xyzcar.ui.components.ScreenHeader

/** 内容区：按当前路由显示页面 */
@Composable
fun ContentArea(vm: AppViewModel) {
    val route = vm.current
    val onBack: (() -> Unit)? = if (vm.stack.size > 1) ({ vm.back() }) else null
    key(route.key) {
        Column(Modifier.fillMaxSize()) {
            when (route) {
                is Route.Home -> when (route.tab) {
                    Tab.Subscriptions -> SubscriptionsScreen(vm)
                    Tab.Discover -> DiscoverScreen(vm)
                    Tab.Search -> SearchScreen(vm)
                    Tab.History -> HistoryScreen(vm)
                }
                is Route.PodcastDetail -> PodcastScreen(vm, route.pid, onBack)
                is Route.EpisodeDetail -> EpisodeScreen(vm, route.episode, onBack)
                is Route.TopListRoute -> TopListScreen(vm, route.category, route.title, onBack)
                is Route.Categories -> CategoriesScreen(vm, onBack)
                is Route.CategoryRoute -> CategoryScreen(vm, route.id, route.name, onBack)
                is Route.EditorPicks -> EditorPicksScreen(vm, onBack)
                is Route.NewEpisodes -> NewEpisodesScreen(vm, onBack)
                is Route.Queue -> QueueScreen(vm, onBack)
                is Route.Settings -> SettingsScreen(vm, onBack)
            }
        }
    }
}

/** 单集行的通用操作：打开详情 / 播放 / 加入队列 */
class EpisodeActions(private val vm: AppViewModel) {
    fun open(ep: Episode) = vm.push(Route.EpisodeDetail(ep))
    fun play(ep: Episode) = vm.player.play(ep)
    fun enqueue(ep: Episode) {
        vm.repo.enqueue(ep)
        vm.bumpQueue()
        vm.player.messages.value = "已加入播放队列：${ep.title}"
    }
}

@Composable
fun Placeholder(title: String, onBack: (() -> Unit)?) {
    ScreenHeader(title, onBack)
    EmptyBox("即将推出")
}
