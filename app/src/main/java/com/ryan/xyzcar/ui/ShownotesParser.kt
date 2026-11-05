package com.ryan.xyzcar.ui

/** 从 shownotes 文本里找出时间戳（mm:ss 或 h:mm:ss） */
object ShownotesParser {
    data class Stamp(val start: Int, val end: Int, val seconds: Int)

    private val regex = Regex("""(?<![\d:])(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?![\d:])""")

    fun timestamps(text: String): List<Stamp> = regex.findAll(text).mapNotNull { m ->
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val min = m.groupValues[2].toInt()
        val s = m.groupValues[3].toInt()
        if (s >= 60 || (m.groupValues[1].isNotEmpty() && min >= 60)) null
        else Stamp(m.range.first, m.range.last + 1, h * 3600 + min * 60 + s)
    }.toList()
}
