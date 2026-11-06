package com.ryan.xyzcar

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.ryan.xyzcar.data.LocalStore
import com.ryan.xyzcar.data.Repository
import com.ryan.xyzcar.data.TokenStore
import com.ryan.xyzcar.data.Updater
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import com.ryan.xyzcar.data.api.XyzApi

class App : Application() {
    lateinit var tokens: TokenStore
    lateinit var local: LocalStore
    lateinit var api: XyzApi
    lateinit var repo: Repository
    lateinit var updater: Updater

    override fun onCreate() {
        super.onCreate()
        instance = this
        tokens = TokenStore(this)
        local = LocalStore(this)
        api = XyzApi(tokens)
        repo = Repository(api, local, tokens)
        updater = Updater(
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
            File(cacheDir, "updates"),
        )
        CrashLog.install(this)
        watchNetwork()
    }

    /** 网络恢复时补发之前上报失败的进度（F6） */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (local.pendingProgress.isNotEmpty()) scope.launch { runCatching { repo.flushPendingProgress() } }
                }
            })
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
