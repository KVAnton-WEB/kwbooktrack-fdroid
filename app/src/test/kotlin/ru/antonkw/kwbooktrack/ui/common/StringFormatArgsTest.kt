package ru.antonkw.kwbooktrack.ui.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.R

/**
 * Строка с `%1$s`/`%1$d`, отданная в UI без аргументов, показывается как есть —
 * «Куплено: %1$s» (так было в BookInfoDialog). Этот тест ловит такие строки
 * по списку ключей, которые UI использует как готовый текст.
 */
@RunWith(RobolectricTestRunner::class)
class StringFormatArgsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val usedAsPlainText = listOf(
        R.string.label_purchased,
        R.string.label_started,
        R.string.label_finished,
        R.string.label_year,
        R.string.label_page_count,
        R.string.label_price,
        R.string.label_genre,
        R.string.label_publisher,
    )

    @Test
    fun labelsUsedWithoutFormatArgsContainNoPlaceholders() {
        usedAsPlainText.forEach { res ->
            assertThat(context.getString(res)).doesNotContain("%")
        }
    }

    @Test
    fun onVariantsStillCarryTheDate() {
        assertThat(context.getString(R.string.label_purchased_on, "2026-03-28"))
            .contains("2026-03-28")
        assertThat(context.getString(R.string.label_started_on, "2026-11-12"))
            .contains("2026-11-12")
        assertThat(context.getString(R.string.label_finished_on, "2026-11-12"))
            .contains("2026-11-12")
    }
}