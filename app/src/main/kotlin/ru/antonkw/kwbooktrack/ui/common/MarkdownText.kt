package ru.antonkw.kwbooktrack.ui.common

import android.graphics.Typeface
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
    val sizeSp = textStyle.fontSize.value
    val lineHeightSp = textStyle.lineHeight.value

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                typeface = Typeface.create(typeface, textStyle.fontStyle.toAndroidTypeface())
                setLineSpacing(0f, (lineHeightSp / sizeSp.coerceAtLeast(1f)))
                setTextIsSelectable(true)
                linksClickable = true
            }
        },
        update = { tv ->
            tv.setTextColor(onSurface)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            markwon.setMarkdown(tv, markdown.ifBlank { "" })
        },
    )
}

private fun androidx.compose.ui.text.font.FontStyle?.toAndroidTypeface(): Int = when (this) {
    androidx.compose.ui.text.font.FontStyle.Italic -> Typeface.ITALIC
    else -> Typeface.NORMAL
}
