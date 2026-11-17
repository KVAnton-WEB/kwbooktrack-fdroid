package ru.antonkw.kwbooktrack.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UrlTextTest {

    @Test
    fun httpUrlIsRecognized() {
        assertThat(asUrl("https://livelib.ru")).isEqualTo("https://livelib.ru")
        assertThat(asUrl(" http://livelib.ru ")).isEqualTo("http://livelib.ru")
    }

    @Test
    fun nonUrlValueIsNotALink() {
        assertThat(asUrl("По подписке")).isNull()
        assertThat(asUrl("")).isNull()
        assertThat(asUrl(null)).isNull()
    }
}
