package com.ryan.xyzcar.data.api

import com.ryan.xyzcar.data.Category
import com.ryan.xyzcar.data.DiscoverySection
import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.data.Page
import com.ryan.xyzcar.data.PendingProgress
import com.ryan.xyzcar.data.Podcast
import com.ryan.xyzcar.data.SearchResult
import com.ryan.xyzcar.data.AuthTokens
import com.ryan.xyzcar.data.ThemeColor
import com.ryan.xyzcar.data.TopList
import com.ryan.xyzcar.data.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : IOException(message)

/** 登录态失效且刷新失败：需要重新登录 */
class AuthExpiredException : IOException("登录已过期，请重新登录")

/**
 * 小宇宙接口客户端。接口定义参考 ultrazg/xyz（handlers 目录下的 Go 代码），
 * 所有接口调用集中在这个文件里，小宇宙改接口时只改这里。
 */
/**
 * appVersion / appBuild 默认用 [APP_VERSION] / [APP_BUILD]；服务端会拒绝太旧的版本（「请升级到新版本后重试」），
 * 登录验证工具可以用环境变量临时覆盖，不用重新打包。
 */
class XyzApi(
    private val tokens: AuthTokens,
    private val appVersion: String = APP_VERSION,
    private val appBuild: String = APP_BUILD,
    /** 单元测试里换成本地假服务器 */
    private val base: String = BASE,
    private val webBase: String = WEB_BASE,
    /** 单调时钟（纳秒），测试里可以替换 */
    private val clock: () -> Long = System::nanoTime,
) {
    @Volatile private var lastRefreshAttempt = 0L


    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 播放器用的 HTTP 客户端（读超时放宽） */
    val mediaHttp: OkHttpClient = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val refreshLock = Mutex()

    // ---------- 公共请求 ----------

    private fun localTime(): String =
        OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun Request.Builder.iosHeaders(
        abtest: String? = "{\"old_user_discovery_feed\":\"enable\"}",
        acceptLanguage: String = "zh-Hant-HK;q=1.0, zh-Hans-CN;q=0.9",
        withTime: Boolean = true,
    ): Request.Builder {
        header("User-Agent", "Xiaoyuzhou/$appVersion (build:$appBuild; iOS $OS_VERSION)")
        header("Market", "AppStore")
        header("App-BuildNo", appBuild)
        header("OS", "ios")
        header("Manufacturer", "Apple")
        header("BundleID", "app.podcast.cosmos")
        if (abtest != null) header("abtest-info", abtest)
        header("Accept-Language", acceptLanguage)
        header("Model", MODEL)
        header("app-permissions", "4")
        header("Accept", "*/*")
        header("App-Version", appVersion)
        header("WifiConnected", "true")
        header("OS-Version", OS_VERSION)
        header("x-jike-device-id", tokens.deviceId)
        header("x-custom-xiaoyuzhou-app-dev", "")
        if (withTime) {
            header("Local-Time", localTime())
            header("Timezone", "Asia/Shanghai")
        }
        tokens.accessToken?.let { header("x-jike-access-token", it) }
        return this
    }

    private data class Call(
        val method: String,
        val path: String,
        val body: JsonElement? = null,
        val abtest: String? = "{\"old_user_discovery_feed\":\"enable\"}",
        val acceptLanguage: String = "zh-Hant-HK;q=1.0, zh-Hans-CN;q=0.9",
        val extraHeaders: Map<String, String> = emptyMap(),
    )

    private fun build(call: Call): Request {
        val b = Request.Builder().url(base + call.path).iosHeaders(call.abtest, call.acceptLanguage)
        call.extraHeaders.forEach { (k, v) -> b.header(k, v) }
        if (call.method == "GET") {
            b.get()
        } else {
            // 用 ByteArray 构造，保证 Content-Type 精确为 application/json（不追加 charset）
            val bytes = (call.body ?: JsonObject(emptyMap())).toString().toByteArray()
            b.post(bytes.toRequestBody(JSON_TYPE))
        }
        return b.build()
    }

    /** 发请求；遇到 401 先刷新 token 再重试一次 */
    private suspend fun exec(call: Call): JsonObject = withContext(Dispatchers.IO) {
        if (!tokens.isLoggedIn) throw AuthExpiredException()
        val sentToken = tokens.accessToken
        val first = http.newCall(build(call)).execute()
        if (first.code == 401) {
            first.close()
            refreshToken(sentToken)
            val second = http.newCall(build(call)).execute()
            if (second.code == 401) {
                second.close()
                // 刚刷新成功还被拒：登录态本身是好的（刷新接口认它），是这个接口不认，不能因此退出登录
                throw ApiException(401, "这个功能暂时用不了（服务端拒绝，HTTP 401）")
            }
            return@withContext parse(second)
        }
        parse(first)
    }

    private fun parse(resp: Response): JsonObject = resp.use {
        val text = it.body?.string().orEmpty()
        if (!it.isSuccessful) throw ApiException(it.code, "请求失败（HTTP ${it.code}）")
        if (text.isBlank()) return JsonObject(emptyMap())
        val el = runCatching { json.parseToJsonElement(text) }.getOrElse { throw ApiException(it.hashCode(), "返回内容无法解析") }
        el as? JsonObject ?: JsonObject(mapOf("data" to el))
    }

    // ---------- 登录 ----------

    // ---------- 扫码登录 ----------
    //
    // 小宇宙统一登录页（accounts.xiaoyuzhoufm.com）的「扫码登录」：车机显示二维码，手机小宇宙 App 扫码确认。
    // 2026-11 实测：短信验证码接口（App 的 /v1/auth/sendCode、网页的 /v1/auth/send-code）都要求阿里云人机验证参数
    // captchaVerifyParam，只能在官方页面里由真人完成，所以不用短信登录。

    data class LoginQr(val id: String, val url: String)

    enum class QrState { WAITING, SCANNED, CONFIRMED, EXPIRED }

    private fun webPost(path: String, body: JsonObject): Request =
        Request.Builder().url(webBase + path)
            .header("x-midway-app-id", WEB_APP_ID)
            .header("Origin", "https://accounts.xiaoyuzhoufm.com")
            .header("Referer", "https://accounts.xiaoyuzhoufm.com/")
            .header("x-jike-allow-app-token-in-cookie", "true")
            .post(body.toString().toByteArray().toRequestBody(JSON_TYPE))
            .build()

    /** 接口报错时优先用服务端给的提示文案（toast） */
    private fun failure(resp: Response, text: String, what: String): ApiException {
        val toast = runCatching { json.parseToJsonElement(text).str("toast") }.getOrDefault("")
        return ApiException(resp.code, toast.ifEmpty { "$what（HTTP ${resp.code}）" })
    }

    /** 申请一个登录二维码，二维码内容是 url，有效期几分钟 */
    suspend fun createLoginQr(): LoginQr = withContext(Dispatchers.IO) {
        http.newCall(webPost("/v1/auth/qrcode/create", buildJsonObject { put("clientId", WEB_CLIENT_ID) })).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw failure(resp, text, "获取登录二维码失败")
            val o = json.parseToJsonElement(text) as JsonObject
            LoginQr(o.str("id"), o.str("url")).also { if (it.id.isEmpty() || it.url.isEmpty()) throw ApiException(resp.code, "登录二维码格式不对") }
        }
    }

    /** 最近一次查询扫码状态的诊断信息（HTTP 状态码、服务端 code / 提示、带回了哪些头和 cookie 名）。不含 token 值 */
    @Volatile var lastQrDiag: String = ""
        private set

    /**
     * 查一次扫码状态。官方网页把 CONFIRMED 和 USED 都当作登录成功，token 跟着这次响应回来
     * （响应头 / 响应体 / Set-Cookie），所以两种状态都要取 token。
     * 只有 401 + code 21（官方判定的过期）或 400「无效二维码」才算失效；其他错误抛出，由调用方重试。
     */
    suspend fun pollLoginQr(id: String): QrState = withContext(Dispatchers.IO) {
        http.newCall(webPost("/v1/auth/qrcode/login", buildJsonObject { put("id", id) })).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            val status = o.str("status")
            val code = o?.get("code")?.toString().orEmpty()
            lastQrDiag = buildString {
                append("HTTP ${resp.code}")
                if (status.isNotEmpty()) append(" status=$status")
                if (code.isNotEmpty()) append(" code=$code")
                o.str("toast").takeIf { it.isNotEmpty() }?.let { append(" toast=$it") }
                o.str("message").takeIf { it.isNotEmpty() }?.let { append(" message=$it") }
                val tokenHeaders = resp.headers.names().filter { it.startsWith("x-jike", ignoreCase = true) }
                if (tokenHeaders.isNotEmpty()) append(" headers=$tokenHeaders")
                val cookieNames = resp.headers("Set-Cookie").map { it.substringBefore('=').trim() }
                if (cookieNames.isNotEmpty()) append(" cookies=$cookieNames")
                o?.keys?.filter { it != "status" && it != "code" && it != "toast" && it != "message" }?.takeIf { it.isNotEmpty() }
                    ?.let { append(" bodyKeys=$it") }
            }
            if ((resp.code == 401 && code == "21") || (resp.code == 400 && o.str("toast") == "无效二维码")) return@use QrState.EXPIRED
            if (!resp.isSuccessful) throw failure(resp, text, "查询扫码状态失败")
            when (status) {
                "WAITTING", "WAITING" -> QrState.WAITING // 服务端拼写就是 WAITTING
                "SCANNED" -> QrState.SCANNED
                "CONFIRMED", "USED" -> {
                    val (access, refresh) = tokensFrom(resp, o)
                    if (access.isNotEmpty() && refresh.isNotEmpty()) {
                        tokens.save(access, refresh)
                        QrState.CONFIRMED
                    } else if (tokens.isLoggedIn) {
                        QrState.CONFIRMED
                    } else {
                        throw ApiException(resp.code, "手机已确认，但响应里没有登录凭证（$lastQrDiag）")
                    }
                }
                else -> throw ApiException(resp.code, "未知的扫码状态（$lastQrDiag）")
            }
        }
    }

    /** token 依次从响应头、响应体、Set-Cookie 里找 */
    private fun tokensFrom(resp: Response, o: JsonObject?): Pair<String, String> {
        val cookies = resp.headers("Set-Cookie").associate { c ->
            val kv = c.substringBefore(';'); kv.substringBefore('=').trim() to kv.substringAfter('=', "").trim()
        }
        fun find(name: String) = resp.header(name).orEmpty()
            .ifEmpty { o.str(name) }.ifEmpty { o.obj("data").str(name) }.ifEmpty { cookies[name].orEmpty() }
        return find("x-jike-access-token") to find("x-jike-refresh-token")
    }

    /** 刷新 token。多个请求同时 401 时只刷新一次 */
    /**
     * 刷新 token。频率控制：
     * - 只在服务端对请求返回 401（access token 真过期）时才刷新，不定时刷新、不提前刷新。官方网页端也是这样做的。
     *   正常使用时大约每 20–30 分钟一次（access token 实测有效期 ≥ 21 分钟），App 不用时一次都不刷新。
     * - 多个请求同时 401 只刷新一次（锁 + 检查 token 是否已被别人换掉）。
     * - 两次刷新请求至少间隔 [MIN_REFRESH_INTERVAL_MS]：刷新后紧接着又 401（例如某个接口本身就不认这个 token），
     *   或服务端 5xx / 断网时，不会每个请求都去刷新。用单调时钟，车机改时间不影响。
     */
    suspend fun refreshToken(staleAccess: String?) = refreshLock.withLock {
        if (tokens.accessToken != staleAccess && tokens.accessToken != null) return@withLock // 别人已经刷新过
        val since = clock() - lastRefreshAttempt
        if (lastRefreshAttempt != 0L && since < MIN_REFRESH_INTERVAL_MS * 1_000_000) {
            throw ApiException(429, "刚刷新过登录态，请稍后再试")
        }
        lastRefreshAttempt = clock()
        withContext(Dispatchers.IO) {
            val refresh = tokens.refreshToken ?: throw AuthExpiredException()
            // 2026-11 实测：扫码登录拿到的 token 走 App 接口刷新即可，每次刷新会同时换新 refresh token（滑动续期）。
            // 只有服务端明确 401 才算登录过期；5xx、网络错误都是暂时的，保留登录态
            val req = Request.Builder().url("$base/app_auth_tokens.refresh")
                .iosHeaders(abtest = null, withTime = false)
                .header("x-jike-refresh-token", refresh)
                .post("null".toByteArray().toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code == 401) throw AuthExpiredException()
                if (!resp.isSuccessful) throw ApiException(resp.code, "刷新登录态失败（HTTP ${resp.code}），稍后会自动重试")
                val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                val (access, newRefresh) = tokensFrom(resp, o)
                if (access.isEmpty()) throw ApiException(resp.code, "刷新登录态失败：响应里没有新的 token，稍后会自动重试")
                tokens.save(access, newRefresh.ifEmpty { refresh })
            }
        }
    }

    // ---------- 订阅 ----------

    /** 订阅列表。sortBy=latestEpisodePubDate：按最新单集时间倒序（2026-11 实测可用，和官方「我的订阅」默认排序一致） */
    suspend fun subscriptions(loadMoreKey: JsonElement? = null, sortBy: String = "latestEpisodePubDate"): Page<Podcast> {
        val body = buildJsonObject {
            put("limit", "20"); put("sortOrder", "desc"); put("sortBy", sortBy)
            if (loadMoreKey != null && loadMoreKey !is JsonNull) put("loadMoreKey", loadMoreKey)
        }
        val r = exec(Call("POST", "/v1/subscription/list", body))
        return Page(r.arr("data").mapObj { parsePodcast(it).copy(subscribed = true) }, r["loadMoreKey"].nonNull())
    }

    suspend fun starredSubscriptions(): List<Podcast> =
        exec(Call("POST", "/v1/subscription-star/list")).arr("data").mapObj { parsePodcast(it).copy(subscribed = true, starred = true) }

    suspend fun updateSubscription(pid: String, on: Boolean): Podcast? {
        val r = exec(
            Call(
                "POST", "/v1/subscription/update",
                buildJsonObject { put("pid", pid); put("mode", if (on) "ON" else "OFF") },
                abtest = "{}", acceptLanguage = "zh-Hans-CN;q=1.0, zh-Hant-TW;q=0.9",
            ),
        )
        return (r["data"] as? JsonObject)?.let { parsePodcast(it) }
    }

    suspend fun updateStar(pid: String, star: Boolean): Podcast? {
        val r = exec(Call("POST", "/v1/subscription-star/update", buildJsonObject { put("pid", pid); put("withStar", star) }))
        return (r["data"] as? JsonObject)?.let { parsePodcast(it) }
    }

    // ---------- 节目与单集 ----------

    suspend fun podcast(pid: String): Podcast =
        parsePodcast(exec(Call("GET", "/v1/podcast/get?pid=$pid")).obj("data") ?: throw ApiException(0, "节目不存在"))

    suspend fun episodes(pid: String, loadMoreKey: JsonElement? = null, order: String = "desc"): Page<Episode> {
        val body = buildJsonObject {
            put("limit", "20"); put("pid", pid); put("order", order)
            if (loadMoreKey != null && loadMoreKey !is JsonNull) put("loadMoreKey", loadMoreKey)
        }
        val r = exec(Call("POST", "/v1/episode/list", body))
        return Page(r.arr("data").mapObj { parseEpisode(it) }, r["loadMoreKey"].nonNull())
    }

    suspend fun episode(eid: String): Episode =
        parseEpisode(exec(Call("GET", "/v1/episode/get?eid=$eid")).obj("data") ?: throw ApiException(0, "单集不存在"))

    // ---------- 播放进度 ----------

    /** eid → 已播放秒数 */
    suspend fun progress(eids: List<String>): Map<String, Int> {
        if (eids.isEmpty()) return emptyMap()
        val body = buildJsonObject { put("eids", JsonArray(eids.map { JsonPrimitive(it) })) }
        val r = exec(Call("POST", "/v1/playback-progress/list", body, abtest = null))
        return r.arr("data").mapObj { it.str("eid") to it.int("progress") }.toMap()
    }

    /** eid → (已播放秒数, playedAt ISO 时间) */
    suspend fun progressWithTime(eids: List<String>): Map<String, Pair<Int, String>> {
        if (eids.isEmpty()) return emptyMap()
        val body = buildJsonObject { put("eids", JsonArray(eids.map { JsonPrimitive(it) })) }
        val r = exec(Call("POST", "/v1/playback-progress/list", body, abtest = null))
        return r.arr("data").mapObj { it.str("eid") to (it.int("progress") to it.str("playedAt")) }.toMap()
    }

    suspend fun updateProgress(items: List<PendingProgress>) {
        if (items.isEmpty()) return
        val body = buildJsonObject {
            put("data", JsonArray(items.map {
                buildJsonObject { put("pid", it.pid); put("eid", it.eid); put("progress", it.progressSec); put("playedAt", it.playedAt) }
            }))
        }
        exec(Call("POST", "/v1/playback-progress/update", body))
    }

    // ---------- 订阅更新（官方「订阅 → 更新」） ----------

    /** 订阅节目的新单集，按发布时间倒序；单集带 isPlayed / isFinished。loadMoreKey 是 {pubDate, id} */
    suspend fun inbox(loadMoreKey: JsonElement? = null): Page<Episode> {
        val body = buildJsonObject { put("limit", "20"); if (loadMoreKey != null && loadMoreKey !is JsonNull) put("loadMoreKey", loadMoreKey) }
        val r = exec(Call("POST", "/v1/inbox/list", body))
        return Page(r.arr("data").mapObj { parseEpisode(it) }, r["loadMoreKey"].nonNull())
    }

    // ---------- 账号 ----------

    /** 当前账号的昵称和头像。返回里还有手机号等字段，这里只取需要的三项 */
    suspend fun profile(): User = XyzParser.parseUser(exec(Call("GET", "/v1/profile/get")).obj("data"))

    // ---------- 收听历史 ----------

    suspend fun history(loadMoreKey: JsonElement? = null): Page<Episode> {
        val body = buildJsonObject { if (loadMoreKey != null && loadMoreKey !is JsonNull) put("loadMoreKey", loadMoreKey) }
        val r = exec(Call("POST", "/v1/episode-played/list-history", body, abtest = null))
        return Page(r.arr("data").mapObj { it.obj("episode")?.let { e -> parseEpisode(e) } }.filterNotNull(), r["loadMoreKey"].nonNull())
    }

    // ---------- 发现 ----------

    /** 发现页一屏：返回区块和下一段的 token */
    suspend fun discovery(loadMoreKey: String? = null): Pair<List<DiscoverySection>, String?> {
        val body = buildJsonObject { put("returnAll", "false"); if (loadMoreKey != null) put("loadMoreKey", loadMoreKey) }
        val r = exec(Call("POST", "/v1/discovery-feed/list", body))
        val sections = XyzParser.parseDiscovery(r)
        return sections to (r["loadMoreKey"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }
    }

    /** 诊断用：只描述发现页返回的结构（类型、字段名），不含内容 */
    suspend fun discoveryShape(loadMoreKey: String? = null): String = shapeOf(exec(Call("POST", "/v1/discovery-feed/list",
        buildJsonObject { put("returnAll", "false"); if (loadMoreKey != null) put("loadMoreKey", loadMoreKey) })))

    /** 诊断用（只给登录验证工具的 probe 步骤用）：发任意只读请求，返回原始 JSON */
    suspend fun rawForProbe(method: String, path: String, body: JsonObject?): JsonObject = exec(Call(method, path, body))

    /** 诊断用：新节目广场的返回结构 */
    suspend fun newEpisodesSquareShape(): String =
        shapeOf(exec(Call("POST", "/v1/pilot-discovery/list", abtest = "{}", acceptLanguage = "zh-Hant-CN;q=1.0, zh-Hans-HK;q=0.9")))

    private fun shapeOf(r: JsonObject): String {
        fun shape(e: JsonElement?, depth: Int): String = when {
            e == null || e is JsonNull -> "null"
            e is JsonArray -> "[${e.size}]" + if (depth > 0 && e.isNotEmpty()) shape(e[0], depth - 1) else ""
            e is JsonObject -> if (depth > 0) e.entries.joinToString(",", "{", "}") { (k, v) ->
                if (k == "type") "type=${(v as? JsonPrimitive)?.content}" else "$k:" + shape(v, depth - 1)
            } else "{${e.keys.size} keys}"
            else -> "v"
        }
        return buildString {
            append("顶层 ").append(r.keys).append('\n')
            r.arr("data").forEachIndexed { i, it -> append("data[$i] ").append(shape(it, 5)).append('\n') }
        }
    }

    suspend fun topList(category: String): TopList {
        val d = exec(Call("GET", "/v1/top-list/get?category=$category")).obj("data")
        return TopList(
            title = d.str("title"),
            category = category,
            episodes = d.arr("items").mapObj { it.obj("item")?.let { e -> parseEpisode(e) } }.filterNotNull(),
            podcasts = emptyList(),
        )
    }

    suspend fun editorPicks(loadMoreKey: JsonElement? = null): Page<Episode> {
        val body = buildJsonObject { if (loadMoreKey != null && loadMoreKey !is JsonNull) put("loadMoreKey", loadMoreKey) }
        val r = exec(
            Call(
                "POST", "/v1/editor-pick/list-history", body, abtest = "{}",
                acceptLanguage = "zh-Hant-CN;q=1.0, zh-Hans-HK;q=0.9",
                extraHeaders = mapOf("X-Online-Host" to "api.xiaoyuzhoufm.com"),
            ),
        )
        val eps = r.arr("data").flatMap { day -> (day as? JsonObject).arr("picks").mapObj { p -> p.obj("episode")?.let { parseEpisode(it) } }.filterNotNull() }
        return Page(eps, r["loadMoreKey"].nonNull())
    }

    suspend fun newEpisodesSquare(): List<Episode> {
        val r = exec(Call("POST", "/v1/pilot-discovery/list", abtest = "{}", acceptLanguage = "zh-Hant-CN;q=1.0, zh-Hans-HK;q=0.9"))
        return r.arr("data").mapObj { it.obj("episode")?.let { e -> parseEpisode(e) } }.filterNotNull()
    }

    suspend fun categories(): List<Category> = XyzParser.parseCategories(exec(Call("POST", "/v1/category/list-all", abtest = null)))

    /**
     * 分类下的推荐单集（官方「推荐」页按分类切换的卡片）。tab：RECOMMEND 为你推荐 / HOT 近期热门 / ALL 全部。
     * 2026-11 实测每个 item 是 {podcast, episode}，一次 10 条，没有 loadMoreKey。
     */
    suspend fun categoryEpisodes(categoryId: String, tab: String = "RECOMMEND"): List<Episode> {
        val body = buildJsonObject { put("categoryId", categoryId); put("tab", tab); put("omitSubscribed", false) }
        return XyzParser.parseCategoryEpisodes(exec(Call("POST", "/v1/category/podcast/list-by-tab", body, abtest = null)))
    }

    /** 「为你推荐」换一批：每次返回 3 集新的推荐（只在用户点「换一批」时调用） */
    suspend fun refreshRecommend(): List<Episode> {
        val r = exec(Call("POST", "/v1/discovery-collection/refresh-episode-recommend"))
        return when (val d = r["data"]) {
            is JsonObject -> XyzParser.parseModule(d)?.episodes.orEmpty()
            is JsonArray -> d.mapObj { XyzParser.parseModule(it) }.flatMap { it.episodes }
            else -> emptyList()
        }
    }

    suspend fun categoryTabs(categoryId: String): List<Category> =
        exec(Call("POST", "/v1/category/podcast/list-tabs", buildJsonObject { put("categoryId", categoryId) }, abtest = null))
            .arr("data").mapObj { Category(it.str("id"), it.str("name")) }

    suspend fun categoryPodcasts(categoryId: String, tab: String, loadMoreKey: JsonElement? = null): Page<Podcast> {
        val body = buildJsonObject {
            put("categoryId", categoryId); put("tab", tab); put("omitSubscribed", false)
            if (loadMoreKey != null && loadMoreKey !is JsonNull) put("loadMoreKey", loadMoreKey)
        }
        val r = exec(Call("POST", "/v1/category/podcast/list-by-tab", body, abtest = null))
        return Page(r.arr("data").mapObj { it.obj("podcast")?.let { p -> parsePodcast(p) } }.filterNotNull(), r["loadMoreKey"].nonNull())
    }

    // ---------- 搜索 ----------

    suspend fun search(keyword: String, type: String = "ALL", loadMoreKey: JsonElement? = null): Pair<SearchResult, JsonElement?> {
        val body = buildJsonObject {
            put("limit", "20"); put("sourcePageName", "4"); put("currentPageName", "4")
            put("type", type); put("keyword", keyword)
            if (loadMoreKey != null && loadMoreKey !is JsonNull) put("loadMoreKey", loadMoreKey)
        }
        val r = exec(Call("POST", "/v1/search/create", body))
        return XyzParser.parseSearch(r) to r["loadMoreKey"].nonNull()
    }

    suspend fun searchPresets(): List<String> =
        exec(Call("GET", "/v1/search/get-preset", abtest = null)).arr("data").mapObj { it.str("text").takeIf { t -> t.isNotEmpty() } }.filterNotNull()

    fun parsePodcast(o: JsonObject): Podcast = XyzParser.parsePodcast(o)
    fun parseEpisode(o: JsonObject): Episode = XyzParser.parseEpisode(o)

    companion object {
        const val BASE = "https://api.xiaoyuzhoufm.com"
        const val WEB_BASE = "https://web-api.xiaoyuzhoufm.com"
        /** 两次刷新请求之间至少隔 60 秒（不管上次成功还是失败），防止异常情况下高频刷新 */
        const val MIN_REFRESH_INTERVAL_MS = 60_000L
        /** 统一登录页用的应用标识（取自 accounts.xiaoyuzhoufm.com 前端代码） */
        const val WEB_APP_ID = "v6worU4NnWyL"
        const val WEB_CLIENT_ID = "xyz-web"
        /** 伪装的 iOS 版本：取自 2026-08 仍在维护的开源客户端里的真实版本 / 构建号组合 */
        const val APP_VERSION = "2.98.0"
        const val APP_BUILD = "2908"
        const val OS_VERSION = "26.2.1"
        const val MODEL = "iPhone18,1"
        private val JSON_TYPE = "application/json".toMediaType()
    }
}

