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
import com.ryan.xyzcar.data.TranscriptLine
import com.ryan.xyzcar.data.User
import com.ryan.xyzcar.data.lite
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

class AuthExpiredException : IOException("登录已过期，请重新登录")

/** 接口参考 ultrazg/xyz。appVersion / appBuild 太旧会被服务端拒绝，登录验证工具可用环境变量临时覆盖 */
class XyzApi(
    private val tokens: AuthTokens,
    private val appVersion: String = APP_VERSION,
    private val appBuild: String = APP_BUILD,
    private val base: String = BASE,
    private val webBase: String = WEB_BASE,
    private val clock: () -> Long = System::nanoTime,
) {
    /** 只在 refreshLock 内读写 */
    private val lastRefreshAttempt = HashMap<String?, Long>()


    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val mediaHttp: OkHttpClient = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val refreshLock = Mutex()

    private fun localTime(): String =
        OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private val userAgent get() = "Xiaoyuzhou/$appVersion (build:$appBuild; iOS $OS_VERSION)"

    private fun Request.Builder.iosHeaders(
        abtest: String? = "{\"old_user_discovery_feed\":\"enable\"}",
        acceptLanguage: String = "zh-Hant-HK;q=1.0, zh-Hans-CN;q=0.9",
        withTime: Boolean = true,
    ): Request.Builder {
        header("User-Agent", userAgent)
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

    /** 切换账号与刷新互斥，保证新 token 写回发起刷新的那个账号 */
    suspend fun <T> withAuthLock(block: () -> T): T = refreshLock.withLock { block() }

    /** 中途切换了账号就放弃，不拿新账号去刷新或重试 */
    private suspend fun exec(call: Call): JsonObject = withContext(Dispatchers.IO) {
        if (!tokens.isLoggedIn) throw AuthExpiredException()
        val account = tokens.accountId
        val sentToken = tokens.accessToken
        val first = http.newCall(build(call)).execute()
        if (first.code == 401) {
            first.close()
            if (tokens.accountId != account) throw IOException("账号已切换")
            refreshToken(sentToken)
            if (tokens.accountId != account) throw IOException("账号已切换")
            val second = http.newCall(build(call)).execute()
            if (second.code == 401) {
                second.close()
                // 刚刷新成功还被拒：登录态本身是好的（刷新接口认它），是这个接口不认，不能因此退出登录
                throw ApiException(401, "这个功能暂时用不了（服务端拒绝，HTTP 401）")
            }
            return@withContext parse(second).also { if (tokens.accountId != account) throw IOException("账号已切换") }
        }
        // 慢请求在切换账号之后才返回：丢掉，免得旧账号的数据进了新账号的缓存
        parse(first).also { if (tokens.accountId != account) throw IOException("账号已切换") }
    }

    private fun parse(resp: Response): JsonObject = resp.use {
        val text = it.body?.string().orEmpty()
        if (!it.isSuccessful) throw ApiException(it.code, "请求失败（HTTP ${it.code}）")
        if (text.isBlank()) return JsonObject(emptyMap())
        val el = runCatching { json.parseToJsonElement(text) }.getOrElse { throw ApiException(it.hashCode(), "返回内容无法解析") }
        el as? JsonObject ?: JsonObject(mapOf("data" to el))
    }

    // 只做扫码登录：2026-11 实测短信验证码接口都要求阿里云人机验证 captchaVerifyParam，只能在官方页面由真人完成
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

    private fun failure(resp: Response, text: String, what: String): ApiException {
        val toast = runCatching { json.parseToJsonElement(text).str("toast") }.getOrDefault("")
        return ApiException(resp.code, toast.ifEmpty { "$what（HTTP ${resp.code}）" })
    }

    suspend fun createLoginQr(): LoginQr = withContext(Dispatchers.IO) {
        http.newCall(webPost("/v1/auth/qrcode/create", buildJsonObject { put("clientId", WEB_CLIENT_ID) })).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw failure(resp, text, "获取登录二维码失败")
            val o = json.parseToJsonElement(text) as JsonObject
            LoginQr(o.str("id"), o.str("url")).also { if (it.id.isEmpty() || it.url.isEmpty()) throw ApiException(resp.code, "登录二维码格式不对") }
        }
    }

    /** 扫码状态的诊断信息，只记头和 cookie 的名字，不含 token 值 */
    @Volatile var lastQrDiag: String = ""
        private set

    /** 官方网页把 CONFIRMED 和 USED 都当登录成功，token 随这次响应返回；只有 401 + code 21 或 400「无效二维码」算失效 */
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
                        // 登录页已取消、回到了原账号：不覆盖它
                        if (tokens.saveLogin(access, refresh)) QrState.CONFIRMED else QrState.EXPIRED
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

    private fun tokensFrom(resp: Response, o: JsonObject?): Pair<String, String> {
        val cookies = resp.headers("Set-Cookie").associate { c ->
            val kv = c.substringBefore(';'); kv.substringBefore('=').trim() to kv.substringAfter('=', "").trim()
        }
        fun find(name: String) = resp.header(name).orEmpty()
            .ifEmpty { o.str(name) }.ifEmpty { o.obj("data").str(name) }.ifEmpty { cookies[name].orEmpty() }
        return find("x-jike-access-token") to find("x-jike-refresh-token")
    }

    /**
     * 只在 401 时刷新，不定时、不提前。同一账号两次刷新至少隔 [MIN_REFRESH_INTERVAL_MS]，
     * 免得接口本身不认 token 或 5xx / 断网时每个请求都去刷新；用单调时钟，不受车机改时间影响
     */
    suspend fun refreshToken(staleAccess: String?) = refreshLock.withLock {
        if (tokens.accessToken != staleAccess && tokens.accessToken != null) return@withLock // 别人已经刷新过
        val account = tokens.accountId
        val last = lastRefreshAttempt[account] ?: 0L
        if (last != 0L && clock() - last < MIN_REFRESH_INTERVAL_MS * 1_000_000) {
            throw ApiException(429, "刚刷新过登录态，请稍后再试")
        }
        lastRefreshAttempt[account] = clock()
        withContext(Dispatchers.IO) {
            val refresh = tokens.refreshToken ?: throw AuthExpiredException()
            // 2026-11 实测每次刷新都会换新 refresh token；只有 401 算登录过期，5xx 和网络错误保留登录态
            val req = Request.Builder().url("$base/app_auth_tokens.refresh")
                .iosHeaders(abtest = null, withTime = false)
                .header("x-jike-refresh-token", refresh)
                .post("null".toByteArray().toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code == 401) { tokens.expire(tokens.accountId); throw AuthExpiredException() }
                if (!resp.isSuccessful) throw ApiException(resp.code, "刷新登录态失败（HTTP ${resp.code}），稍后会自动重试")
                val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                val (access, newRefresh) = tokensFrom(resp, o)
                if (access.isEmpty()) throw ApiException(resp.code, "刷新登录态失败：响应里没有新的 token，稍后会自动重试")
                tokens.save(access, newRefresh.ifEmpty { refresh })
            }
        }
    }

    /** sortBy=latestEpisodePubDate 按最新单集倒序（2026-11 实测可用，与官方默认排序一致） */
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

    suspend fun podcast(pid: String): Podcast =
        parsePodcast(exec(Call("GET", "/v1/podcast/get?pid=$pid")).obj("data") ?: throw ApiException(0, "节目不存在"))

    suspend fun episodes(pid: String, loadMoreKey: JsonElement? = null, order: String = "desc"): Page<Episode> {
        val body = buildJsonObject {
            put("limit", "20"); put("pid", pid); put("order", order)
            if (loadMoreKey != null && loadMoreKey !is JsonNull) put("loadMoreKey", loadMoreKey)
        }
        val r = exec(Call("POST", "/v1/episode/list", body))
        return Page(r.arr("data").mapObj { parseListEpisode(it) }, r["loadMoreKey"].nonNull())
    }

    suspend fun episode(eid: String): Episode =
        parseEpisode(exec(Call("GET", "/v1/episode/get?eid=$eid")).obj("data") ?: throw ApiException(0, "单集不存在"))

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

    /** 官方「订阅 → 更新」：订阅节目的新单集，按发布时间倒序 */
    suspend fun inbox(loadMoreKey: JsonElement? = null): Page<Episode> {
        val body = buildJsonObject { put("limit", "20"); if (loadMoreKey != null && loadMoreKey !is JsonNull) put("loadMoreKey", loadMoreKey) }
        val r = exec(Call("POST", "/v1/inbox/list", body))
        return Page(r.arr("data").mapObj { parseListEpisode(it) }, r["loadMoreKey"].nonNull())
    }

    /** 返回里还有手机号等字段，只取 uid、昵称和头像 */
    suspend fun profile(): User = XyzParser.parseUser(exec(Call("GET", "/v1/profile/get")).obj("data"))

    suspend fun history(loadMoreKey: JsonElement? = null): Page<Episode> {
        val body = buildJsonObject { if (loadMoreKey != null && loadMoreKey !is JsonNull) put("loadMoreKey", loadMoreKey) }
        val r = exec(Call("POST", "/v1/episode-played/list-history", body, abtest = null))
        return Page(r.arr("data").mapObj { it.obj("episode")?.let { e -> parseListEpisode(e) } }.filterNotNull(), r["loadMoreKey"].nonNull())
    }

    suspend fun discovery(loadMoreKey: String? = null): Pair<List<DiscoverySection>, String?> {
        val body = buildJsonObject { put("returnAll", "false"); if (loadMoreKey != null) put("loadMoreKey", loadMoreKey) }
        val r = exec(Call("POST", "/v1/discovery-feed/list", body))
        val sections = XyzParser.parseDiscovery(r)
        return sections to (r["loadMoreKey"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }
    }

    /** 诊断用：只输出结构，不含内容 */
    suspend fun discoveryShape(loadMoreKey: String? = null): String = shapeOf(exec(Call("POST", "/v1/discovery-feed/list",
        buildJsonObject { put("returnAll", "false"); if (loadMoreKey != null) put("loadMoreKey", loadMoreKey) })))

    /** 只给登录验证工具的 probe 步骤用，只发只读请求 */
    suspend fun rawForProbe(method: String, path: String, body: JsonObject?): JsonObject = exec(Call(method, path, body))

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
            episodes = d.arr("items").mapObj { it.obj("item")?.let { e -> parseListEpisode(e) } }.filterNotNull(),
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
        val eps = r.arr("data").flatMap { day -> (day as? JsonObject).arr("picks").mapObj { p -> p.obj("episode")?.let { parseListEpisode(it) } }.filterNotNull() }
        return Page(eps, r["loadMoreKey"].nonNull())
    }

    suspend fun newEpisodesSquare(): List<Episode> {
        val r = exec(Call("POST", "/v1/pilot-discovery/list", abtest = "{}", acceptLanguage = "zh-Hant-CN;q=1.0, zh-Hans-HK;q=0.9"))
        return r.arr("data").mapObj { it.obj("episode")?.let { e -> parseListEpisode(e) } }.filterNotNull()
    }

    suspend fun categories(): List<Category> = XyzParser.parseCategories(exec(Call("POST", "/v1/category/list-all", abtest = null)))

    /** tab：RECOMMEND 为你推荐 / HOT 近期热门 / ALL 全部；2026-11 实测一次 10 条，没有 loadMoreKey */
    suspend fun categoryEpisodes(categoryId: String, tab: String = "RECOMMEND"): List<Episode> {
        val body = buildJsonObject { put("categoryId", categoryId); put("tab", tab); put("omitSubscribed", false) }
        return XyzParser.parseCategoryEpisodes(exec(Call("POST", "/v1/category/podcast/list-by-tab", body, abtest = null)))
    }

    /** 签名地址有效期很短，不缓存；CDN 只认小宇宙 App 的 UA（否则 403），只带 UA，不带 token 和设备号 */
    suspend fun transcript(eid: String, mediaId: String): List<TranscriptLine> {
        val body = buildJsonObject { put("eid", eid); put("mediaId", mediaId) }
        val url = exec(Call("POST", "/v1/episode-transcript/get", body)).obj("data").str("transcriptUrl")
        if (url.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            mediaHttp.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { r ->
                if (!r.isSuccessful) throw ApiException(r.code, "文字稿下载失败（HTTP ${r.code}）")
                XyzParser.parseTranscript(XyzParser.gunzipIfNeeded(r.body?.bytes() ?: ByteArray(0)).decodeToString())
            }
        }
    }

    /** 每次返回 3 集，只在用户点「换一批」时调用 */
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
    private fun parseListEpisode(o: JsonObject): Episode = XyzParser.parseListEpisode(o)

    companion object {
        const val BASE = "https://api.xiaoyuzhoufm.com"
        const val WEB_BASE = "https://web-api.xiaoyuzhoufm.com"
        /** 不论上次刷新成败都要隔满，防止异常时高频刷新 */
        const val MIN_REFRESH_INTERVAL_MS = 60_000L
        /** 取自 accounts.xiaoyuzhoufm.com 前端代码 */
        const val WEB_APP_ID = "v6worU4NnWyL"
        const val WEB_CLIENT_ID = "xyz-web"
        /** 伪装 iOS 客户端：取自 2026-08 仍在维护的开源客户端的真实版本 / 构建号组合 */
        const val APP_VERSION = "2.98.0"
        const val APP_BUILD = "2908"
        const val OS_VERSION = "26.2.1"
        const val MODEL = "iPhone18,1"
        private val JSON_TYPE = "application/json".toMediaType()
    }
}

