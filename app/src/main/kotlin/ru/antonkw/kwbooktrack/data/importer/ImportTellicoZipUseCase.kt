package ru.antonkw.kwbooktrack.data.importer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.antonkw.kwbooktrack.data.tellico.TellicoCsvReader
import ru.antonkw.kwbooktrack.data.tellico.TellicoRecord
import ru.antonkw.kwbooktrack.data.tellico.TellicoXmlReader
import java.io.File
import javax.inject.Inject

/**
 * Импорт архива Tellico: `.tc` (zip с `tellico.xml`), zip с CSV или «голый»
 * CSV/XML-файл.
 *
 * Архив распаковывается во временный каталог рядом с выходным — оттуда
 * апплиер берёт обложки и фото. Каталог удаляется в `finally`.
 *
 * Записи → снимки → БД сливает [ImportTellicoUseCase]: этот класс отвечает
 * только за файл (архив, распаковка, формат).
 */
class ImportTellicoZipUseCase @Inject constructor(
    private val importUseCase: ImportTellicoUseCase,
    private val csvReader: TellicoCsvReader,
    private val xmlReader: TellicoXmlReader,
) {

    suspend operator fun invoke(
        file: File,
        format: ImportFormat,
        outputDir: File,
        dryRun: Boolean = false,
    ): ImportTellicoResult {
        if (!file.exists()) {
            // Не «успешный» импорт в пустоту: UI должен показать ошибку.
            return ImportTellicoResult(
                total = 0,
                imported = 0,
                skipped = 0,
                coversCopied = 0,
                notesImported = 0,
                dryRun = dryRun,
                errors = listOf(IllegalStateException("файл не найден: ${file.absolutePath}")),
            )
        }
        val staging = File(outputDir, ".import-staging")
        try {
            val prepared = withContext(Dispatchers.IO) { prepare(file, format, staging) }
            // Корень ассетов: распакованный архив; обложки кладутся в outputDir.
            return importUseCase(
                prepared.records,
                ImportTellicoOptions(
                    assetRoot = prepared.root,
                    coverOutputDir = outputDir,
                    dryRun = dryRun,
                ),
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Готовит архив к чтению: распаковывает zip (или берёт родительский каталог
     * «голого» файла) и читает записи.
     *
     * [Prepared.root] — корень ассетов: обложки и фото лежат внутри него.
     */
    private fun prepare(file: File, format: ImportFormat, staging: File): Prepared {
        val zipped = isZip(file)
        // Для «голого» файла корень — его родительский каталог: рядом лежат обложки и фото.
        val root = if (zipped) {
            staging.deleteRecursively()
            staging.mkdirs()
            unzipLimited(file, staging)
            staging
        } else {
            file.parentFile ?: File(".")
        }
        val records = when (format) {
            ImportFormat.TELLICO_XML -> {
                val xml = source(root, file, zipped) { it.name == "tellico.xml" }
                    ?: throw IllegalStateException("в архиве нет tellico.xml")
                xmlReader.read(xml.readBytes())
            }
            ImportFormat.TELLICO_CSV -> {
                val csv = source(root, file, zipped) { it.name.endsWith(".csv", ignoreCase = true) }
                    ?: throw IllegalStateException("в архиве нет CSV")
                csv.inputStream().bufferedReader().use { reader -> csvReader.read(reader) }
            }
            ImportFormat.KWBOOKTRACK_ZIP ->
                throw IllegalStateException("свой бэкап импортируется отдельным импортёром")
        }
        return Prepared(root, records)
    }

    /** Корень ассетов и прочитанные записи. */
    private class Prepared(val root: File, val records: List<TellicoRecord>)

    /**
     * Файл-источник: в zip ищем по имени, «голый» файл берём как есть —
     * его формап уже определён по содержимому, а имя может быть любым.
     */
    private fun source(root: File, file: File, zipped: Boolean, predicate: (File) -> Boolean): File? =
        if (zipped) {
            root.walkTopDown().firstOrNull { it.isFile && predicate(it) }
        } else {
            file.takeIf { it.isFile }
        }

    private fun isZip(file: File): Boolean =
        file.inputStream().use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }
}
