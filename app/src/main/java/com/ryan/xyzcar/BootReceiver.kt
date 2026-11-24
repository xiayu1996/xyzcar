package com.ryan.xyzcar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ryan.xyzcar.data.DebugLog

/** 只为开机拉起进程，让 [App.onCreate] 重新加入 3 分屏列表，车机重启后不用先手动打开本应用 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DebugLog.kv("start", "boot", "action" to intent.action)
    }
}
