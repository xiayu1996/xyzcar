package com.ryan.xyzcar

import com.ryan.xyzcar.data.AuthTokens
import com.ryan.xyzcar.data.api.ApiException
import com.ryan.xyzcar.data.api.AuthExpiredException
import com.ryan.xyzcar.data.api.XyzApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 登录态的关键路径，用本地假服务器验证（不连小宇宙）：
 * access token 过期 → 自动刷新 → 重试；并发只刷新一次；刷新出的新 refresh token 会保存；
 * 服务器 5xx / 断网时保留登录态；只有刷新接口明确 401 才算登录过期；扫码登录状态处理。
 */
class AuthFlowTest {
    private class MemTokens : AuthTokens {
        override var accessToken: String? = "old-access"
        override var refreshToken: String? = "old-refresh"
        override var uid: String? = null
        override val deviceId = "TEST-DEVICE"
        override var savedAt: Long = 0L
        override fun save(access: String, refresh: String) { accessToken = access; refreshToken = refresh; savedAt = System.currentTimeMillis() }
        override fun clear() { accessToken = null; refreshToken = null }
    }

    private lateinit var server: MockWebServer
    private val refreshCalls = AtomicInteger()
    private var refreshResponse: () -> MockResponse = {
        MockResponse().setHeader("x-jike-access-token", "new-access").setHeader("x-jike-refresh-token", "new-refresh").setBody("{}")
    }
    private var qrResponses = ArrayDeque<MockResponse>()

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/subscription/list" ->
                    if (request.getHeader("x-jike-access-token") == "new-access") MockResponse().setBody("""{"data":[]}""")
                    else MockResponse().setResponseCode(401).setBody("{}")
                "/app_auth_tokens.refresh" -> {
                    refreshCalls.incrementAndGet()
                    Thread.sleep(100) // 让并发请求真的撞在一起
                    refreshResponse()
                }
                "/v1/auth/qrcode/create" -> MockResponse().setBody("""{"id":"q1","url":"https://h5.example/oauth?qrcode_id=q1"}""")
                "/v1/auth/qrcode/login" -> qrResponses.removeFirstOrNull() ?: MockResponse().setBody("""{"status":"WAITTING"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun api(tokens: AuthTokens) = server.url("").toString().trimEnd('/').let { XyzApi(tokens, base = it, webBase = it) }

    @Test fun expiredAccessIsRefreshedAndRetried() = runBlocking {
        val t = MemTokens()
        api(t).subscriptions(null)
        assertEquals("new-access", t.accessToken)
        assertEquals("新的 refresh token 要保存下来（滑动续期）", "new-refresh", t.refreshToken)
        assertEquals(1, refreshCalls.get())
    }

    /** 不提前刷新：token 再旧，只要服务端不返回 401 就不刷新 */
    @Test fun noRefreshWithout401() = runBlocking {
        val t = MemTokens().apply { accessToken = "new-access"; savedAt = System.currentTimeMillis() - 10 * 60 * 60_000 }
        api(t).subscriptions(null)
        assertEquals(0, refreshCalls.get())
    }

    /** 刷新后 60 秒内又遇到 401：不再刷新（防止某个接口一直 401 时每个请求都刷新） */
    @Test fun refreshRateLimited() = runBlocking {
        var now = 1_000_000_000L
        val t = MemTokens()
        val base = server.url("").toString().trimEnd('/')
        val a = XyzApi(t, base = base, webBase = base, clock = { now })
        a.subscriptions(null) // 401 → 刷新一次
        t.accessToken = "old-access" // 模拟新 token 马上又被拒
        repeat(5) { runCatching { a.subscriptions(null) } }
        assertEquals("60 秒内只刷新一次", 1, refreshCalls.get())
        now += 61_000_000_000L
        t.accessToken = "old-access"
        a.subscriptions(null)
        assertEquals("过了 60 秒可以再刷新", 2, refreshCalls.get())
        assertTrue(t.isLoggedIn)
    }

    /** 服务器出错时也受 60 秒间隔限制，不会每个请求都去打刷新接口 */
    @Test fun failingRefreshIsNotHammered() = runBlocking {
        refreshResponse = { MockResponse().setResponseCode(503) }
        val t = MemTokens()
        val a = api(t)
        repeat(10) { runCatching { a.subscriptions(null) } }
        assertEquals(1, refreshCalls.get())
        assertTrue(t.isLoggedIn)
    }

    @Test fun concurrent401sRefreshOnce() = runBlocking {
        val t = MemTokens()
        val a = api(t)
        (1..5).map { async(kotlinx.coroutines.Dispatchers.IO) { a.subscriptions(null) } }.awaitAll()
        assertEquals(1, refreshCalls.get())
    }

    @Test fun serverErrorDuringRefreshKeepsLogin() = runBlocking {
        refreshResponse = { MockResponse().setResponseCode(503) }
        val t = MemTokens()
        try { api(t).subscriptions(null); fail() } catch (e: AuthExpiredException) { fail("503 不应该当成登录过期") } catch (e: ApiException) {}
        assertEquals("old-refresh", t.refreshToken)
        assertTrue(t.isLoggedIn)
    }

    @Test fun networkErrorDuringRefreshKeepsLogin() = runBlocking {
        refreshResponse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        val t = MemTokens()
        try { api(t).subscriptions(null); fail() } catch (e: AuthExpiredException) { fail("断网不应该当成登录过期") } catch (e: IOException) {}
        assertTrue(t.isLoggedIn)
    }

    @Test fun refresh401MeansExpired() = runBlocking {
        refreshResponse = { MockResponse().setResponseCode(401) }
        try { api(MemTokens()).subscriptions(null); fail() } catch (e: AuthExpiredException) {}
    }

    @Test fun qrUsedWithCookieTokensLogsIn() = runBlocking {
        val t = MemTokens().apply { clear() }
        qrResponses.addAll(listOf(
            MockResponse().setBody("""{"status":"WAITTING"}"""),
            MockResponse().setBody("""{"status":"USED"}""")
                .addHeader("Set-Cookie", "x-jike-access-token=qr-access; Path=/; Domain=.example")
                .addHeader("Set-Cookie", "x-jike-refresh-token=qr-refresh; Path=/; HttpOnly"),
        ))
        val a = api(t)
        val qr = a.createLoginQr()
        assertEquals(XyzApi.QrState.WAITING, a.pollLoginQr(qr.id))
        assertEquals(XyzApi.QrState.CONFIRMED, a.pollLoginQr(qr.id))
        assertEquals("qr-access", t.accessToken)
        assertEquals("qr-refresh", t.refreshToken)
    }

    @Test fun qrExpiredOnlyOnCode21() = runBlocking {
        val t = MemTokens().apply { clear() }
        qrResponses.addAll(listOf(
            MockResponse().setResponseCode(500).setBody("""{"code":1,"toast":"服务繁忙"}"""),
            MockResponse().setResponseCode(401).setBody("""{"code":21,"toast":"二维码已失效，请重新扫码登录","message":"qrcode expired"}"""),
        ))
        val a = api(t)
        try { a.pollLoginQr("q1"); fail() } catch (e: ApiException) { assertTrue(e.message!!.contains("服务繁忙")) }
        assertEquals(XyzApi.QrState.EXPIRED, a.pollLoginQr("q1"))
    }
}
