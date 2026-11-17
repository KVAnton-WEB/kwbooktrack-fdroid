package ru.antonkw.kwbooktrack.data.repository

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.storage.FilePhotoStorage
import java.io.File

/**
 * Фото на диске и в базе — одно и то же.
 *
 * Удаление строки без удаления файла оставляет мусор: `deleteForNote` знает
 * только имена `note_{id}_*`, а импортированные фото лежат под именами
 * `{md5}_{тип}_{id}`.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoRepositoryTest {

    private lateinit var db: KwBooktrackDatabase
    private lateinit var repo: PhotoRepository

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        repo = PhotoRepository(db.photoDao(), FilePhotoStorage(ApplicationProvider.getApplicationContext()))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun deleteRemovesFileFromDisk() = runTest {
        val file = photoFile("note_1_abc.jpg")
        repo.insert(
            PhotoEntity(
                mediaId = 1L,
                mediaType = MediaType.BOOKS,
                noteId = null,
                path = file.absolutePath,
                createdAt = 0L,
            ),
        )
        val photo = db.photoDao().getAll().single()

        repo.delete(photo)

        assertThat(db.photoDao().getAll()).isEmpty()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun deleteRemovesFileWithImportedName() = runTest {
        // Импортированное фото лежит под именем `{md5}_{тип}_{id}` — и раньше
        // `deleteForNote` его не трогал.
        val file = photoFile("d41d8cd98f00b204_books_1.jpg")
        repo.insert(
            PhotoEntity(
                mediaId = 1L,
                mediaType = MediaType.BOOKS,
                noteId = null,
                path = file.absolutePath,
                createdAt = 0L,
            ),
        )
        val photo = db.photoDao().getAll().single()

        repo.delete(photo)

        assertThat(file.exists()).isFalse()
    }

    private fun photoFile(name: String): File {
        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "photos")
            .apply { mkdirs() }
        return File(dir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }
}
