package ru.antonkw.kwbooktrack.ui.common

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon

/**
 * TextView под Markwon: ссылки кликабельны и открываются в браузере.
 *
 * Markwon оборачивает markdown-ссылки в `ClickableSpan`, а клик по такому
 * span'у обрабатывает только `LinkMovementMethod` — с `ArrowKeyMovementMethod`
 * (его ставит `setTextIsSelectable`) ссылка остаётся «мёртвой».
 */
internal fun markdownTextView(context: Context): TextView = TextView(context).apply {
    setTextIsSelectable(true)
    linksClickable = true
    movementMethod = LinkMovementMethod.getInstance()
}

/**
 * Подсвечивает ссылки: Markwon сам их не красит, и без цвета они
 * неотличимы от обычного текста — по ним никто не кликнет.
 */
internal fun highlightLinks(view: TextView, linkColor: Int) {
    val spanned = view.text as? Spannable ?: return
    spanned.getSpans(0, spanned.length, ClickableSpan::class.java).forEach { span ->
        spanned.setSpan(
            ForegroundColorSpan(linkColor),
            spanned.getSpanStart(span),
            spanned.getSpanEnd(span),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}

/**
 * Renders [markdown] through Markwon (Android TextView wrapped in AndroidView).
 *
 * The Markwon instance is remembered per-context — it's stateless apart from a
 * tiny cache, so reusing it is safe. Recomposition triggers `setMarkdown` on the
 * same TextView, which Markwon treats as a full re-render.
 *
 * ponytail: MinSdk = 31, so we don't need any of the API-25 SpannableString
 * workarounds Markwon ships. If we drop to API 25, swap in Markwon.builder(...).
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    val onSurface = textColor.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val sizeSp = textStyle.fontSize.value
    val lineHeightSp = textStyle.lineHeight.value

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            markdownTextView(ctx).apply {
                setTextColor(onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                typeface = Typeface.create(typeface, textStyle.fontStyle.toAndroidTypeface())
                setLineSpacing(0f, (lineHeightSp / sizeSp.coerceAtLeast(1f)))
            }
        },
        update = { tv ->
            tv.setTextColor(onSurface)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            markwon.setMarkdown(tv, markdown.ifBlank { "" })
            highlightLinks(tv, linkColor)
        },
    )
}

private fun androidx.compose.ui.text.font.FontStyle?.toAndroidTypeface(): Int = when (this) {
    androidx.compose.ui.text.font.FontStyle.Italic -> Typeface.ITALIC
    else -> Typeface.NORMAL
}
