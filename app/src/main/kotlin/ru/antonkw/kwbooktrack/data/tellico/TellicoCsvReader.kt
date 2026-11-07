package ru.antonkw.kwbooktrack.data.tellico

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField
import java.io.Reader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TellicoCsvReader @Inject constructor() {

    /**
     * Читает CSV Tellico в промежуточную модель: известные поля — в [TellicoRecord.fields]
     * по внутренним именам Tellico, чужие — в [TellicoRecord.extra].
     */
    fun read(reader: Reader): List<TellicoRecord> =
        CSVParser(reader, FORMAT).use { parser ->
            val headers = parser.headerNames
            parser.records.map { record -> record.toTellicoRecord(headers) }
        }

    private fun CSVRecord.toTellicoRecord(headers: List<String>): TellicoRecord {
        val fields = mutableMapOf<String, String>()
        val extra = mutableMapOf<String, ExtraField>()
        headers.forEachIndexed { index, header ->
            if (header in TellicoFields.SKIP_TITLES) return@forEachIndexed
            // Строка может быть короче заголовка: get(header) бросил бы исключение
            // вместо null, а одна кривая строка не должна ронять весь импорт.
            val value = if (index < size()) get(index).trim() else ""
            if (value.isEmpty()) return@forEachIndexed
            val name = TellicoFields.NAME_BY_TITLE[header]
                ?: TellicoFields.EXTRA_NAME_BY_TITLE[header]
                ?: TellicoFields.nameFromTitle(header)
            // Заголовок из одних знаков препинания имени не даёт — колонку пропускаем.
            if (name.isEmpty()) return@forEachIndexed
            if (name in TellicoFields.KNOWN) {
                fields[name] = value
            } else {
                extra[name] = ExtraField(title = header, value = value)
            }
        }
        return TellicoRecord(fields, extra)
    }

    private companion object {
        val FORMAT: CSVFormat = CSVFormat.Builder.create(CSVFormat.EXCEL)
            .setDelimiter(';')
            .setQuote('"')
            .setRecordSeparator("\n")
            .setIgnoreEmptyLines(true)
            .setHeader()
            .setSkipHeaderRecord(true)
            .build()
    }
}
