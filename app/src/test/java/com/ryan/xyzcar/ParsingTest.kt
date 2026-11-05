package com.ryan.xyzcar

import com.ryan.xyzcar.data.api.XyzParser
import com.ryan.xyzcar.player.PlayerConnection
import com.ryan.xyzcar.ui.Fmt
import com.ryan.xyzcar.ui.ShownotesParser
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用 xyz 文档里的真实返回样例验证解析逻辑 */
class ParsingTest {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { isLenient = true; allowTrailingComma = true; allowComments = true; ignoreUnknownKeys = true }

    private val api = XyzParser

    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    /** 2026-11 实测的发现页结构（内容是编的，字段名和层级照实） */
    @Test
    fun parseDiscovery2026() {
        fun ep(id: String) = """{"type":"EPISODE","eid":"$id","pid":"p1","title":"单集$id","duration":600,
            "enclosure":{"url":"https://a.example/$id.m4a"},"podcast":{"pid":"p1","title":"节目"}}"""
        val r = json.parseToJsonElement("""{"data":[
            {"type":"DISCOVERY_HEADER","data":[{"id":"1","url":"x","leftImage":{},"rightContent":{}}]},
            {"type":"DISCOVERY_EPISODE_RECOMMEND","data":{"title":"为你推荐","moduleType":"x","targetType":"EPISODE",
              "target":[{"recommendation":"r","relatedUsers":[],"hasNegativeFeedback":false,"episode":${ep("e1")}},
                        {"recommendation":"r","relatedUsers":[],"hasNegativeFeedback":false,"episode":${ep("e2")}}]}},
            {"type":"EDITOR_PICK","data":{"date":"2026-11-05","dateIsoStr":"x","picks":[{"episode":${ep("e3")},"recentAudiences":[],"comment":{}}]}},
            {"type":"TOP_LIST","data":[{"type":"TOP_LIST","id":"t","title":"最热榜","category":"HOT_EPISODES_IN_24_HOURS","items":[{"item":${ep("e4")}}]}]},
            {"type":"CATEGORY_ENTRANCE","data":{"categories":[{"id":"c","name":"文化"}],"dismiss":{"id":"d","type":"CATEGORY_ENTRANCE"}}}
        ],"loadMoreKey":"k"}""").jsonObject
        val sections = api.parseDiscovery(r)
        assertEquals(listOf("为你推荐", "编辑精选", "最热榜"), sections.map { it.title })
        assertEquals(listOf("e4"), sections[2].episodes.map { it.eid })
        assertEquals(listOf("e1", "e2"), sections[0].episodes.map { it.eid })
        assertEquals(listOf("e3"), sections[1].episodes.map { it.eid })
    }

    @Test
    fun parseEpisodeDetail() {
        val root = json.parseToJsonElement(res("episode_detail.json")).jsonObject
        val upstream = root["data"]!!.jsonObject["data"]!!.jsonObject
        val ep = api.parseEpisode(upstream)
        assertTrue(ep.eid.isNotEmpty())
        assertTrue(ep.pid.isNotEmpty())
        assertTrue("audio url: ${ep.audioUrl}", ep.audioUrl.startsWith("http"))
        assertTrue(ep.durationSec > 60)
        assertTrue(ep.podcastTitle.isNotEmpty())
        assertTrue(ep.coverUrl.startsWith("http"))
        assertTrue(ep.shownotes.contains("<"))
        assertEquals("#1A1A1A", ep.color?.original)
    }

    @Test
    fun accentPicksReadableVariant() {
        val c = com.ryan.xyzcar.data.ThemeColor("#1A1A1A", "#ACACAC", "#0B0B0B")
        val dark = androidx.compose.material3.darkColorScheme(background = androidx.compose.ui.graphics.Color(0xFF0B0B0C), surface = androidx.compose.ui.graphics.Color(0xFF161618))
        val light = androidx.compose.material3.lightColorScheme(background = androidx.compose.ui.graphics.Color(0xFFF7F6F5), surface = androidx.compose.ui.graphics.Color.White)
        // 深色底：原色太暗看不清，退到浅色变体
        assertEquals(com.ryan.xyzcar.ui.theme.parseHex("#ACACAC"), com.ryan.xyzcar.ui.theme.pickAccent(c, dark))
        // 浅色底：原色可用
        assertEquals(com.ryan.xyzcar.ui.theme.parseHex("#1A1A1A"), com.ryan.xyzcar.ui.theme.pickAccent(c, light))
        // 没有主题色：返回 null，沿用品牌色
        assertEquals(null, com.ryan.xyzcar.ui.theme.pickAccent(null, light))
    }

    @Test
    fun parseEpisodeList() {
        val root = json.parseToJsonElement(res("episode_list.json")).jsonObject
        val upstream = root["data"]!!.jsonObject
        val items = (upstream["data"] as JsonArray).map { api.parseEpisode(it as JsonObject) }
        assertTrue(items.isNotEmpty())
        items.forEach { assertTrue(it.eid.isNotEmpty()); assertTrue(it.title.isNotEmpty()) }
        assertTrue(upstream["loadMoreKey"] is JsonObject)
    }

    @Test
    fun speedCycle() {
        var s = 1.0f
        val seen = mutableListOf<Float>()
        repeat(5) {
            s = PlayerConnection.SPEEDS.firstOrNull { it > s + 0.01f } ?: PlayerConnection.SPEEDS.first()
            seen += s
        }
        assertEquals(listOf(1.25f, 1.5f, 2.0f, 0.8f, 1.0f), seen)
    }

    @Test
    fun format() {
        assertEquals("01:05", Fmt.clock(65_000))
        assertEquals("1:01:05", Fmt.clock(3_665_000))
        assertEquals("已听完", Fmt.remaining(3600, 3590))
        assertEquals("剩余 30 分钟", Fmt.remaining(3600, 1800))
    }

    @Test
    fun shownotesTimestamps() {
        val ts = ShownotesParser.timestamps("<p>00:30 开场</p><p>12:05 正题</p><p>1:02:03 结尾</p><p>2024 年</p>")
        assertEquals(listOf(30, 725, 3723), ts.map { it.seconds })
    }
}
