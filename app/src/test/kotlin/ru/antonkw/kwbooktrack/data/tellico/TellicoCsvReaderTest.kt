package ru.antonkw.kwbooktrack.data.tellico

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField
import java.io.StringReader

class TellicoCsvReaderTest {

    @Test
    fun knownHeadersBecomeInternalFieldNames() {
        val record = record(
            "Заголовок" to "Властелин Колец",
            "Автор" to "Толкин, Джон",
            "ISBN#" to "978-5-04168446-4",
            "Усл. печ. л." to "32",
        )

        assertThat(record.fields["title"]).isEqualTo("Властелин Колец")
        assertThat(record.fields["author"]).isEqualTo("Толкин, Джон")
        assertThat(record.fields["isbn"]).isEqualTo("978-5-04168446-4")
        assertThat(record.fields["усл.-печ.-л."]).isEqualTo("32")
    }

    @Test
    fun soldHeaderUsesTellicoFieldName() {
        // Review Focus #5: по правилу выводится «продано», но в коллекции поле
        // называется «продано-new» — иначе Tellico завёл бы второе поле.
        val record = record("Продано?" to "нет")

        assertThat(record.fields["продано-new"]).isEqualTo("нет")
        assertThat(record.fields).doesNotContainKey("продано")
    }

    @Test
    fun unknownHeadersGoToExtraWithTheirTitle() {
        val record = record(
            "Чтец" to "Иванова",
            "livelib/fantlab" to "https://livelib.ru",
        )

        assertThat(record.extra["чтец"]).isEqualTo(ExtraField(title = "Чтец", value = "Иванова"))
        assertThat(record.extra["livelib-fantlab"])
            .isEqualTo(ExtraField(title = "livelib/fantlab", value = "https://livelib.ru"))
        assertThat(record.fields).doesNotContainKey("чтец")
    }

    @Test
    fun serviceNumberColumnIsSkipped() {
        val record = record(
            "Номер" to "42",
            "Заголовок" to "Дюна",
        )

        assertThat(record.fields).doesNotContainKey("номер")
        assertThat(record.extra).doesNotContainKey("номер")
        assertThat(record.fields["title"]).isEqualTo("Дюна")
    }

    @Test
    fun emptyCellsAreNotStored() {
        val record = record(
            "Заголовок" to "Дюна",
            "Подзаголовок" to "",
            "Чтец" to "   ",
        )

        assertThat(record.fields).doesNotContainKey("subtitle")
        assertThat(record.extra).doesNotContainKey("чтец")
    }

    @Test
    fun nameIsDerivedFromTitleByTellicoRule() {
        assertThat(TellicoFields.nameFromTitle("ISBN#")).isEqualTo("isbn")
        assertThat(TellicoFields.nameFromTitle("livelib/fantlab")).isEqualTo("livelib-fantlab")
        assertThat(TellicoFields.nameFromTitle("Продано?")).isEqualTo("продано")
        assertThat(TellicoFields.nameFromTitle("Усл. печ. л.")).isEqualTo("усл.-печ.-л.")
    }

    private fun record(vararg pairs: Pair<String, String>): TellicoRecord {
        val headers = pairs.map { it.first }
        val values = pairs.map { (_, v) -> if (v.contains(';') || v.contains('"')) "\"$v\"" else v }
        val csv = headers.joinToString(";") + "\n" + values.joinToString(";") + "\n"
        return TellicoCsvReader().readRecords(StringReader(csv)).single()
    }
}
