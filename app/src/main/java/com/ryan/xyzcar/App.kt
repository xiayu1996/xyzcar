package com.ryan.xyzcar

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.ryan.xyzcar.data.CarLab
import com.ryan.xyzcar.data.DebugLog
import com.ryan.xyzcar.data.LocalStore
import com.ryan.xyzcar.data.Repository
import com.ryan.xyzcar.data.TokenStore
import com.ryan.xyzcar.data.Updater
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import com.ryan.xyzcar.data.api.XyzApi

class App : Application(), ImageLoaderFactory {
    lateinit var tokens: TokenStore
    lateinit var local: LocalStore
    lateinit var api: XyzApi
    lateinit var repo: Repository
    lateinit var updater: Updater

    override fun onCreate() {
        super.onCreate()
        instance = this
        tokens = TokenStore(this)
        local = LocalStore(this) { tokens.activeId }
        api = XyzApi(tokens)
        repo = Repository(api, local, tokens)
        updater = Updater(
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
            File(cacheDir, "updates"),
        )
        DebugLog.init(this)
        CrashLog.install(this)
        watchNetwork()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { CarLab.onAppStart(this@App, local, BuildConfigInfo.VERSION) }
                .onFailure { DebugLog.kv("start", "snapshot_error", "error" to "${it.javaClass.simpleName}:${it.message.orEmpty()}") }
        }
    }

    /** 内存缓存 15%，比 Coil 默认的 20% 小 */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(64L * 1024 * 1024).build() }
        .crossfade(220)
        .respectCacheHeaders(false)
        .build()

    /** 车机上界面隐藏多半是在后台播放，放掉内存缓存，回来从磁盘缓存重新解码 */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            imageLoader.memoryCache?.clear()
            repo.trimMemory()
        }
    }

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
