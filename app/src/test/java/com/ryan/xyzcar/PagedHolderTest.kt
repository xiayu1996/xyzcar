package com.ryan.xyzcar

import com.ryan.xyzcar.data.Page
import com.ryan.xyzcar.ui.PagedHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.IOException

/** 断网时自动重试会一直发请求，失败后只能等用户点「重试」 */
class PagedHolderTest {
    @Test
    fun failedLoadMoreWaitsForManualRetry() {
        var calls = 0
        var failNext = false
        val h = PagedHolder<String>(CoroutineScope(Dispatchers.Unconfined), {}, { it }) { key ->
            calls++
            if (failNext) throw IOException("断网")
            Page(listOf("item$calls"), JsonPrimitive("k$calls"))
        }
        h.refresh()
        assertEquals(1, calls)

        failNext = true
        h.loadMore()
        assertEquals(2, calls)
        assertNotNull(h.error)

        // 模拟滚动到底、筛选不够一屏等自动触发
        repeat(5) { h.loadMore() }
        assertEquals(2, calls)

        failNext = false
        h.retry()
        assertEquals(3, calls)
        assertEquals(null, h.error)
        assertEquals(listOf("item1", "item3"), h.items.toList())
    }
}
