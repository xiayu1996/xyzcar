package com.ryan.xyzcar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ryan.xyzcar.data.DebugLog

/**
 * 开机广播：只为让系统拉起进程。真正的工作在 [App.onCreate] 里（CarLab.onAppStart 把本应用加入 3 分屏列表），
 * 这样车机重启后不用先手动打开一次本应用，3 分屏就能选到。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DebugLog.kv("start", "boot", "action" to intent.action)
    }
}
