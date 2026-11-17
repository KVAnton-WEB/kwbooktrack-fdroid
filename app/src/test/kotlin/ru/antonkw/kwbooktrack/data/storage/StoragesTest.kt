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
        coverStorage = FileCoverStorage(ctx, CoverTranscoder())
        photoStorage = FilePhotoStorage(ctx)
    }

    @After
    fun tearDown() {
        val coversDir = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "covers")
        val photosDir = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "photos")
        coversDir.listFiles()?.forEach { it.delete() }
        photosDir.listFiles()?.forEach { it.delete() }
    }

    /** Real decodable PNG — saveTemp transcodes, so garbage input isn't a valid fixture. */
    private fun pngBytes(w: Int = 8, h: Int = 8, color: Int = android.graphics.Color.RED): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(color)
        return java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    @Test
    fun coverSaveTempWritesAUniqueFileEachCall() {
        // Two parallel saveTemp() calls in the same millisecond used to pick
        // the same tempId (=-currentTimeMillis()) and overwrite each other.
        // After fix #15, UUID suffixes make collisions impossible.
        val a = coverStorage.saveTemp(ByteArrayInputStream(pngBytes(color = android.graphics.Color.RED)), "png")
        val b = coverStorage.saveTemp(ByteArrayInputStream(pngBytes(color = android.graphics.Color.BLUE)), "png")
        assertThat(a).isNotEqualTo(b)
        assertThat(File(a).exists()).isTrue()
        assertThat(File(b).exists()).isTrue()
        // saveTemp is no longer a byte copy — it downscales and re-encodes, so
        // the stored bytes are a WebP, not the PNG that went in. (Distinct
        // colours can still encode to identical bytes at 8×8, so the
        // uniqueness that matters here is the path, asserted above.)
        assertThat(File(a).readBytes()).isNotEqualTo(pngBytes())
    }

    @Test
    fun coverFileKeepsUuidName() {
        // Имя обложки — UUID, а не id книги: у книг и фильмов id считаются
        // независимо, и имя по id у них совпадало бы.
        val temp = coverStorage.saveTemp(ByteArrayInputStream(pngBytes()), "png")

        assertThat(File(temp).name).startsWith("cover_")
        assertThat(File(temp).exists()).isTrue()
    }

    @Test
    fun coverDeleteRemovesOnlyGivenFile() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val coversDir = File(ctx.filesDir, "covers")
        val target = File(coversDir, "cover_a.webp").apply { writeBytes(byteArrayOf(1)) }
        val keep = File(coversDir, "cover_b.webp").apply { writeBytes(byteArrayOf(2)) }

        coverStorage.delete(target.absolutePath)

        assertThat(target.exists()).isFalse()
        assertThat(keep.exists()).isTrue()
    }

    @Test
    fun coverDeleteIgnoresNull() {
        coverStorage.delete(null)
    }

    @Test
    fun coverDeleteDoesNotTouchFilesOutsideCoversDir() {
        // delete() unlinks by exact path, so it must still refuse to touch
        // anything outside the covers directory — that is a safety boundary,
        // not just a hygiene nicety.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val outside = File(ctx.filesDir, "outside.webp").apply { writeBytes(byteArrayOf(1)) }

        coverStorage.delete(outside.absolutePath)

        assertThat(outside.exists()).isTrue()
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
    fun photoSaveNamedWritesFileUnderTheGivenName() {
        val path = photoStorage.saveNamed("5289df_42", ByteArrayInputStream(byteArrayOf(1, 2)), "jpg")
        assertThat(File(path).name).isEqualTo("5289df_42.jpg")
        assertThat(File(path).exists()).isTrue()
        assertThat(File(path).readBytes()).isEqualTo(byteArrayOf(1, 2))
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