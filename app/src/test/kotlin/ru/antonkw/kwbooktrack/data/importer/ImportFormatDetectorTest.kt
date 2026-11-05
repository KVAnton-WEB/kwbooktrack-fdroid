package ru.antonkw.kwbooktrack.data.importer

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Определение формата импорта по содержимому файла: свой бэкап, родной
 * Tellico (XML) и CSV.
 */
class ImportFormatDetectorTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("kwbooktrack-detect-").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun zipWithManifestIsKwbooktrackBackup() {
        val file = zip("manifest.json" to "{}", "books.csv" to "title;author")

        assertThat(ImportFormatDetector.detect(file)).isEqualTo(ImportFormat.KWBOOKTRACK_ZIP)
    }

    @Test
    fun zipWithTellicoXmlIsTellicoXml() {
        val file = zip("tellico.xml" to "<tellico/>")

        assertThat(ImportFormatDetector.detect(file)).isEqualTo(ImportFormat.TELLICO_XML)
    }

    @Test
    fun zipWithNestedTellicoXmlIsTellicoXml() {
        val file = zip("tellico/tellico.xml" to "<tellico/>")

        assertThat(ImportFormatDetector.detect(file)).isEqualTo(ImportFormat.TELLICO_XML)
    }

    @Test
    fun zipWithCsvIsTellicoCsv() {
        val file = zip("tellico/tellico.csv" to "Заголовок;Автор")

        assertThat(ImportFormatDetector.detect(file)).isEqualTo(ImportFormat.TELLICO_CSV)
    }

    @Test
    fun zipWithoutTellicoFilesFails() {
        val file = zip("readme.txt" to "ничего тут нет")

        val error = runCatching { ImportFormatDetector.detect(file) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error).hasMessageThat().contains("tellico.xml")
    }

    @Test
    fun brokenZipIsReportedAsUnknownFormat() {
        // Сигнатура zip есть, а сам архив битый — пользователю нужно понятное сообщение.
        val file = file("broken.zip", "PK мусор")

        val error = runCatching { ImportFormatDetector.detect(file) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error).hasMessageThat().contains("tellico.xml")
    }

    @Test
    fun xmlFileIsTellicoXml() {
        val file = file("books.xml", "<?xml version=\"1.0\"?><tellico><collection/></tellico>")

        assertThat(ImportFormatDetector.detect(file)).isEqualTo(ImportFormat.TELLICO_XML)
    }

    @Test
    fun collectionTagIsTellicoXml() {
        val file = file("books.xml", "<?xml version=\"1.0\"?><collection type=\"2\"/>")

        assertThat(ImportFormatDetector.detect(file)).isEqualTo(ImportFormat.TELLICO_XML)
    }

    @Test
    fun csvFileIsTellicoCsv() {
        val file = file("books.csv", "Заголовок;Автор\nДюна;Герберт")

        assertThat(ImportFormatDetector.detect(file)).isEqualTo(ImportFormat.TELLICO_CSV)
    }

    private fun file(name: String, content: String): File =
        File(dir, name).apply { writeText(content) }

    private var archiveCount = 0

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = File(dir, "archive-${archiveCount++}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }
}
