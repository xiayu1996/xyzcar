package com.ryan.xyzcar

import com.ryan.xyzcar.data.AuthTokens
import com.ryan.xyzcar.data.api.XyzApi
import com.ryan.xyzcar.ui.screens.qrMatrix
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/**
 * 真实登录验证（不是常规单元测试）：只有 scripts/login-check.sh 设置了 XYZ_STEP 才会执行，平时自动跳过。
 * 和 App 用的是同一个 XyzApi，验证的是请求头、登录、token 刷新、数据接口这条链路。
 *
 * 输出里不打印 token，只打印是否成功和数量。
 */
class LoginCheck {
    private val step = System.getenv("XYZ_STEP")
    private val stateDir = File(System.getenv("XYZ_STATE_DIR") ?: ".logincheck")

    /** 文件版 token 存储，权限 600，目录已加入 .gitignore */
    private class FileTokens(private val file: File) : AuthTokens {
        private val p = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
        private fun put(k: String, v: String?) {
            if (v == null) p.remove(k) else p.setProperty(k, v)
            file.parentFile.mkdirs()
            file.outputStream().use { p.store(it, null) }
            runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
        }
        override var accessToken: String? get() = p.getProperty("access"); set(v) = put("access", v)
        override var refreshToken: String? get() = p.getProperty("refresh"); set(v) = put("refresh", v)
        override var uid: String? get() = p.getProperty("uid"); set(v) = put("uid", v)
        override val deviceId: String
            get() = p.getProperty("device") ?: java.util.UUID.randomUUID().toString().uppercase().also { put("device", it) }
        override val savedAt: Long get() = p.getProperty("savedAt")?.toLongOrNull() ?: 0L
        override fun save(access: String, refresh: String) { put("access", access); put("refresh", refresh); put("savedAt", System.currentTimeMillis().toString()) }
        override fun clear() { listOf("access", "refresh", "uid", "savedAt").forEach { p.remove(it) }; put("uid", null) }
    }

    /** 可用环境变量 XYZ_APP_VERSION / XYZ_APP_BUILD 临时覆盖伪装的 App 版本 */
    private fun api(tokens: AuthTokens) = XyzApi(
        tokens,
        System.getenv("XYZ_APP_VERSION")?.takeIf { it.isNotBlank() } ?: XyzApi.APP_VERSION,
        System.getenv("XYZ_APP_BUILD")?.takeIf { it.isNotBlank() } ?: XyzApi.APP_BUILD,
    ).also { log("伪装 App 版本：${System.getenv("XYZ_APP_VERSION") ?: XyzApi.APP_VERSION}（build ${System.getenv("XYZ_APP_BUILD") ?: XyzApi.APP_BUILD}）") }

