package com.ryan.xyzcar.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/** 抽成接口供 JVM 上的登录验证工具和单元测试替换实现；不写日志 */
interface AuthTokens {
    var accessToken: String?
    var refreshToken: String?
    var uid: String?
    /** 所有账号共用，退出登录也不变（同官方 App 在一台手机上换号） */
    val deviceId: String
    /** 毫秒；0 表示未知 */
    val savedAt: Long get() = 0L
    val isLoggedIn: Boolean get() = !accessToken.isNullOrEmpty() && !refreshToken.isNullOrEmpty()
    val accountId: String? get() = ""
    fun save(access: String, refresh: String)
    /** 已有登录中的账号时不写、返回 false（如取消添加账号后才返回的扫码结果） */
    fun saveLogin(access: String, refresh: String): Boolean { save(access, refresh); return true }
    fun clear()
    /** 刷新接口明确 401 时只作废 [account] 的登录态 */
    fun expire(account: String?) = clear()
}

/** 值为 null 表示删除 */
interface KeyValue {
    fun get(key: String): String?
    fun put(vararg pairs: Pair<String, String?>)
}

data class Account(val id: String, val uid: String, val nickname: String, val avatarUrl: String, val loggedIn: Boolean)

/**
 * 每个账号一个槽位（a.<id>.字段），accounts 存顺序，active 存当前槽位；切换账号只改 active，不刷新 token。
 * 0.0.5 及以前的单组 token 首次打开时原样迁到 [MAIN]，不用重新扫码
 */
open class AccountStore(private val kv: KeyValue) : AuthTokens {
    init { migrate() }

    private fun migrate() {
        if (kv.get("accounts") != null) return
        val a = kv.get("access"); val r = kv.get("refresh")
        if (!a.isNullOrEmpty() && !r.isNullOrEmpty()) {
            kv.put(
                k(MAIN, "access") to a, k(MAIN, "refresh") to r, k(MAIN, "saved_at") to kv.get("saved_at"), k(MAIN, "uid") to kv.get("uid"),
                "accounts" to MAIN, "active" to MAIN,
                "access" to null, "refresh" to null, "saved_at" to null, "uid" to null,
            )
        } else {
            kv.put("accounts" to "", "access" to null, "refresh" to null, "saved_at" to null, "uid" to null)
        }
    }

    private fun k(id: String, field: String) = "a.$id.$field"
    private fun field(f: String): String? = activeId?.let { kv.get(k(it, f)) }
    private fun setField(f: String, v: String?) { activeId?.let { kv.put(k(it, f) to v) } }

    val ids: List<String> get() = kv.get("accounts").orEmpty().split(',').filter { it.isNotEmpty() }
    val activeId: String? get() = kv.get("active")?.takeIf { it in ids }
    override val accountId: String? get() = activeId

    override var accessToken: String? get() = field("access"); set(v) = setField("access", v)
    override var refreshToken: String? get() = field("refresh"); set(v) = setField("refresh", v)
    override var uid: String? get() = field("uid"); set(v) = setField("uid", v)
    override val savedAt: Long get() = field("saved_at")?.toLongOrNull() ?: 0L

    override val deviceId: String
        get() = kv.get("device_id") ?: UUID.randomUUID().toString().uppercase().also { kv.put("device_id" to it) }

    override fun save(access: String, refresh: String) {
        val id = activeId ?: newId().also { kv.put("accounts" to (ids + it).joinToString(","), "active" to it) }
        kv.put(k(id, "access") to access, k(id, "refresh") to refresh, k(id, "saved_at") to System.currentTimeMillis().toString())
    }

    override fun saveLogin(access: String, refresh: String): Boolean {
        if (isLoggedIn) return false
        save(access, refresh)
        return true
    }

    override fun clear() { activeId?.let { remove(it) } }

    override fun expire(account: String?) {
        if (account != null && account in ids) kv.put(k(account, "access") to null, k(account, "refresh") to null)
    }

    fun remove(id: String) {
        val fields = FIELDS.map { k(id, it) to null }.toTypedArray()
        kv.put(*fields, "accounts" to (ids - id).joinToString(","), "active" to activeId.takeIf { it != id })
    }

    /** null：添加账号的登录页，暂时没有当前账号 */
    fun activate(id: String?) { kv.put("active" to id?.takeIf { it in ids }) }

    fun accounts(): List<Account> = ids.map { id ->
        Account(
            id, kv.get(k(id, "uid")).orEmpty(), kv.get(k(id, "nick")).orEmpty(), kv.get(k(id, "avatar")).orEmpty(),
            !kv.get(k(id, "access")).isNullOrEmpty() && !kv.get(k(id, "refresh")).isNullOrEmpty(),
        )
    }

    fun setProfile(id: String, user: User) {
        if (id in ids) kv.put(k(id, "uid") to user.uid.ifEmpty { null }, k(id, "nick") to user.nickname, k(id, "avatar") to user.avatarUrl)
    }

    /** 同一个人已有槽位时把新 token 并回旧槽位（保留它的播放列表、续播点）并删掉 [id]；返回最终槽位 */
    fun mergeDuplicate(id: String): String {
        val uid = kv.get(k(id, "uid")).orEmpty()
        if (uid.isEmpty()) return id
        val old = ids.firstOrNull { it != id && kv.get(k(it, "uid")) == uid } ?: return id
        val moved = listOf("access", "refresh", "saved_at", "nick", "avatar").map { k(old, it) to kv.get(k(id, it)) }.toTypedArray()
        kv.put(*moved)
        remove(id)
        activate(old)
        return old
    }

    private fun newId(): String = if (MAIN !in ids) MAIN else "a" + UUID.randomUUID().toString().replace("-", "").take(8)

    companion object {
        /** 0.0.5 及以前唯一账号迁移后的槽位，本地数据沿用原键名 */
        const val MAIN = "main"
        private val FIELDS = listOf("access", "refresh", "saved_at", "uid", "nick", "avatar")
    }
}

class TokenStore(context: Context) : AccountStore(PrefsKeyValue(securePrefs(context)))

private fun securePrefs(context: Context): SharedPreferences = try {
    val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    EncryptedSharedPreferences.create(
        context, "auth_secure", key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
} catch (e: Exception) {
    // Keystore 异常时退回普通存储，保证能用
    context.getSharedPreferences("auth_fallback", Context.MODE_PRIVATE)
}

private class PrefsKeyValue(private val prefs: SharedPreferences) : KeyValue {
    // 0.0.5 及以前 saved_at 存的是 Long
    override fun get(key: String): String? = try { prefs.getString(key, null) } catch (e: ClassCastException) { prefs.getLong(key, 0L).toString() }
    override fun put(vararg pairs: Pair<String, String?>) {
        val e = prefs.edit()
        pairs.forEach { (k, v) -> if (v == null) e.remove(k) else e.putString(k, v) }
        e.apply()
    }
}
