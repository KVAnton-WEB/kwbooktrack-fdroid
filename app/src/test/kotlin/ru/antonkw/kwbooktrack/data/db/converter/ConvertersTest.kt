package ru.antonkw.kwbooktrack.data.db.converter

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField

/**
 * Конвертер чужих полей Tellico: Map<String, ExtraField> ↔ JSON в колонке.
 */
class ConvertersTest {

    private val converters = Converters()

    @Test
    fun extraFieldsAreStoredAsJson() {
        val json = converters.fromExtraFields(mapOf("my_field" to ExtraField(title = "Моё поле", value = "42")))

        assertThat(json).isEqualTo("""{"my_field":{"t":"Моё поле","v":"42"}}""")
    }

    @Test
    fun emptyExtraFieldsAreStoredAsEmptyJson() {
        assertThat(converters.fromExtraFields(emptyMap())).isEqualTo("{}")
        assertThat(converters.toExtraFields("{}")).isEmpty()
    }

    @Test
    fun extraFieldsSurviveTheRoundTrip() {
        val fields = mapOf(
            "first" to ExtraField(title = "Первое", value = "1"),
            "second" to ExtraField(title = "Второе", value = "2"),
        )

        assertThat(converters.toExtraFields(converters.fromExtraFields(fields))).isEqualTo(fields)
    }

    @Test
    fun brokenJsonIsNotFatal() {
        // Колонка могла остаться битой (ручная правка, старый экспорт) —
        // чтение не должно ронять приложение.
        assertThat(converters.toExtraFields("не json")).isEmpty()
        assertThat(converters.toExtraFields("")).isEmpty()
    }
}
