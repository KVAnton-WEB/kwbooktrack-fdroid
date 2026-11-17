package ru.antonkw.kwbooktrack.ui.common

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import com.google.common.truth.Truth.assertThat
import io.noties.markwon.Markwon
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Ссылка в markdown должна открываться в браузере, а не быть «мёртвым» текстом:
 * для этого TextView нужен LinkMovementMethod.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownTextLinkTest {

    @Test
    fun markdownLinkOpensInBrowser() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = markdownTextView(activity)
        Markwon.create(activity).setMarkdown(view, "Смотри [LiveLib](https://livelib.ru/book/1)")

        val span = (view.text as Spannable)
            .getSpans(0, view.text.length, ClickableSpan::class.java)
            .firstOrNull()
        assertThat(span).isNotNull()

        // Без LinkMovementMethod клик по ссылке не доходит до span'а:
        // setTextIsSelectable ставит ArrowKeyMovementMethod, и ссылка мертва.
        assertThat(view.movementMethod).isInstanceOf(LinkMovementMethod::class.java)

        span!!.onClick(view)

        val intent = shadowOf(activity).nextStartedActivity
        assertThat(intent?.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent?.data?.toString()).isEqualTo("https://livelib.ru/book/1")
    }

    @Test
    fun linksAreHighlighted() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = markdownTextView(activity)
        Markwon.create(activity).setMarkdown(view, "Смотри [LiveLib](https://livelib.ru/book/1)")

        highlightLinks(view, Color.RED)

        val colors = (view.text as Spannable)
            .getSpans(0, view.text.length, ForegroundColorSpan::class.java)
        assertThat(colors).hasLength(1)
        assertThat(colors.first().foregroundColor).isEqualTo(Color.RED)
    }
}
