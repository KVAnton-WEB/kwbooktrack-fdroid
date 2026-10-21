package ru.antonkw.kwbooktrack.ui.common

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhotoGalleryDialogTest {

    @Test
    fun `saved photo is loaded as a file`() {
        val path = "/data/user/0/ru.antonkw.kwbooktrack/files/photos/note_1_1.jpg"
        val model = photoImageModel(path)
        assertThat(model).isInstanceOf(java.io.File::class.java)
        assertThat((model as java.io.File).path).isEqualTo(path)
    }

    // Regression: the gallery wrapped every path in File(), and File("content://…")
    // is a path that doesn't exist — pending photos showed up blank in the note
    // gallery, while their thumbnails in the editor (a real Uri) rendered fine.
    @Test
    fun `pending photo is loaded as a content uri`() {
        val uri = "content://media/external/images/media/42"
        val model = photoImageModel(uri)
        assertThat(model).isInstanceOf(Uri::class.java)
        assertThat((model as Uri).toString()).isEqualTo(uri)
    }
}
