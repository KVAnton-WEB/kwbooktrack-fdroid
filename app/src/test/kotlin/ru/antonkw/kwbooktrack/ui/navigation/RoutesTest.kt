package ru.antonkw.kwbooktrack.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.antonkw.kwbooktrack.data.db.entity.MediaType

class RoutesTest {

    @Test
    fun `note editor carries its kind in the route`() {
        // Тип заметки едет в маршруте: без него шаблон `?kind={kind}` с
        // defaultValue матчился бы и на маршрут без query, и FAB «добавить
        // заметку» открывал бы редактор рецензии.
        assertThat(Routes.noteEditor(42, MediaType.BOOKS))
            .isEqualTo("notes/new/42/BOOKS?kind=NOTE")
        assertThat(Routes.noteEditorReview(42, MediaType.BOOKS))
            .isEqualTo("notes/new/42/BOOKS?kind=REVIEW")
        // Владелец заметки — книга или фильм, поэтому в маршруте едут оба.
        assertThat(Routes.noteEditor(7, MediaType.MOVIES))
            .isEqualTo("notes/new/7/MOVIES?kind=NOTE")
    }
}
