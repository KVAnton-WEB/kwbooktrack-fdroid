package ru.antonkw.kwbooktrack.data.importer

import java.io.File
import java.util.zip.ZipFile

/** Формат файла импорта. */
enum class ImportFormat {
    /** Свой zip-бэкап приложения (`manifest.json` + `books.csv`). */
    KWBOOKTRACK_ZIP,

    /** Родной формат Tellico: `tellico.xml` (в `.tc` это zip). */
    TELLICO_XML,

    /** CSV-выгрузка Tellico. */
    TELLICO_CSV,
}

/**
 * Определяет формат файла импорта по содержимому — расширение у `.tc` то же,
 * что у zip, а переименованный файл ничего не говорит.
 */
object ImportFormatDetector {

    private const val HEAD_BYTES = 4096

    /**
     * @throws IllegalStateException если формат не распознан.
     */
    fun detect(file: File): ImportFormat {
        val head = file.inputStream().use { input ->
            ByteArray(HEAD_BYTES).also { input.read(it) }
        }
        if (!isZip(head)) {
            val text = String(head, Charsets.UTF_8)
            val isXml = text.contains("<tellico") || text.contains("<collection")
            return if (isXml) ImportFormat.TELLICO_XML else ImportFormat.TELLICO_CSV
        }
        val names = runCatching {
            ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        }.getOrElse {
            // Битый zip — не «формат не найден», но сообщение должно быть понятным.
            throw IllegalStateException("в архиве нет ни tellico.xml, ни CSV")
        }
        if (names.any { it.substringAfterLast('/') == "manifest.json" }) {
            return ImportFormat.KWBOOKTRACK_ZIP
        }
        if (names.any { it.substringAfterLast('/') == "tellico.xml" }) {
            return ImportFormat.TELLICO_XML
        }
        if (names.any { it.endsWith(".csv", ignoreCase = true) }) {
            return ImportFormat.TELLICO_CSV
        }
        throw IllegalStateException("в архиве нет ни tellico.xml, ни CSV")
    }

    /** ZIP начинается с сигнатуры `PK`. */
    private fun isZip(head: ByteArray): Boolean =
        head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
}
