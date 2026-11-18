package ru.antonkw.kwbooktrack.data.importer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.antonkw.kwbooktrack.data.tellico.TellicoCsvReader
import ru.antonkw.kwbooktrack.data.tellico.TellicoRecord
import ru.antonkw.kwbooktrack.data.tellico.TellicoXmlReader
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
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
    ): ImportTellicoResult {
        if (!file.exists()) {
            // Не «успешный» импорт в пустоту: UI должен показать ошибку.
            return ImportTellicoResult(
                total = 0,
                imported = 0,
                skipped = 0,
                coversCopied = 0,
                notesImported = 0,
                errors = listOf(IllegalStateException("файл не найден: ${file.absolutePath}")),
            )
        }
        // Каталог распаковки — свой на каждый импорт: два параллельных импорта
        // (или импорт, прерванный пользователем) делили бы один каталог, и
        // `deleteRecursively()` в `finally` сносил бы чужую распаковку.
        // Хвосты прерванных импортов убираем только со своим префиксом:
        // чужой staging (kw-импорт) может быть ещё активен.
        val staging = StagingDirs.create(outputDir, "tellico")
        StagingDirs.cleanOrphans(outputDir, "tellico")
        try {
            val prepared = withContext(Dispatchers.IO) { prepare(file, format, staging) }
            // Корень ассетов: распакованный архив; обложки кладутся в outputDir.
            return importUseCase(
                prepared.records,
                ImportTellicoOptions(
                    assetRoot = prepared.root,
                    coverOutputDir = outputDir,
                ),
            )
        } finally {
            StagingDirs.release(staging)
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
                // Потоковое чтение: `readBytes()` грузил весь файл в память
                // целиком, и большая коллекция (десятки мегабайт XML) давала OOM.
                // Парсер собирает дерево документа — это его предел: см.
                // `TellicoXmlReader`.
                xmlReader.read(xml)
            }
            ImportFormat.TELLICO_CSV -> {
                val csv = source(root, file, zipped) { it.name.endsWith(".csv", ignoreCase = true) }
                    ?: throw IllegalStateException("в архиве нет CSV")
                // Потоковое чтение с определением кодировки на лету: `readText()`
                // держал в памяти три копии файла (байты, строка, записи).
                InputStreamReader(csv.inputStream(), detectCharset(csv)).use { reader ->
                    csvReader.read(reader)
                }
            }
            ImportFormat.KWBOOKTRACK_ZIP ->
                throw IllegalStateException("свой бэкап импортируется отдельным импортёром")
        }
        return Prepared(root, records)
    }

    /**
     * Кодировка файла: UTF-8, а если он не UTF-8 — cp1251.
     *
     * Кодировку платформы брать нельзя: выгрузка Tellico под Windows — это
     * cp1251, и в русской локали весь текст превращался бы в мусор.
     *
     * Проверяем **весь** файл, а не первые 64 КБ: кириллица может начаться
     * позже, а `InputStreamReader` при неверной кодировке не падает, а
     * подставляет U+FFFD — импорт проходил успешно, а названия приходили битые.
     *
     * Файл в память не грузится: читаем чанками, декодер сам помнит
     * непрочитанную последовательность между ними.
     */
    private fun detectCharset(file: File): Charset {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        var utf8 = false
        runCatching {
            file.inputStream().use { input ->
                // +4 — запас на непрочитанный хвост последовательности.
                val inBuf = ByteBuffer.allocate(CHUNK_BYTES + 4)
                val out = CharBuffer.allocate(CHUNK_BYTES + 4)
                inBuf.clear()
                var eof = false
                var ok = true
                while (ok) {
                    if (!eof) {
                        val read = input.read(inBuf.array(), inBuf.position(), inBuf.remaining())
                        if (read < 0) eof = true else inBuf.position(inBuf.position() + read)
                    }
                    inBuf.flip()
                    val result = decoder.decode(inBuf, out, eof)
                    if (result.isError) {
                        ok = false
                        break
                    }
                    if (result.isOverflow) {
                        // Выходной буфер переполнен — даём место и продолжаем.
                        out.clear()
                        inBuf.compact()
                        continue
                    }
                    if (eof) break
                    inBuf.compact()
                    out.clear()
                }
                utf8 = ok
            }
        }.onFailure { utf8 = false }
        return if (utf8) Charsets.UTF_8 else CHARSET_CP1251
    }

    private companion object {
        /** Сколько байт читать за раз при определении кодировки. */
        const val CHUNK_BYTES = 64 * 1024

        /** Кодировка выгрузки Tellico под Windows. */
        val CHARSET_CP1251: Charset = Charset.forName("windows-1251")
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
