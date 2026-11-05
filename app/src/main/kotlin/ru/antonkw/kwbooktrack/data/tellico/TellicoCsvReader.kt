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

    /** Старый API: список сырых записей CSV. Уходит вместе с маппером в Task 6. */
    fun read(reader: Reader): List<CSVRecord> =
        CSVParser(reader, FORMAT).use { it.records }

    /**
     * Читает CSV Tellico в промежуточную модель: известные поля — в [TellicoRecord.fields]
     * по внутренним именам Tellico, чужие — в [TellicoRecord.extra].
     */
    fun readRecords(reader: Reader): List<TellicoRecord> =
        CSVParser(reader, FORMAT).use { parser ->
            val headers = parser.headerNames
            parser.records.map { record -> record.toTellicoRecord(headers) }
        }

    private fun CSVRecord.toTellicoRecord(headers: List<String>): TellicoRecord {
        val fields = mutableMapOf<String, String>()
        val extra = mutableMapOf<String, ExtraField>()
        for (header in headers) {
            if (header in TellicoFields.SKIP_TITLES) continue
            val value = get(header)?.trim().orEmpty()
            if (value.isEmpty()) continue
            val name = TellicoFields.NAME_BY_TITLE[header] ?: TellicoFields.nameFromTitle(header)
            if (name in TellicoFields.KNOWN) fields[name] = value else extra[name] = ExtraField(title = header, value = value)
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