    /** 最小 PNG 编码（8 位灰度）。安卓单测编译时没有 java.awt，所以手写 */
    private fun grayPng(w: Int, h: Int, white: (Int, Int) -> Boolean): ByteArray {
        val raw = java.io.ByteArrayOutputStream()
        for (y in 0 until h) { raw.write(0); for (x in 0 until w) raw.write(if (white(x, y)) 255 else 0) }
        val out = java.io.ByteArrayOutputStream()
        val data = java.io.DataOutputStream(out)
        fun chunk(type: String, body: ByteArray) {
            data.writeInt(body.size)
            val crc = java.util.zip.CRC32()
            val t = type.toByteArray()
            data.write(t); data.write(body); crc.update(t); crc.update(body)
            data.writeInt(crc.value.toInt())
        }
        data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = java.io.ByteArrayOutputStream().also { b ->
            java.io.DataOutputStream(b).apply { writeInt(w); writeInt(h); write(8); write(0); write(0); write(0); write(0) }
        }.toByteArray()
        chunk("IHDR", ihdr)
        val z = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(z).use { it.write(raw.toByteArray()) }
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun log(msg: String) = println("[登录验证] $msg")
    private fun mask(s: String?) = if (s.isNullOrEmpty()) "(空)" else "${s.take(2)}***（长度 ${s.length}）"

    /** 扫码登录：终端里打印二维码，同时存一张 PNG 并自动打开；用手机小宇宙 App 扫码确认 */
    @Test
    fun qr() {
        assumeTrue(step == "qr")
        val tokens = FileTokens(File(stateDir, "tokens.properties"))
        tokens.clear()
        val api = api(tokens)
        val deadline = System.currentTimeMillis() + 240_000 // 最多等 4 分钟（脚本整体 5 分钟上限）
        var round = 0
        while (System.currentTimeMillis() < deadline) {
            round++
            val qr = runBlocking { api.createLoginQr() }
            showQr(qr.url, round)
            var lastDiag = ""
            var errors = 0
            val qrDeadline = minOf(deadline, System.currentTimeMillis() + 120_000)
            var expired = false
            while (System.currentTimeMillis() < qrDeadline && !expired) {
                Thread.sleep(1000)
                val st = runCatching { runBlocking { api.pollLoginQr(qr.id) } }
                if (api.lastQrDiag != lastDiag) { log("服务端返回：${api.lastQrDiag}"); lastDiag = api.lastQrDiag }
                st.onFailure { e ->
                    errors++
                    log("⚠️ 查询出错（第 $errors 次）：${e.message}")
                    if (errors >= 5) throw e
                }
                when (st.getOrNull() ?: continue) {
                    XyzApi.QrState.WAITING -> {}
                    XyzApi.QrState.SCANNED -> {}
                    XyzApi.QrState.CONFIRMED -> {
                        log("✅ 手机已确认，拿到 access token ${mask(tokens.accessToken)}，refresh token ${mask(tokens.refreshToken)}")
                        checkWith(api, tokens); return
                    }
                    XyzApi.QrState.EXPIRED -> { log("二维码已失效，重新生成"); expired = true }
                }
            }
            if (!expired) log("这个二维码等了 2 分钟没有确认，重新生成")
        }
        error("4 分钟内没有完成扫码确认")
    }

    private fun showQr(url: String, round: Int) {
        val m = qrMatrix(url)
        // 终端：上下两个模块合成一个字符（▀▄█），浅色模块用实心块，适合深色背景的终端
        log("第 $round 个二维码，用手机小宇宙 App 扫码：")
        for (y in 0 until m.height step 2) {
            val line = StringBuilder()
            for (x in 0 until m.width) {
                val top = !m[x, y]
                val bottom = y + 1 < m.height && !m[x, y + 1] || y + 1 >= m.height
                line.append(if (top && bottom) '█' else if (top) '▀' else if (bottom) '▄' else ' ')
            }
            log(line.toString())
        }
        // PNG：终端显示不正常时用这张图扫
        val scale = 10
        val w = m.width * scale
        val h = m.height * scale
        val png = File(stateDir, "qr.png").apply { parentFile.mkdirs() }
        png.writeBytes(grayPng(w, h) { x, y -> !m[x / scale, y / scale] })
        log("二维码图片：${png.absolutePath}（已尝试自动打开）")
        if (System.getenv("XYZ_NO_OPEN") == null && System.getProperty("os.name").orEmpty().contains("Mac")) runCatching { ProcessBuilder("open", png.absolutePath).start() }
    }

    @Test
    fun check() {
        assumeTrue(step == "check")
        val tokens = FileTokens(File(stateDir, "tokens.properties"))
        require(tokens.isLoggedIn) { "还没有登录记录，请先完整跑一次 scripts/login-check.sh" }
        checkWith(api(tokens), tokens)
    }

    /** 只读：打印发现页（为你推荐 / 编辑精选）和三个榜单的前几条，确认内容真实可用 */
    @Test
    fun show() {
        assumeTrue(step == "show")
        val tokens = FileTokens(File(stateDir, "tokens.properties"))
        require(tokens.isLoggedIn) { "还没有登录记录，请先完整跑一次 scripts/login-check.sh" }
        val api = api(tokens)
        val n = System.getenv("XYZ_SHOW_N")?.toIntOrNull() ?: 5
        runBlocking {
            val (sections, more) = api.discovery(null)
            log("发现页第一页：${sections.size} 个区块${if (more != null) "（还有下一页）" else ""}")
            sections.forEach { sec ->
                log("【${sec.title}】${sec.episodes.size} 集 ${sec.podcasts.size} 个节目")
                sec.episodes.take(n).forEach { log("  · ${it.title} — ${it.podcastTitle}（${it.durationSec / 60} 分钟）${if (it.audioUrl.isNotEmpty()) "" else " ⚠️无音频地址"}") }
                sec.podcasts.take(n).forEach { log("  · 节目：${it.title}") }
            }
            var key = more
            var page = 1
            while (key != null && page < 6) { // 最多翻 5 页
                page++
                val (secN, next) = api.discovery(key)
                log("发现页第 $page 页：${secN.size} 个区块 ${secN.map { "${it.title}(${it.episodes.size + it.podcasts.size})" }}")
                if (secN.isEmpty()) api.discoveryShape(key).lines().filter { it.isNotBlank() }.forEach { log("第 $page 页结构 ${it.take(400)}") }
                key = next
            }
            // 可播放性：对第一集的音频地址发 Range 请求，只取前 1KB
            sections.firstOrNull()?.episodes?.firstOrNull()?.let { e ->
                val req = okhttp3.Request.Builder().url(e.audioUrl).header("Range", "bytes=0-1023").build()
                okhttp3.OkHttpClient().newCall(req).execute().use { r ->
                    log("音频可访问性（${e.title.take(20)}…）：HTTP ${r.code} ${r.header("Content-Type")} 总大小 ${r.header("Content-Range")?.substringAfter('/')}")
                }
            }
            val picks = api.editorPicks(null).items
            log("【编辑精选页】${picks.size} 集")
            picks.take(n).forEach { log("  · ${it.title} — ${it.podcastTitle}") }
            for ((cat, name) in listOf("HOT_EPISODES_IN_24_HOURS" to "最热榜", "SKYROCKET_EPISODES" to "锋芒榜", "NEW_STAR_EPISODES" to "新星榜")) {
                val t = api.topList(cat)
                log("【$name】${t.title}：${t.episodes.size} 集")
                t.episodes.take(n).forEachIndexed { i, e -> log("  ${i + 1}. ${e.title} — ${e.podcastTitle}") }
            }
        }
    }

    /**
     * 只读：导出一份公开内容快照给 debug 演示模式和界面截图用。
     * 只含发现页、榜单、编辑精选、分类、节目和单集等公开内容；订阅、历史、进度都是编的，不含账号数据和 token。
     */
    @Test
    fun dump() {
        assumeTrue(step == "dump")
        val tokens = FileTokens(File(stateDir, "tokens.properties"))
        require(tokens.isLoggedIn) { "还没有登录记录，请先完整跑一次 scripts/login-check.sh" }
        val api = api(tokens)
        val root = stateDir.absoluteFile.parentFile
        runBlocking {
            val sections = mutableListOf<com.ryan.xyzcar.data.DiscoverySection>()
            var key: String? = null
            repeat(4) {
                val (sec, next) = api.discovery(key)
                sections += sec
                if (next == null || next == key) return@repeat
                key = next
            }
            val tops = listOf("HOT_EPISODES_IN_24_HOURS", "SKYROCKET_EPISODES", "NEW_STAR_EPISODES").associateWith { api.topList(it).episodes }
            val picks = api.editorPicks(null).items
            val pids = (picks + tops.values.flatten()).map { it.pid }.distinct().take(8)
            val pods = pids.mapIndexed { i, pid ->
                api.podcast(pid).copy(subscribed = i < 6, starred = i < 2, hasUpdate = i in 1..3)
            }
            val podEps = pods.associate { p -> p.pid to api.episodes(p.pid, null).items.take(12).map { it.copy(progressSec = 0) } }
            // 「历史」和「单集详情」要 shownotes：取几集补全详情，进度编一个
            val detailed = picks.take(6).mapIndexed { i, e ->
                val full = api.episode(e.eid)
                full.copy(progressSec = if (i % 2 == 0) (full.durationSec * (0.2 + 0.15 * i)).toInt().coerceAtMost(full.durationSec - 60) else 0)
            }
            val snap = com.ryan.xyzcar.data.DemoSnapshot(
                podcasts = pods, episodes = detailed, sections = sections.distinctBy { it.title },
                topLists = tops, editorPicks = picks, categories = api.categories(),
                searchPresets = api.searchPresets(), podcastEpisodes = podEps,
            )
            val out = File(root, "app/src/debug/assets/demo_snapshot.json").apply { parentFile.mkdirs() }
            out.writeText(kotlinx.serialization.json.Json { prettyPrint = false; encodeDefaults = true }.encodeToString(com.ryan.xyzcar.data.DemoSnapshot.serializer(), snap))
            log("快照：${sections.size} 个区块、${pods.size} 个节目、${snap.editorPicks.size} 条精选、${tops.values.sumOf { it.size }} 条榜单 → ${out.relativeTo(root)}（${out.length() / 1024} KB）")

            // 截图测试用的封面（Paparazzi 不能联网）
            val covers = File(root, "app/src/test/resources/covers").apply { mkdirs() }
            val urls = (pods.map { it.coverUrl } + (snap.episodes + snap.editorPicks + tops.values.flatten() + podEps.values.flatten() + sections.flatMap { it.episodes }).map { it.coverUrl } +
                sections.flatMap { it.podcasts }.map { it.coverUrl }).filter { it.startsWith("http") }.distinct()
            val http = okhttp3.OkHttpClient()
            var ok = 0
            urls.forEach { u ->
                val f = File(covers, coverName(u))
                if (f.exists()) { ok++; return@forEach }
                runCatching {
                    http.newCall(okhttp3.Request.Builder().url(u).build()).execute().use { r -> if (r.isSuccessful) { f.writeBytes(r.body!!.bytes()); ok++ } }
                }
            }
            log("封面：$ok / ${urls.size} 张 → ${covers.relativeTo(root)}")
        }
    }

    /** 登录后的链路：拉数据 → 主动刷新 token → 用新 token 再拉数据 */
    private fun checkWith(api: XyzApi, tokens: AuthTokens) = runBlocking {
        val subs = api.subscriptions(null)
        log("✅ 订阅列表：第一页 ${subs.items.size} 个节目")
        val starred = runCatching { api.starredSubscriptions().size }
        log(starred.fold({ "✅ 星标订阅：$it 个" }, { "⚠️ 星标订阅读取失败：${it.message}" }))
        val history = api.history(null)
        log("✅ 收听历史：第一页 ${history.items.size} 集")
        history.items.firstOrNull()?.let { ep ->
            val p = api.progress(listOf(ep.eid))[ep.eid]
            log("✅ 读取进度：最近一集${if (p == null) "没有进度记录" else "已听 ${p / 60} 分钟"}")
        }

        val before = tokens.accessToken
        val refreshBefore = tokens.refreshToken
        api.refreshToken(before)
        log(if (tokens.accessToken != before) "✅ token 刷新成功（拿到新的 access token）" else "⚠️ token 刷新后 access token 没变")
        log(if (tokens.refreshToken != refreshBefore) "✅ refresh token 也换成了新的（滑动续期：每次刷新都会延长登录有效期）" else "⚠️ refresh token 没有更换")
        val again = api.subscriptions(null)
        log("✅ 用新 token 再次拉订阅：${again.items.size} 个节目")
        val (sections, _) = api.discovery(null)
        log("✅ 发现页：${sections.size} 个区块")
        if (sections.isEmpty() || System.getenv("XYZ_SHAPE") != null) api.discoveryShape().lines().filter { it.isNotBlank() }.forEach { log("发现页结构 $it") }
        // App 其他页面用到的只读接口：数量为 0 通常说明返回格式变了
        val ep = history.items.firstOrNull()
        val extra = listOf<Pair<String, suspend () -> Int>>(
            "最热榜" to { api.topList("HOT_EPISODES_IN_24_HOURS").episodes.size },
            "锋芒榜" to { api.topList("SKYROCKET_EPISODES").episodes.size },
            "新星榜" to { api.topList("NEW_STAR_EPISODES").episodes.size },
            "编辑精选" to { api.editorPicks(null).items.size },
            "新节目广场" to { api.newEpisodesSquare().size },
            "分类" to { api.categories().size },
            "分类下的节目" to { api.categories().firstOrNull()?.let { c -> api.categoryTabs(c.id).firstOrNull()?.let { t -> api.categoryPodcasts(c.id, t.id).items.size } } ?: 0 },
            "热门搜索词" to { api.searchPresets().size },
            "搜索「播客」" to { api.search("播客").first.let { it.podcasts.size + it.episodes.size } },
            "单集详情" to { if (ep == null) -1 else if (api.episode(ep.eid).eid == ep.eid) 1 else 0 },
            "节目详情" to { if (ep == null) -1 else if (api.podcast(ep.pid).pid == ep.pid) 1 else 0 },
            "节目单集列表" to { if (ep == null) -1 else api.episodes(ep.pid, null).items.size },
        )
        var bad = 0
        for ((name, f) in extra) {
            val r = runCatching { f() }
            r.onSuccess { n -> if (n > 0) log("✅ $name：$n") else if (n < 0) log("⏭ $name：没有可用的单集，跳过") else { bad++; log("⚠️ $name：0（返回格式可能变了）") } }
            r.onFailure { bad++; log("⚠️ $name 失败：${it.message}") }
        }
        if (System.getenv("XYZ_SHAPE") != null) api.newEpisodesSquareShape().lines().filter { it.isNotBlank() }.take(4).forEach { log("新节目广场结构 $it") }
        log(if (bad == 0) "全部通过" else "登录和核心链路通过；$bad 个页面接口需要排查")
    }
}

/** 封面文件名：URL 的 SHA-1，截图测试按同样规则查找 */
fun coverName(url: String): String =
    java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
