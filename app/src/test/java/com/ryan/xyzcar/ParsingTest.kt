package com.ryan.xyzcar

import com.ryan.xyzcar.data.api.XyzParser
import com.ryan.xyzcar.player.PlayerConnection
import com.ryan.xyzcar.ui.Fmt
import com.ryan.xyzcar.ui.ShownotesParser
import com.ryan.xyzcar.ui.screens.isTodo
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

    // ---------- 0.0.2 新接口（2026-11 实测的结构，内容是编的） ----------

    @Test
    fun parseCategoryEpisodesFillsPodcast() {
        val r = json.parseToJsonElement("""{"data":[
            {"podcast":{"pid":"p9","title":"声音节目","image":{"middlePicUrl":"https://i.example/p9.jpg"},"color":{"original":"#112233","light":"#AABBCC","dark":"#001122"}},
             "episode":{"eid":"e9","title":"一集","duration":1800,"payType":"FREE"},"readTrackInfo":{}},
            {"podcast":{"pid":"p8","title":"付费节目"},"episode":{"eid":"e8","title":"付费一集","payType":"PAY_EPISODE"}},
            {"podcast":{"pid":"p7","title":"没有单集"}}
        ]}""").jsonObject
        val eps = api.parseCategoryEpisodes(r)
        assertEquals(listOf("e9", "e8"), eps.map { it.eid })
        assertEquals("p9", eps[0].pid)
        assertEquals("声音节目", eps[0].podcastTitle)
        assertEquals("https://i.example/p9.jpg", eps[0].coverUrl)
        assertEquals("#112233", eps[0].color?.original)
        assertEquals(false, eps[0].paid)
        assertEquals(true, eps[1].paid)
    }

    @Test
    fun parseCategoriesWithIcons() {
        val r = json.parseToJsonElement("""{"data":[
            {"id":"c1","name":"商业","emoji":"🏷️","icon":{"picUrl":"https://i.example/c1.png","smallPicUrl":"https://i.example/c1.png@small"}},
            {"id":"","name":"坏数据"}
        ]}""").jsonObject
        val cats = api.parseCategories(r)
        assertEquals(1, cats.size)
        assertEquals("商业", cats[0].name)
        assertEquals("https://i.example/c1.png@small", cats[0].iconUrl)
    }

    /** 账号接口还返回手机号等字段：只取 uid / 昵称 / 头像 */
    @Test
    fun parseUserIgnoresPrivateFields() {
        val r = json.parseToJsonElement("""{"data":{"uid":"u1","nickname":"昵称","phoneNumber":{"mobilePhoneNumber":"10000000000","areaCode":"+86"},
            "avatar":{"picture":{"picUrl":"https://i.example/a.jpg","middlePicUrl":"https://i.example/a.jpg@middle"}}}}""").jsonObject
        val u = api.parseUser(r["data"] as JsonObject)
        assertEquals("u1", u.uid)
        assertEquals("昵称", u.nickname)
        assertEquals("https://i.example/a.jpg@middle", u.avatarUrl)
        assertTrue("10000000000" !in u.toString())
    }

    @Test
    fun parseSearchSkipsHeadersAndKeepsHighlight() {
        val r = json.parseToJsonElement("""{"data":[
            {"type":"HEADER","title":"节目"},
            {"type":"PODCAST","pid":"p1","title":"辩论小会","payType":"FREE"},
            {"type":"HEADER","title":"单集"},
            {"type":"EPISODE","eid":"e1","title":"辩论？","payType":"FREE","isFinished":true,"podcast":{"pid":"p2","title":"无聊斋"}}
        ],"highlightWord":{"words":["辩论"],"singleMaxHighlightTime":3}}""").jsonObject
        val s = api.parseSearch(r)
        assertEquals(listOf("p1"), s.podcasts.map { it.pid })
        assertEquals(listOf("e1"), s.episodes.map { it.eid })
        assertEquals(listOf("辩论"), s.highlight)
        assertEquals(true, s.episodes[0].finished)
        assertEquals("无聊斋", s.episodes[0].podcastTitle)
    }

    /** 「未听 + 在听」筛选：服务端标记听完、或只剩最后 30 秒的都算听完 */
    @Test
    fun todoFilter() {
        val base = com.ryan.xyzcar.data.Episode("e", "p", "t", durationSec = 600)
        assertTrue(base.isTodo())
        assertTrue(base.copy(progressSec = 300).isTodo())
        assertTrue(!base.copy(progressSec = 580).isTodo())
        assertTrue(!base.copy(finished = true).isTodo())
    }

    @Test
    fun recommendRefreshModule() {
        val m = json.parseToJsonElement("""{"title":"为你推荐","target":[{"episode":{"eid":"e1","title":"a"}},{"episode":{"eid":"e2","title":"b"}}]}""").jsonObject
        assertEquals(listOf("e1", "e2"), api.parseModule(m)?.episodes?.map { it.eid })
    }
}
