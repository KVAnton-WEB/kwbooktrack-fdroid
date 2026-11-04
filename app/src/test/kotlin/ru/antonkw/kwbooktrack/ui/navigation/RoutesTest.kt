package ru.antonkw.kwbooktrack.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RoutesTest {

    @Test
    fun `note editor carries its kind in the route`() {
        // Тип заметки едет в маршруте: без него шаблон `?kind={kind}` с
        // defaultValue матчился бы и на маршрут без query, и FAB «добавить
        // заметку» открывал бы редактор рецензии.
        assertThat(Routes.noteEditor(42)).isEqualTo("books/42/notes/new?kind=NOTE")
        assertThat(Routes.noteEditorReview(42)).isEqualTo("books/42/notes/new?kind=REVIEW")
    }
}
