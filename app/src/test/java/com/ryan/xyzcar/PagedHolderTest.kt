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

/** 加载更多失败后不能自动重试（断网时会变成一直发请求），只有用户点「重试」才再请求 */
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

        // 滚动到底、筛选不够一屏等自动触发：都不应该再发请求
        repeat(5) { h.loadMore() }
        assertEquals(2, calls)

        failNext = false
        h.retry()
        assertEquals(3, calls)
        assertEquals(null, h.error)
        assertEquals(listOf("item1", "item3"), h.items.toList())
    }
}
