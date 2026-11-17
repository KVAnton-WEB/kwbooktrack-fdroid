package ru.antonkw.kwbooktrack.ui.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration

/**
 * Значение-ссылка (например, поле «кинопоиск»): клик открывает браузер.
 *
 * В markdown ссылки кликабельны сами (см. [MarkdownText]), но обычные поля
 * Tellico — это голый URL, его нужно превратить в ссылку вручную.
 */
@Composable
fun UrlText(
    url: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val context = LocalContext.current
    Text(
        text = url,
        style = style.copy(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
        modifier = modifier.clickable { openInBrowser(context, url) },
    )
}

/** Значение, если это http(s)-ссылка; иначе null. */
fun asUrl(value: String?): String? = value?.trim()?.takeIf {
    it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
}

/** Открывает ссылку во внешнем браузере. Устройства без браузера не падают. */
fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