/** 返回结构解析：纯函数，方便单元测试 */
object XyzParser {
    /**
     * 发现页。2026-11 实测返回的 data 是一组卡片：
     * DISCOVERY_HEADER（顶部入口，跳过）、DISCOVERY_EPISODE_RECOMMEND（data 是单个模块对象：title + target[].episode）、
     * EDITOR_PICK（data.picks[].episode）、第二页的 TOP_LIST（data[] 每个榜单 items[].item）和 CATEGORY_ENTRANCE（跳过）；旧版的 DISCOVERY_COLLECTION（data 是模块数组）、NEW_POWER 也兼容。
     */
    fun parseDiscovery(r: JsonObject): List<DiscoverySection> {
        val sections = mutableListOf<DiscoverySection>()
        r.arr("data").forEach { item ->
            val o = item as? JsonObject ?: return@forEach
            val data = o["data"]
            when (o.str("type")) {
                "DISCOVERY_HEADER", "CATEGORY_ENTRANCE" -> {} // 顶部入口、分类入口：App 里有单独的分类页
                "TOP_LIST" -> (data as? JsonArray)?.forEach { t ->
                    val tl = t as? JsonObject ?: return@forEach
                    val eps = tl.arr("items").mapObj { it.obj("item")?.let(::parseEpisode) }.filterNotNull()
                    if (eps.isNotEmpty()) sections += DiscoverySection(tl.str("title").ifEmpty { "榜单" }, episodes = eps)
                }
                "NEW_POWER" -> {
                    val pods = o.obj("data").arr("items").mapObj { parsePodcast(it) }
                    if (pods.isNotEmpty()) sections += DiscoverySection("新节目", podcasts = pods)
                }
                "EDITOR_PICK" -> {
                    val eps = o.obj("data").arr("picks").mapObj { it.obj("episode")?.let(::parseEpisode) }.filterNotNull()
                    if (eps.isNotEmpty()) sections += DiscoverySection("编辑精选", episodes = eps)
                }
                // 其他类型：data 是模块数组或单个模块对象，尽量识别，识别不了就跳过
                else -> when (data) {
                    is JsonArray -> data.forEach { m -> (m as? JsonObject)?.let { parseModule(it) }?.let(sections::add) }
                    is JsonObject -> parseModule(data)?.let(sections::add)
                    else -> {}
                }
            }
        }
        return sections
    }

