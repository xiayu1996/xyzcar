package com.ryan.xyzcar.data

import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * 局域网分享：车机上临时开一个只读的 HTTP 服务，手机在同一网络里用浏览器打开网址就能拿到报告。
 * 网址带随机口令，只响应这一个路径；离开页面或 [MINUTES] 分钟后自动关闭。不经过任何第三方服务器。
 * 车机连手机热点、或手机连车机热点都行（两边在同一个网段）。
 */
object LanShare {
    const val MINUTES = 10
    private const val PORT = 8765

    data class Session(val urls: List<String>, val until: Long)

    @Volatile private var server: ServerSocket? = null

    /** 开始分享 [text]（再次调用会替换上一份）；没有局域网地址时返回 null */
    @Synchronized
    fun start(text: String): Session? {
        stop()
        val ips = lanAddresses()
        if (ips.isEmpty()) return null.also { DebugLog.kv("export", "lan_start", "result" to "no_lan") }
        val token = token()
        val body = text.toByteArray()
        val ss = runCatching { ServerSocket(PORT) }.getOrElse { ServerSocket(0) }.apply { soTimeout = 1000 }
        val until = System.currentTimeMillis() + MINUTES * 60_000L
        server = ss
        thread(isDaemon = true, name = "lan-share") {
            ss.use {
                while (!ss.isClosed && System.currentTimeMillis() < until) {
                    val sock = try { ss.accept() } catch (_: SocketTimeoutException) { continue } catch (_: IOException) { break }
                    runCatching {
                        sock.use { s ->
                            s.soTimeout = 3000
                            val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                            val path = line.split(' ').getOrNull(1).orEmpty()
                            val ok = line.startsWith("GET ") && (path == "/$token" || path == "/$token.txt")
                            val payload = if (ok) body else "not found\n".toByteArray()
                            val head = (if (ok) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 404 Not Found\r\n") +
                                "Content-Type: text/plain; charset=utf-8\r\nContent-Length: ${payload.size}\r\n" +
                                "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
                            s.getOutputStream().apply { write(head.toByteArray()); write(payload); flush() }
                            DebugLog.kv("export", "lan_get", "ok" to ok)
                        }
                    }
                }
            }
            DebugLog.kv("export", "lan_stop")
        }
        val urls = ips.map { "http://$it:${ss.localPort}/$token" }
        DebugLog.kv("export", "lan_start", "port" to ss.localPort, "addresses" to ips.size, "bytes" to body.size)
        return Session(urls, until)
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    /** 本机的局域网 IPv4（Wi-Fi 客户端或热点），不含回环和蜂窝网络的运营商地址 */
    fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("ccmni") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress.orEmpty() }
            .distinct()
    }.getOrDefault(emptyList())

    private fun token(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..8).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }
}