object XyzParser {
    /**
     * 2026-11 实测 data 是一组卡片：DISCOVERY_EPISODE_RECOMMEND（data 是单个模块）、EDITOR_PICK、第二页的 TOP_LIST；
     * 旧版的 DISCOVERY_COLLECTION（data 是模块数组）、NEW_POWER 也兼容
     */
    fun parseDiscovery(r: JsonObject): List<DiscoverySection> {
        val sections = mutableListOf<DiscoverySection>()
        r.arr("data").forEach { item ->
            val o = item as? JsonObject ?: return@forEach
            val data = o["data"]
            when (o.str("type")) {
                "DISCOVERY_HEADER", "CATEGORY_ENTRANCE" -> {} // App 里有单独的分类页
                "TOP_LIST" -> (data as? JsonArray)?.forEach { t ->
                    val tl = t as? JsonObject ?: return@forEach
                    val eps = tl.arr("items").mapObj { it.obj("item")?.let(::parseListEpisode) }.filterNotNull()
                    if (eps.isNotEmpty()) sections += DiscoverySection(tl.str("title").ifEmpty { "榜单" }, episodes = eps, topCategory = tl.str("category"))
                }
                "NEW_POWER" -> {
                    val pods = o.obj("data").arr("items").mapObj { parsePodcast(it) }
                    if (pods.isNotEmpty()) sections += DiscoverySection("新节目", podcasts = pods)
                }
                "EDITOR_PICK" -> {
                    val eps = o.obj("data").arr("picks").mapObj { it.obj("episode")?.let(::parseListEpisode) }.filterNotNull()
                    if (eps.isNotEmpty()) sections += DiscoverySection("编辑精选", episodes = eps)
                }
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
        val eps = targets.mapObj { t -> t.obj("episode")?.let { parseListEpisode(it) } }.filterNotNull()
        val pods = targets.mapObj { t -> t.obj("podcast")?.let { parsePodcast(it) } }.filterNotNull()
        if (eps.isEmpty() && pods.isEmpty()) return null
        return DiscoverySection(title, podcasts = pods, episodes = eps)
    }

    fun parseSearch(r: JsonObject): SearchResult {
        val pods = mutableListOf<Podcast>()
        val eps = mutableListOf<Episode>()
        r.arr("data").forEach { item ->
            val o = item as? JsonObject ?: return@forEach
            when (o.str("type")) {
                "PODCAST" -> pods += parsePodcast(o)
                "EPISODE" -> eps += parseListEpisode(o)
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

    fun parseCategoryEpisodes(r: JsonObject): List<Episode> =
        r.arr("data").mapObj { item ->
            val e = item.obj("episode") ?: return@mapObj null
            val pod = item.obj("podcast")
            val ep = parseListEpisode(e)
            val p = pod?.let { parsePodcast(it) }
            ep.copy(
                pid = ep.pid.ifEmpty { p?.pid.orEmpty() },
                podcastTitle = ep.podcastTitle.ifEmpty { p?.title.orEmpty() },
                coverUrl = ep.coverUrl.ifEmpty { p?.coverUrl.orEmpty() },
                color = ep.color ?: p?.color,
            )
        }.filter { it.eid.isNotEmpty() }

    /** 2026-11 实测是 JSON 数组 [{text, startMs}]；也兼容外面再包一层对象 */
    fun parseTranscript(text: String): List<TranscriptLine> {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val arr = root as? JsonArray ?: (root as? JsonObject)?.values?.firstOrNull { it is JsonArray } as? JsonArray ?: return emptyList()
        return arr.mapObj { o ->
            val t = o.str("text").trim()
            val start = o.str("startMs").toDoubleOrNull()?.toLong()
            if (t.isEmpty() || start == null) null else TranscriptLine(start, t)
        }.sortedBy { it.startMs }
    }

    /** 文字稿 CDN 返回 gzip 字节但不一定带 Content-Encoding，按魔数判断 */
    fun gunzipIfNeeded(b: ByteArray): ByteArray =
        if (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) java.util.zip.GZIPInputStream(b.inputStream()).use { it.readBytes() } else b

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

    fun parseListEpisode(o: JsonObject): Episode = parseEpisode(o).lite()

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
            transcriptMediaId = if (o.str("isTranscriptEnabled") == "false") "" else o.obj("transcript").str("mediaId").ifEmpty { o.str("transcriptMediaId") },
        )
    }

}

internal fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
internal fun JsonElement?.arr(key: String): JsonArray = ((this as? JsonObject)?.get(key) as? JsonArray) ?: JsonArray(emptyList())
internal fun JsonElement?.str(key: String): String =
    (((this as? JsonObject)?.get(key)) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
internal fun JsonElement?.int(key: String): Int = str(key).toDoubleOrNull()?.toInt() ?: 0
internal fun JsonElement?.bool(key: String): Boolean = str(key) == "true"
internal fun JsonElement?.nonNull(): JsonElement? = this?.takeIf { it !is JsonNull }
internal fun <T> JsonArray.mapObj(f: (JsonObject) -> T?): List<T> = mapNotNull { (it as? JsonObject)?.let(f) }