    fun parseModule(m: JsonObject): DiscoverySection? {
        val title = m.str("title").ifEmpty { return null }
        val targets = m.arr("target")
        val eps = targets.mapObj { t -> t.obj("episode")?.let { parseEpisode(it) } }.filterNotNull()
        val pods = targets.mapObj { t -> t.obj("podcast")?.let { parsePodcast(it) } }.filterNotNull()
        if (eps.isEmpty() && pods.isEmpty()) return null
        return DiscoverySection(title, podcasts = pods, episodes = eps)
    }

    /** 搜索结果：data 里混着 HEADER / PODCAST / EPISODE；highlightWord.words 是要高亮的词 */
    fun parseSearch(r: JsonObject): SearchResult {
        val pods = mutableListOf<Podcast>()
        val eps = mutableListOf<Episode>()
        r.arr("data").forEach { item ->
            val o = item as? JsonObject ?: return@forEach
            when (o.str("type")) {
                "PODCAST" -> pods += parsePodcast(o)
                "EPISODE" -> eps += parseEpisode(o)
            }
        }
        val words = r.obj("highlightWord").arr("words").mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { w -> w.isNotBlank() } }
        return SearchResult(pods, eps, words)
    }

    fun parseCategories(r: JsonObject): List<Category> =
        r.arr("data").mapObj { c ->
            val icon = c.obj("icon")
            Category(c.str("id"), c.str("name"), icon.str("smallPicUrl").ifEmpty { icon.str("picUrl") })
        }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }

    /** 分类推荐：每项 {podcast, episode}；单集里没有节目信息时用外层 podcast 补齐 */
    fun parseCategoryEpisodes(r: JsonObject): List<Episode> =
        r.arr("data").mapObj { item ->
            val e = item.obj("episode") ?: return@mapObj null
            val pod = item.obj("podcast")
            val ep = parseEpisode(e)
            val p = pod?.let { parsePodcast(it) }
            ep.copy(
                pid = ep.pid.ifEmpty { p?.pid.orEmpty() },
                podcastTitle = ep.podcastTitle.ifEmpty { p?.title.orEmpty() },
                coverUrl = ep.coverUrl.ifEmpty { p?.coverUrl.orEmpty() },
                color = ep.color ?: p?.color,
            )
        }.filter { it.eid.isNotEmpty() }

    fun parseUser(o: JsonObject?): User = User(
        uid = o.str("uid"),
        nickname = o.str("nickname"),
        avatarUrl = o.obj("avatar").obj("picture").let { it.str("middlePicUrl").ifEmpty { it.str("picUrl") } },
    )

    fun parsePodcast(o: JsonObject): Podcast {
        val img = o.obj("image")
        return Podcast(
            pid = o.str("pid"),
            title = o.str("title"),
            author = o.str("author"),
            description = o.str("description").ifEmpty { o.str("brief") },
            coverUrl = img.str("middlePicUrl").ifEmpty { img.str("picUrl") },
            subscriptionCount = o.int("subscriptionCount"),
            episodeCount = o.int("episodeCount"),
            latestEpisodePubDate = o.str("latestEpisodePubDate"),
            subscribed = o.str("subscriptionStatus") == "ON",
            starred = o.bool("subscriptionStar"),
            color = parseColor(o),
        )
    }

    fun parseColor(o: JsonObject?): ThemeColor? {
        val c = o.obj("color") ?: return null
        return ThemeColor(c.str("original"), c.str("light"), c.str("dark")).takeIf { it.original.isNotEmpty() }
    }

    fun parseEpisode(o: JsonObject): Episode {
        val pod = o.obj("podcast")
        val img = o.obj("image") ?: pod.obj("image")
        val audio = o.obj("enclosure").str("url")
            .ifEmpty { o.obj("media").obj("source").str("url") }
            .ifEmpty { o.obj("media").obj("backupSource").str("url") }
        return Episode(
            eid = o.str("eid"),
            pid = o.str("pid").ifEmpty { pod.str("pid") },
            title = o.str("title"),
            podcastTitle = pod.str("title"),
            description = o.str("description"),
            shownotes = o.str("shownotes"),
            durationSec = o.int("duration"),
            pubDate = o.str("pubDate"),
            audioUrl = audio,
            coverUrl = img.str("middlePicUrl").ifEmpty { img.str("picUrl") },
            playCount = o.int("playCount"),
            color = parseColor(pod) ?: parseColor(o),
            paid = o.str("payType").let { it.isNotEmpty() && it != "FREE" },
            finished = o.bool("isFinished"),
        )
    }

}

// ---------- JSON 小工具：字段缺失时返回空值，不抛异常 ----------

internal fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
internal fun JsonElement?.arr(key: String): JsonArray = ((this as? JsonObject)?.get(key) as? JsonArray) ?: JsonArray(emptyList())
internal fun JsonElement?.str(key: String): String =
    (((this as? JsonObject)?.get(key)) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
internal fun JsonElement?.int(key: String): Int = str(key).toDoubleOrNull()?.toInt() ?: 0
internal fun JsonElement?.bool(key: String): Boolean = str(key) == "true"
internal fun JsonElement?.nonNull(): JsonElement? = this?.takeIf { it !is JsonNull }
internal fun <T> JsonArray.mapObj(f: (JsonObject) -> T?): List<T> = mapNotNull { (it as? JsonObject)?.let(f) }
