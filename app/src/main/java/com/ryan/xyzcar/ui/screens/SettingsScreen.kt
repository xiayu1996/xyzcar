package com.ryan.xyzcar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ryan.xyzcar.BuildConfigInfo
import com.ryan.xyzcar.ui.AppViewModel
import com.ryan.xyzcar.ui.components.BigButton
import com.ryan.xyzcar.ui.components.Chip
import com.ryan.xyzcar.ui.components.ScreenHeader
import com.ryan.xyzcar.ui.components.SectionTitle
import com.ryan.xyzcar.ui.theme.Dims

/** 设置：界面缩放（实车密度未知时手动调）、退出登录 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: (() -> Unit)?) {
    ScreenHeader("设置", onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dims.gap)) {
        SectionTitle("界面缩放")
        Text("如果车机上文字和按钮偏大或偏小，在这里调整。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(0.7f, 0.8f, 0.9f, 1.0f, 1.15f, 1.3f).forEach { s ->
                Chip("${(s * 100).toInt()}%", selected = kotlin.math.abs(vm.uiScale - s) < 0.01f) { vm.updateScale(s) }
            }
        }
        SectionTitle("账号")
        Text("已登录。token 会自动续期，一般不需要重新登录。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BigButton("退出登录", secondary = true) { vm.logout() }
        SectionTitle("关于")
        Text("小宇宙车机版 ${BuildConfigInfo.VERSION} · 个人自用，接口参考 ultrazg/xyz", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
