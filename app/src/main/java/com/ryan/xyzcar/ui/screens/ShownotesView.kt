package com.ryan.xyzcar.ui.screens

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import com.ryan.xyzcar.data.Episode
import com.ryan.xyzcar.ui.AppViewModel
import com.ryan.xyzcar.ui.ShownotesParser

/** F12 shownotes：HTML 渲染，时间戳可点击跳转 */
@Composable
fun ShownotesView(vm: AppViewModel, ep: Episode, modifier: Modifier = Modifier) {
    val text = remember(ep.eid, ep.shownotes, ep.description) { buildShownotes(vm, ep) }
    val fg = MaterialTheme.colorScheme.onSurface.toArgb()
    val link = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setLineSpacing(0f, 1.35f)
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { tv ->
            tv.setTextColor(fg)
            tv.setLinkTextColor(link)
            tv.text = text
        },
    )
}

private fun buildShownotes(vm: AppViewModel, ep: Episode): CharSequence {
    val html = ep.shownotes.ifEmpty { ep.description.replace("\n", "<br>") }
        .replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), "")
    val spanned = SpannableStringBuilder(HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT))
    // 去掉末尾空行
    while (spanned.isNotEmpty() && spanned.last().isWhitespace()) spanned.delete(spanned.length - 1, spanned.length)
    ShownotesParser.timestamps(spanned.toString()).forEach { st ->
        spanned.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) = vm.player.playAt(ep, st.seconds * 1000L)
            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = false
                ds.isFakeBoldText = true
            }
        }, st.start, st.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return spanned
}
