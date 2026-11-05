package com.ryan.xyzcar.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ryan.xyzcar.data.Page
import com.ryan.xyzcar.data.Podcast
import com.ryan.xyzcar.ui.AppViewModel
import com.ryan.xyzcar.ui.Route
import com.ryan.xyzcar.ui.components.AutoLoadList
import com.ryan.xyzcar.ui.components.EmptyBox
import com.ryan.xyzcar.ui.components.ErrorBox
import com.ryan.xyzcar.ui.components.PodcastRow
import com.ryan.xyzcar.ui.components.RoundIconButton
import com.ryan.xyzcar.ui.components.ScreenHeader
import com.ryan.xyzcar.ui.components.SkeletonRows
import com.ryan.xyzcar.ui.theme.Dims

/** 刷新按钮代替下拉刷新：车机上下拉手势不稳，按钮更好点 */
private class SubsState { var offline by mutableStateOf(false); var force = false }

/** F3 我的订阅：星标置顶，刷新按钮 */
@Composable
fun SubscriptionsScreen(vm: AppViewModel) {
    val extra = vm.holder("subs-extra") { SubsState() }
    val h = vm.pagedHolder<Podcast>("subs", { it.pid }) { key ->
        if (key == null) {
            val first = vm.repo.subscriptionsFirstPage(extra.force)
            extra.force = false
            extra.offline = first.fromDisk
            Page(first.items, first.loadMoreKey)
        } else {
            val more = vm.repo.subscriptionsMore(key, emptySet())
            Page(more.items, more.loadMoreKey)
        }
    }
    LaunchedEffect(Unit) { h.ensureLoaded() }

    ScreenHeader("我的订阅", onBack = null) {
        RoundIconButton(Icons.Filled.Refresh, "刷新", enabled = !h.loading) {
            extra.force = true
            h.refresh()
        }
    }
    if (extra.offline) {
        Text("网络不可用，显示的是上次缓存的订阅", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    when {
        !h.loadedOnce && h.loading -> SkeletonRows()
        h.error != null && h.items.isEmpty() -> ErrorBox(h.error!!) { h.refresh() }
        h.loadedOnce && h.items.isEmpty() -> EmptyBox("还没有订阅节目，去「发现」看看")
        else -> AutoLoadList(h.listState, h.loading, h.end, h::loadMore) {
            items(h.items, key = { it.pid }) { p ->
                PodcastRow(p) { vm.push(Route.PodcastDetail(p.pid)) }
            }
            if (h.error != null) item { ErrorBox(h.error!!) { h.loadMore() } }
        }
    }
    Spacer(Modifier.width(Dims.gap))
}
