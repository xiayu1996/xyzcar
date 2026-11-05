package com.ryan.xyzcar.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/** token 存在 EncryptedSharedPreferences；不写日志 */
/** 登录态存取。抽成接口，方便在 JVM 上（登录验证工具）用文件实现 */
interface AuthTokens {
    var accessToken: String?
    var refreshToken: String?
    var uid: String?
    /** 固定设备 ID（x-jike-device-id），退出登录也不变 */
    val deviceId: String
    /** 最近一次拿到 token 的时间（毫秒），用来提前刷新；0 表示不知道 */
    val savedAt: Long get() = 0L
    val isLoggedIn: Boolean get() = !accessToken.isNullOrEmpty() && !refreshToken.isNullOrEmpty()
    fun save(access: String, refresh: String)
    fun clear()
}

class TokenStore(context: Context) : AuthTokens {
    private val prefs: SharedPreferences = try {
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

    override var accessToken: String?
        get() = prefs.getString("access", null)
        set(v) = prefs.edit().putString("access", v).apply()

    override var refreshToken: String?
        get() = prefs.getString("refresh", null)
        set(v) = prefs.edit().putString("refresh", v).apply()

    override var uid: String?
        get() = prefs.getString("uid", null)
        set(v) = prefs.edit().putString("uid", v).apply()

    /** 固定设备 ID：首次生成后不再变化，让请求看起来像同一台设备 */
    override val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().uppercase().also {
            prefs.edit().putString("device_id", it).apply()
        }

    override val savedAt: Long get() = prefs.getLong("saved_at", 0L)

    override fun save(access: String, refresh: String) {
        prefs.edit().putString("access", access).putString("refresh", refresh).putLong("saved_at", System.currentTimeMillis()).apply()
    }

    override fun clear() {
        prefs.edit().remove("access").remove("refresh").remove("uid").remove("saved_at").apply()
    }
}
