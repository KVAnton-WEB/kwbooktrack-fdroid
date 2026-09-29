package ru.antonkw.kwbooktrack.data.importer

import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import java.io.File
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.inject.Inject
import kotlin.Unit

class ImportTellicoZipUseCase @Inject constructor(
    private val csvReader: TellicoCsvReader,
    private val mapper: TellicoBookMapper,
    private val bookRepository: BookRepository,
    private val noteRepository: NoteRepository,
) {
    suspend operator fun invoke(
        zipFile: File,
        coverOutputDir: File,
        dryRun: Boolean = false,
    ): ImportTellicoResult {
        if (!zipFile.exists()) {
            return ImportTellicoResult(
                total = 0,
                imported = 0,
                skipped = 0,
                coversCopied = 0,
                notesImported = 0,
                dryRun = dryRun,
            )
        }

        val csvContent = StringBuilder()
        val extractedCovers = File(coverOutputDir, "covers").apply { mkdirs() }
        var coversExtracted = 0
        // The cover files in a Tellico ZIP live under <prefix>/Books_files/, e.g. "tellico/Books_files/abc.jpeg".
        // We auto-detect the prefix from the first matching entry so users don't need to know it.
        var coverDirPrefix: String? = null
        val imageExt = setOf("jpg", "jpeg", "png", "webp")

        ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            var entry: ZipEntry? = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name
                    val ext = name.substringAfterLast('.', "").lowercase()
                    when {
                        name.endsWith(".csv", ignoreCase = true) && csvContent.isEmpty() -> {
                            csvContent.append(zin.readBytes().toString(Charsets.UTF_8))
                        }
                        ext in imageExt -> {
                            if (coverDirPrefix == null && "/" in name) {
                                val idx = name.lastIndexOf("/Books_files/")
                                if (idx >= 0) {
                                    coverDirPrefix = name.substring(0, idx + "/Books_files/".length)
                                }
                            }
                            if (!dryRun) {
                                val basename = File(name).name
                                val target = File(extractedCovers, basename)
                                target.outputStream().use { out -> zin.copyTo(out) }
                                coversExtracted++
                            }
                        }
                    }
                }
                entry = zin.nextEntry
            }
        }

        if (csvContent.isEmpty()) {
            return ImportTellicoResult(
                total = 0,
                imported = 0,
                skipped = 0,
                coversCopied = 0,
                notesImported = 0,
                dryRun = dryRun,
            )
        }

        val useCase = ImportTellicoUseCase(
            csvReader = csvReader,
            mapper = mapper,
            bookRepository = bookRepository,
            noteRepository = noteRepository,
        )
        val result = useCase(
            input = StringReader(csvContent.toString()),
            options = ImportTellicoOptions(
                coverSourceDir = if (dryRun) null else extractedCovers,
                coverOutputDir = coverOutputDir,
                dryRun = dryRun,
            ),
        )
        return result.copy(coversCopied = if (dryRun) 0 else coversExtracted)
    }
}
