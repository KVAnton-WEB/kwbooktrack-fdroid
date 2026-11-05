package ru.antonkw.kwbooktrack.data.snapshot

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Поиск файлов распакованного архива.
 *
 * Индекс строится один раз на прогон импорта: `find` зовётся на каждую книгу,
 * а обход каталога на каждую книгу — это O(книг × файлов).
 */
class ImportAssetsTest {

    private lateinit var base: File
    private lateinit var root: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("kwbooktrack-assets-").toFile()
        root = File(base, "archive").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    @Test
    fun findsFileByNameInSubdirectory() {
        val nested = File(root, "tellico/Books_files/abc.webp").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        assertThat(ImportAssets(root, null).find("abc.webp")).isEqualTo(nested)
    }

    @Test
    fun directPathHasPriorityOverNameSearch() {
        val direct = File(root, "abc.webp").apply { writeBytes(byteArrayOf(1)) }
        File(root, "sub/abc.webp").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(2))
        }

        assertThat(ImportAssets(root, null).find("abc.webp")).isEqualTo(direct)
    }

    @Test
    fun indexIsBuiltOnceAndReused() {
        // Файл, появившийся после первого поиска, в индекс уже не попадёт:
        // индекс — снимок каталога на момент первого обращения, а не обход на каждый вызов.
        File(root, "sub/a.webp").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val assets = ImportAssets(root, null)

        assertThat(assets.find("a.webp")).isNotNull()

        File(root, "sub/b.webp").writeBytes(byteArrayOf(2))

        // Не переобходили каталог — нового файла в индексе нет.
        assertThat(assets.find("b.webp")).isNull()
        // Найденный ранее файл находится и повторно: индекс переиспользуется.
        assertThat(assets.find("a.webp")).isNotNull()
    }

    @Test
    fun nameSearchKeepsWalkOrder() {
        // Из двух файлов с одинаковым именем берётся тот же, что и при обходе каталога.
        File(root, "a/abc.webp").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        File(root, "b/abc.webp").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(2))
        }
        val expected = root.walkTopDown().first { it.isFile && it.name == "abc.webp" }

        assertThat(ImportAssets(root, null).find("abc.webp")).isEqualTo(expected)
    }

    @Test
    fun missingRootReturnsNull() {
        val assets = ImportAssets(File(root, "нет-такого"), null)

        assertThat(assets.find("abc.webp")).isNull()
    }

    @Test
    fun pathOutsideRootIsRejected() {
        File(base, "outside.webp").writeBytes(byteArrayOf(1))

        assertThat(ImportAssets(root, null).find("../outside.webp")).isNull()
    }
}
