package ru.antonkw.kwbooktrack.data.importer

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Распаковка архива с лимитами.
 *
 * Архив приходит из пользовательского выбора, и без счётчика «бомба» из
 * миллиона мелких записей распаковывается целиком и съедает память
 * устройства. Защита от выхода за пределы каталога (zip-slip) от этого не
 * спасает.
 */
class UnzipTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("unzip-test-").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun ordinaryArchiveIsUnpacked() {
        val zip = zipOf("a.txt" to "привет")
        val target = File(dir, "out")

        unzipLimited(zip, target)

        assertThat(File(target, "a.txt").readText()).isEqualTo("привет")
    }

    @Test
    fun oversizedArchiveIsRejected() {
        val zip = zipOf("big.bin" to "x".repeat(10_000))
        val target = File(dir, "out")

        val error = runCatching { unzipLimited(zip, target, maxBytes = 1_000L) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java)
    }

    @Test
    fun tooManyEntriesAreRejected() {
        val zip = File(dir, "many.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            repeat(5) { index ->
                out.putNextEntry(ZipEntry("f$index.txt"))
                out.write("данные".toByteArray())
                out.closeEntry()
            }
        }
        val target = File(dir, "out")

        val error = runCatching { unzipLimited(zip, target, maxEntries = 3) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IOException::class.java)
    }

    @Test
    fun entryOutsideTargetIsSkipped() {
        val zip = File(dir, "slip.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("../escaped.txt"))
            out.write("побег".toByteArray())
            out.closeEntry()
        }
        val target = File(dir, "out")

        unzipLimited(zip, target)

        assertThat(File(dir, "escaped.txt").exists()).isFalse()
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val zip = File(dir, "archive.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return zip
    }
}
