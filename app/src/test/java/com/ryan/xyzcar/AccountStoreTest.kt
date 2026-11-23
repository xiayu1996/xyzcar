package com.ryan.xyzcar

import com.ryan.xyzcar.data.AccountStore
import com.ryan.xyzcar.data.KeyValue
import com.ryan.xyzcar.data.User
import com.ryan.xyzcar.data.api.XyzApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** 多账号：旧版迁移、添加账号不碰当前账号、同一个人合并、过期只影响一个账号、切换和刷新互斥 */
class AccountStoreTest {
    private class MemKv(init: Map<String, String> = emptyMap()) : KeyValue {
        val map = HashMap(init)
        override fun get(key: String): String? = map[key]
        override fun put(vararg pairs: Pair<String, String?>) { pairs.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v } }
    }

    @Test fun legacySingleAccountMigratesWithoutRescan() {
        val kv = MemKv(mapOf("access" to "A1", "refresh" to "R1", "device_id" to "DEV", "saved_at" to "123"))
        val s = AccountStore(kv)
        assertEquals(AccountStore.MAIN, s.activeId)
        assertEquals("A1", s.accessToken)
        assertEquals("R1", s.refreshToken)
        assertEquals(123L, s.savedAt)
        assertEquals("设备 ID 不能变（refresh token 和它配对）", "DEV", s.deviceId)
        assertNull("旧键迁走后删除", kv.map["access"])
        // 再打开一次不会重复迁移
        assertEquals(listOf(AccountStore.MAIN), AccountStore(kv).ids)
    }

    @Test fun freshInstallFirstLoginCreatesMain() {
        val s = AccountStore(MemKv())
        assertFalse(s.isLoggedIn)
        s.save("A", "R")
        assertEquals(AccountStore.MAIN, s.activeId)
        assertTrue(s.isLoggedIn)
    }

    @Test fun addingAccountDoesNotTouchCurrentOne() {
        val s = AccountStore(MemKv(mapOf("access" to "A1", "refresh" to "R1")))
        s.setProfile(AccountStore.MAIN, User("u1", "一号"))
        s.activate(null) // 添加账号：暂时离开当前账号
        assertFalse(s.isLoggedIn)
        s.save("A2", "R2") // 扫码登录写进新槽位
        val second = s.activeId!!
        assertTrue(second != AccountStore.MAIN)
        s.setProfile(second, User("u2", "二号"))
        assertEquals(second, s.mergeDuplicate(second))
        assertEquals(2, s.accounts().size)
        s.activate(AccountStore.MAIN)
        assertEquals("A1", s.accessToken)
        assertEquals("R1", s.refreshToken)
    }

    /** 取消添加账号、回到原账号后，晚到的扫码结果不能覆盖原账号 */
    @Test fun lateQrLoginDoesNotOverwriteActiveAccount() {
        val s = AccountStore(MemKv(mapOf("access" to "A1", "refresh" to "R1")))
        assertFalse(s.saveLogin("X", "Y"))
        assertEquals("A1", s.accessToken)
        assertEquals("R1", s.refreshToken)
        assertEquals(1, s.ids.size)
    }

    @Test fun sameUserLoggingInAgainMergesIntoOriginalSlot() {
        val s = AccountStore(MemKv(mapOf("access" to "A1", "refresh" to "R1")))
        s.setProfile(AccountStore.MAIN, User("u1", "一号"))
        s.expire(AccountStore.MAIN)
        s.activate(null)
        s.save("A1b", "R1b")
        val fresh = s.activeId!!
        s.setProfile(fresh, User("u1", "一号"))
        assertEquals(AccountStore.MAIN, s.mergeDuplicate(fresh))
        assertEquals(listOf(AccountStore.MAIN), s.ids)
        assertEquals(AccountStore.MAIN, s.activeId)
        assertEquals("A1b", s.accessToken)
        assertTrue(s.accounts().single().loggedIn)
    }

    @Test fun expireOnlyAffectsOneAccount() {
        val s = AccountStore(MemKv(mapOf("access" to "A1", "refresh" to "R1")))
        s.activate(null); s.save("A2", "R2")
        val second = s.activeId!!
        s.expire(AccountStore.MAIN)
        assertTrue(s.isLoggedIn)
        assertEquals(listOf(false, true), s.accounts().map { it.loggedIn })
        s.remove(second)
        assertNull(s.activeId)
        assertEquals(listOf(AccountStore.MAIN), s.ids)
    }

    /** 刷新间隔按账号计时：刚刷新过 A，切到 B 马上能刷新 */
    @Test fun refreshIntervalIsPerAccount() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/subscription/list" ->
                    if (request.getHeader("x-jike-access-token")?.endsWith("-new") == true) MockResponse().setBody("""{"data":[]}""")
                    else MockResponse().setResponseCode(401).setBody("{}")
                "/app_auth_tokens.refresh" -> {
                    val r = request.getHeader("x-jike-refresh-token").orEmpty()
                    MockResponse().setHeader("x-jike-access-token", r.removePrefix("R") + "-new").setHeader("x-jike-refresh-token", r + "b").setBody("{}")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val s = AccountStore(MemKv(mapOf("access" to "A1", "refresh" to "R1")))
            s.activate(null); s.save("A2", "R2")
            val second = s.activeId!!
            s.activate(AccountStore.MAIN)
            val base = server.url("").toString().trimEnd('/')
            val api = XyzApi(s, base = base, webBase = base)
            api.subscriptions(null)
            api.withAuthLock { s.activate(second) }
            api.subscriptions(null) // 不应该报「刚刷新过登录态」
            assertEquals("2-new", s.accessToken)
        } finally {
            server.shutdown()
        }
    }

    /** 刷新进行中切换账号：新 token 写回发起刷新的账号，另一个账号不受影响；原请求放弃，不拿新账号重试 */
    @Test fun switchDuringRefreshKeepsTokensWithTheirAccount() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/subscription/list" ->
                    if (request.getHeader("x-jike-access-token") == "A1-new") MockResponse().setBody("""{"data":[]}""")
                    else MockResponse().setResponseCode(401).setBody("{}")
                "/app_auth_tokens.refresh" -> {
                    Thread.sleep(300)
                    if (request.getHeader("x-jike-refresh-token") == "R1") MockResponse().setHeader("x-jike-access-token", "A1-new").setHeader("x-jike-refresh-token", "R1-new").setBody("{}")
                    else MockResponse().setResponseCode(401)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val s = AccountStore(MemKv(mapOf("access" to "A1", "refresh" to "R1")))
            s.activate(null); s.save("A2", "R2")
            val second = s.activeId!!
            s.activate(AccountStore.MAIN)
            val base = server.url("").toString().trimEnd('/')
            val api = XyzApi(s, base = base, webBase = base)
            val call = async(kotlinx.coroutines.Dispatchers.IO) { runCatching { api.subscriptions(null) } }
            delay(150) // 刷新已经开始
            api.withAuthLock { s.activate(second) }
            val r = call.await()
            if (r.isSuccess) fail("切换账号后不应该拿新账号重试")
            assertTrue(r.exceptionOrNull() is IOException)
            s.activate(AccountStore.MAIN)
            assertEquals("A1-new", s.accessToken)
            assertEquals("R1-new", s.refreshToken)
            s.activate(second)
            assertEquals("A2", s.accessToken)
            assertEquals("R2", s.refreshToken)
        } finally {
            server.shutdown()
        }
    }
}
