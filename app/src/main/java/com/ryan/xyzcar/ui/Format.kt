package com.ryan.xyzcar.ui

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Fmt {
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    fun minutes(sec: Int): String = when {
        sec <= 0 -> ""
        sec < 3600 -> "${(sec + 59) / 60} 分钟"
        else -> "${sec / 3600} 小时 ${(sec % 3600) / 60} 分"
    }

    fun remaining(durationSec: Int, progressSec: Int): String {
        if (durationSec <= 0) return ""
        if (progressSec <= 0) return minutes(durationSec)
        if (progressSec >= durationSec - 30) return "已听完"
        return "剩余 " + minutes(durationSec - progressSec)
    }

    private val dateFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneId.systemDefault())
    fun date(iso: String): String {
        val t = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
        val days = Duration.between(t, Instant.now()).toDays()
        return when {
            days < 1 -> "今天"
            days < 2 -> "昨天"
            days < 7 -> "$days 天前"
            else -> dateFmt.format(t)
        }
    }

    /** 86000 → 8.6万 */
    fun count(n: Int): String = if (n >= 10000) "%.1f万".format(n / 10000f).replace(".0万", "万") else n.toString()
}
