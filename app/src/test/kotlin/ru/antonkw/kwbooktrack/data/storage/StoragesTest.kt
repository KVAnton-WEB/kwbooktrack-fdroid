package ru.antonkw.kwbooktrack.data.storage

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Real-on-disk tests for [FileCoverStorage] and [FilePhotoStorage].
 *
 * The NoOp stub in [ru.antonkw.kwbooktrack.data.NoOpCoverStorage] deliberately
 * swallows all writes, so it can never exercise the filename-uniqueness path
 * (fix #15), the cross-prefix scan, or the on-disk unlink of orphan files
 * (fix #18). These tests run under Robolectric against a temp filesDir and
 * cover the three bugs the stub can't see.
 */
@RunWith(RobolectricTestRunner::class)
class StoragesTest {

    private lateinit var coverStorage: FileCoverStorage
    private lateinit var photoStorage: FilePhotoStorage

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        coverStorage = FileCoverStorage(ctx)
        photoStorage = FilePhotoStorage(ctx)
    }

    @After
    fun tearDown() {
        val coversDir = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "covers")
        val photosDir = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "photos")
        coversDir.listFiles()?.forEach { it.delete() }
        photosDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun coverSaveTempWritesAUniqueFileEachCall() {
        // Two parallel saveTemp() calls in the same millisecond used to pick
        // the same tempId (=-currentTimeMillis()) and overwrite each other.
        // After fix #15, UUID suffixes make collisions impossible.
        val a = coverStorage.saveTemp(ByteArrayInputStream(byteArrayOf(0x01)), "jpg")
        val b = coverStorage.saveTemp(ByteArrayInputStream(byteArrayOf(0x02)), "jpg")
        assertThat(a).isNotEqualTo(b)
        assertThat(File(a).exists()).isTrue()
        assertThat(File(b).exists()).isTrue()
        assertThat(File(a).readBytes()).isEqualTo(byteArrayOf(0x01))
        assertThat(File(b).readBytes()).isEqualTo(byteArrayOf(0x02))
    }

    @Test
    fun coverPromoteToBookDeletesPreviousExtensions() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val coversDir = File(ctx.filesDir, "covers")
        // Pre-create a JPG and a PNG cover for book 42.
        File(coversDir, "42.jpg").writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        File(coversDir, "42.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50.toByte()))
        val newTemp = coverStorage.saveTemp(ByteArrayInputStream(byteArrayOf(0xAA.toByte())), "jpg")

        coverStorage.promoteToBook(42L, newTemp)

        assertThat(File(coversDir, "42.jpg").exists()).isTrue()
        assertThat(File(coversDir, "42.png").exists()).isFalse()
        assertThat(File(coversDir, "42.jpg").readBytes()).isEqualTo(byteArrayOf(0xAA.toByte()))
    }

    @Test
    fun coverDeleteForBookRemovesAllExtensionsForThatBook() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val coversDir = File(ctx.filesDir, "covers")
        File(coversDir, "10.jpg").writeBytes(byteArrayOf(1))
        File(coversDir, "10.webp").writeBytes(byteArrayOf(2))
        File(coversDir, "11.jpg").writeBytes(byteArrayOf(3))

        coverStorage.deleteForBook(10L)

        assertThat(File(coversDir, "10.jpg").exists()).isFalse()
        assertThat(File(coversDir, "10.webp").exists()).isFalse()
        assertThat(File(coversDir, "11.jpg").exists()).isTrue()
    }

    @Test
    fun photoSaveForNoteWritesUniqueFilesForParallelCalls() = runTest {
        // Same UUID-vs-timestamp fix as cover — two calls within the same ms
        // used to overwrite each other.
        val a = photoStorage.saveForNote(1L, ByteArrayInputStream(byteArrayOf(0x10)), "jpg")
        val b = photoStorage.saveForNote(1L, ByteArrayInputStream(byteArrayOf(0x20)), "jpg")
        assertThat(a).isNotEqualTo(b)
        assertThat(File(a).exists()).isTrue()
        assertThat(File(b).exists()).isTrue()
    }

    @Test
    fun photoDeleteForNoteRemovesOnlyThatNoteFiles() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val photosDir = File(ctx.filesDir, "photos")
        File(photosDir, "note_5_aaa.jpg").writeBytes(byteArrayOf(1))
        File(photosDir, "note_5_bbb.jpg").writeBytes(byteArrayOf(2))
        File(photosDir, "note_6_aaa.jpg").writeBytes(byteArrayOf(3))

        photoStorage.deleteForNote(5L)

        assertThat(File(photosDir, "note_5_aaa.jpg").exists()).isFalse()
        assertThat(File(photosDir, "note_5_bbb.jpg").exists()).isFalse()
        assertThat(File(photosDir, "note_6_aaa.jpg").exists()).isTrue()
    }

    @Test
    fun photoDeletePathsUnlinksExactlyTheListedFiles() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val photosDir = File(ctx.filesDir, "photos")
        val target = File(photosDir, "note_1_target.jpg").apply { writeBytes(byteArrayOf(1)) }
        val keep = File(photosDir, "note_1_keep.jpg").apply { writeBytes(byteArrayOf(2)) }
        val outsideCwd = File(ctx.filesDir, "outside.jpg").apply { writeBytes(byteArrayOf(3)) }

        photoStorage.deletePaths(listOf(target.absolutePath, outsideCwd.absolutePath))

        assertThat(target.exists()).isFalse()
        // deletePaths() must not unlink files outside the photos dir — that's
        // a security boundary, not just a hygiene nicety.
        assertThat(outsideCwd.exists()).isTrue()
        assertThat(keep.exists()).isTrue()
    }
}