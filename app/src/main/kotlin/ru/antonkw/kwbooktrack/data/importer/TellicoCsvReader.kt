package ru.antonkw.kwbooktrack.data.importer

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import java.io.Reader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TellicoCsvReader @Inject constructor() {

    fun read(reader: Reader): List<CSVRecord> {
        val format = CSVFormat.Builder.create(CSVFormat.EXCEL)
            .setDelimiter(';')
            .setQuote('"')
            .setRecordSeparator("\n")
            .setIgnoreEmptyLines(true)
            .setHeader()
            .setSkipHeaderRecord(true)
            .build()

        return CSVParser(reader, format).use { it.records }
    }
}
