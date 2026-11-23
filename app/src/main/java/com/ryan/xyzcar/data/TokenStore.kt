package com.ryan.xyzcar.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/** 登录态存取。抽成接口，方便在 JVM 上（登录验证工具、单元测试）用内存或文件实现。不写日志 */
interface AuthTokens {
    var accessToken: String?
    var refreshToken: String?
    var uid: String?
    /** 固定设备 ID（x-jike-device-id），所有账号共用，退出登录也不变（和官方 App 在同一台手机上换号一样） */
    val deviceId: String
    /** 最近一次拿到 token 的时间（毫秒）；0 表示不知道 */
    val savedAt: Long get() = 0L
    val isLoggedIn: Boolean get() = !accessToken.isNullOrEmpty() && !refreshToken.isNullOrEmpty()
    /** 当前账号的槽位；单账号实现返回 "" */
    val accountId: String? get() = ""
    fun save(access: String, refresh: String)
    /** 扫码登录拿到的 token；返回 false 表示没写（当前已有登录中的账号，例如取消添加账号后才返回的扫码结果） */
    fun saveLogin(access: String, refresh: String): Boolean { save(access, refresh); return true }
    fun clear()
    /** 刷新接口明确 401：只作废 [account] 这一个账号的登录态 */
    fun expire(account: String?) = clear()
}

/** 最小键值存储：一次写多个键（值为 null 表示删除），方便换成内存实现做测试 */
interface KeyValue {
    fun get(key: String): String?
    fun put(vararg pairs: Pair<String, String?>)
}

/** 账号列表里显示的信息；[loggedIn] = false 表示登录已过期，点一下重新扫码 */
data class Account(val id: String, val uid: String, val nickname: String, val avatarUrl: String, val loggedIn: Boolean)

/**
 * 多账号：每个账号一个槽位（a.<id>.access / refresh / saved_at / uid / nick / avatar），accounts 存槽位顺序，active 存当前槽位。
 * - 0.0.5 及以前只有一组 access / refresh，首次打开时原样迁到槽位 [MAIN]，不用重新扫码；设备 ID 不变
 * - 切换账号只改 active，不刷新 token（刷新只在 401 时发生）
 * - 添加账号时先把 active 置空，新登录写进新槽位，不会覆盖当前账号；同一个人（uid 相同）再登录时合并回原槽位
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

    /** 没有当前账号（首次登录、添加账号）时新建一个槽位并设为当前 */
    override fun save(access: String, refresh: String) {
        val id = activeId ?: newId().also { kv.put("accounts" to (ids + it).joinToString(","), "active" to it) }
        kv.put(k(id, "access") to access, k(id, "refresh") to refresh, k(id, "saved_at") to System.currentTimeMillis().toString())
    }

    override fun saveLogin(access: String, refresh: String): Boolean {
        if (isLoggedIn) return false
        save(access, refresh)
        return true
    }

    /** 退出当前账号：删掉它的槽位 */
    override fun clear() { activeId?.let { remove(it) } }

    override fun expire(account: String?) {
        if (account != null && account in ids) kv.put(k(account, "access") to null, k(account, "refresh") to null)
    }

    fun remove(id: String) {
        val fields = FIELDS.map { k(id, it) to null }.toTypedArray()
        kv.put(*fields, "accounts" to (ids - id).joinToString(","), "active" to activeId.takeIf { it != id })
    }

    /** null = 暂时没有当前账号（添加账号的登录页） */
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

    /**
     * [id] 刚登录、已写入 uid：如果另一个槽位是同一个人，把新 token 和资料并回那个槽位（保留它的播放列表、续播点），
     * 删掉 [id]，并把那个槽位设为当前。返回最终的槽位
     */
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
        /** 0.0.5 及以前的唯一账号迁移后的槽位；它的本地数据（播放列表、续播点）沿用原来的键，不用搬 */
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
