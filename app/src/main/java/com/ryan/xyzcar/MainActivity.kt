package com.ryan.xyzcar

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.ryan.xyzcar.data.CarLab
import com.ryan.xyzcar.data.DebugLog
import com.ryan.xyzcar.ui.AppViewModel
import com.ryan.xyzcar.ui.MainLayout
import com.ryan.xyzcar.ui.screens.LoginScreen
import com.ryan.xyzcar.ui.theme.XyzCarTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        if (vm.loggedIn) vm.player.onForeground()
    }

    /** Firebase Test Lab 设备上这个系统设置为 "true"：自动进演示模式，避免 Robo 往登录框乱填号码发短信 */
    private fun inTestLab(): Boolean =
        runCatching { android.provider.Settings.System.getString(contentResolver, "firebase.test.lab") == "true" }.getOrDefault(false)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        DebugLog.log("launch", CarLab.describe(this, intent, "onNewIntent"))
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        DebugLog.log("window", "multi_window_changed ${CarLab.window(this)}")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        DebugLog.log("window", "config_changed ${CarLab.window(this)}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.log("launch", CarLab.describe(this, intent, if (savedInstanceState == null) "onCreate" else "onCreate_recreated"))
        enableEdgeToEdge()
        if (BuildConfig.DEBUG && (intent.getBooleanExtra("demo", false) || inTestLab())) vm.enableDemo()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            XyzCarTheme(vm.themeMode) {
                // 根节点用 Surface，保证深色模式下默认文字是浅色
                androidx.compose.material3.Surface(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                ) {
                    if (vm.loggedIn) MainLayout(vm) else LoginScreen(vm)
                }
            }
        }
    }
}
