package ru.antonkw.kwbooktrack.data.importer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.antonkw.kwbooktrack.data.snapshot.ImportAssets
import ru.antonkw.kwbooktrack.data.snapshot.SnapshotApplier
import ru.antonkw.kwbooktrack.data.tellico.TellicoCsvReader
import ru.antonkw.kwbooktrack.data.tellico.TellicoRecord
import ru.antonkw.kwbooktrack.data.tellico.TellicoXmlReader
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * Импорт архива Tellico: `.tc` (zip с `tellico.xml`), zip с CSV или «голый»
 * CSV/XML-файл.
 *
 * Архив распаковывается во временный каталог рядом с выходным — оттуда
 * апплиер берёт обложки и фото. Каталог удаляется в `finally`.
 */
class ImportTellicoZipUseCase @Inject constructor(
    private val csvReader: TellicoCsvReader,
    private val xmlReader: TellicoXmlReader,
    private val mapper: TellicoBookMapper,
    private val applier: SnapshotApplier,
) {

    suspend operator fun invoke(
        file: File,
        format: ImportFormat,
        outputDir: File,
        dryRun: Boolean = false,
    ): ImportTellicoResult {
        if (!file.exists()) {
            return ImportTellicoResult(0, 0, 0, 0, 0, dryRun)
        }
        val staging = File(outputDir, ".import-staging")
        try {
            val records = withContext(Dispatchers.IO) { readRecords(file, format, staging) }
            val assets = ImportAssets(staging, outputDir)
            var imported = 0
            var skipped = 0
            var coversCopied = 0
            var notesImported = 0
            val errors = mutableListOf<Throwable>()
            for (record in records) {
                val snapshot = mapper.map(record)
                if (snapshot == null) {
                    skipped++
                    continue
                }
                if (dryRun) {
                    imported++
                    continue
                }
                try {
                    val stats = withContext(Dispatchers.IO) { applier.apply(snapshot, assets) }
                    imported++
                    coversCopied += stats.covers
                    notesImported += stats.notes + stats.sessions + stats.tags + stats.photos
                } catch (e: Exception) {
                    errors += e
                    skipped++
                }
            }
            return ImportTellicoResult(
                total = records.size,
                imported = imported,
                skipped = skipped,
                coversCopied = coversCopied,
                notesImported = notesImported,
                dryRun = dryRun,
                errors = errors,
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Читает записи Tellico из архива (или из самого файла, если это не zip). */
    private fun readRecords(file: File, format: ImportFormat, staging: File): List<TellicoRecord> {
        val zipped = isZip(file)
        // Для «голого» файла корень — его родительский каталог: рядом лежат обложки и фото.
        val root = if (zipped) {
            staging.deleteRecursively()
            staging.mkdirs()
            unzip(file, staging)
            staging
        } else {
            file.parentFile ?: File(".")
        }
        return when (format) {
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
    }

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

    /** Распаковка архива с сохранением путей. */
    private fun unzip(zip: File, target: File) {
        val root = target.canonicalPath
        ZipFile(zip).use { archive ->
            for (entry in archive.entries()) {
                val out = File(target, entry.name)
                // Путь из архива не должен уводить за пределы распаковки.
                if (!out.canonicalPath.startsWith(root + File.separator)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun isZip(file: File): Boolean =
        file.inputStream().use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }
}
