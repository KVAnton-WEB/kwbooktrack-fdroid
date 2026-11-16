package ru.antonkw.kwbooktrack.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.ui.about.AboutScreen
import ru.antonkw.kwbooktrack.ui.add.AddBookScreen
import ru.antonkw.kwbooktrack.ui.books.BookDetailScreen
import ru.antonkw.kwbooktrack.ui.calendar.CalendarScreen
import ru.antonkw.kwbooktrack.ui.importer.TellicoImportScreen
import ru.antonkw.kwbooktrack.ui.library.LibraryScreen
import ru.antonkw.kwbooktrack.ui.movies.MovieDetailScreen
import ru.antonkw.kwbooktrack.ui.movies.MovieEditScreen
import ru.antonkw.kwbooktrack.ui.notes.NoteDetailScreen
import ru.antonkw.kwbooktrack.ui.notes.NoteEditorScreen
import ru.antonkw.kwbooktrack.ui.notes.NotesListScreen
import ru.antonkw.kwbooktrack.ui.stats.StatisticsScreen

object Routes {
    /** Список библиотеки: книги или фильмы — по переключателю в заголовке. */
    const val LIBRARY = "library"
    const val ADD_BOOK = "books/new"
    const val EDIT_BOOK = "books/{bookId}/edit"
    const val STATS = "stats"
    const val CALENDAR = "calendar"
    const val IMPORT = "import"
    const val NOTES = "notes"
    const val ABOUT = "about"
    const val BOOK_DETAIL = "books/{bookId}"
    const val MOVIE_DETAIL = "movies/{movieId}"
    const val ADD_MOVIE = "movies/new"
    const val EDIT_MOVIE = "movies/{movieId}/edit"

    /**
     * Один маршрут на редактор заметки. Тип (заметка/цитата/рецензия) —
     * обязательный query-аргумент: иначе шаблон с `kind={kind}` и
     * defaultValue матчился бы и на маршрут без query, и FAB «добавить
     * заметку» открывал бы рецензию (kind по умолчанию).
     *
     * Владелец — книга или фильм, поэтому в маршруте едут и id, и тип.
     */
    const val NOTE_EDITOR = "notes/new/{mediaId}/{mediaType}?kind={kind}"
    const val NOTE_EDIT = "notes/{noteId}/edit"
    const val NOTE_DETAIL = "notes/{noteId}"

    fun bookDetail(bookId: Long) = "books/$bookId"
    fun movieDetail(movieId: Long) = "movies/$movieId"
    fun editBook(bookId: Long) = "books/$bookId/edit"
    fun editMovie(movieId: Long) = "movies/$movieId/edit"

    fun noteEditor(mediaId: Long, mediaType: MediaType, kind: NoteKind = NoteKind.NOTE): String =
        "notes/new/$mediaId/${mediaType.name}?kind=${kind.name}"

    fun noteEditorReview(mediaId: Long, mediaType: MediaType): String =
        noteEditor(mediaId, mediaType, NoteKind.REVIEW)

    fun noteEdit(noteId: Long) = "notes/$noteId/edit"
    fun noteDetail(noteId: Long) = "notes/$noteId"
}

@Composable
fun KwBooktrackNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onMediaClick = { mediaType, mediaId ->
                    navController.navigate(
                        if (mediaType == MediaType.MOVIES) {
                            Routes.movieDetail(mediaId)
                        } else {
                            Routes.bookDetail(mediaId)
                        },
                    )
                },
                onOpenStats = { navController.navigate(Routes.STATS) },
                onAddMedia = { mediaType ->
                    navController.navigate(
                        if (mediaType == MediaType.MOVIES) Routes.ADD_MOVIE else Routes.ADD_BOOK,
                    )
                },
                onOpenCalendar = { navController.navigate(Routes.CALENDAR) },
                onOpenNotes = { navController.navigate(Routes.NOTES) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.ADD_BOOK) {
            AddBookScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.EDIT_BOOK,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
        ) {
            AddBookScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.ADD_MOVIE) {
            MovieEditScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.EDIT_MOVIE,
            arguments = listOf(navArgument("movieId") { type = NavType.LongType }),
        ) {
            MovieEditScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.STATS) {
            StatisticsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.CALENDAR) {
            CalendarScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.IMPORT) {
            TellicoImportScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.NOTES) {
            NotesListScreen(
                onBack = { navController.popBackStack() },
                onOpenNote = { noteId -> navController.navigate(Routes.noteDetail(noteId)) },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(
                onBack = { navController.popBackStack() },
                onImportClick = { navController.navigate(Routes.IMPORT) },
            )
        }
        composable(
            route = Routes.NOTE_DETAIL,
            arguments = listOf(navArgument("noteId") { type = NavType.LongType }),
        ) { entry ->
            val noteId = entry.arguments?.getLong("noteId") ?: 0L
            NoteDetailScreen(
                noteId = noteId,
                onBack = { navController.popBackStack() },
                onOpenMedia = { mediaType, mediaId ->
                    navController.navigate(
                        if (mediaType == MediaType.MOVIES) {
                            Routes.movieDetail(mediaId)
                        } else {
                            Routes.bookDetail(mediaId)
                        },
                    )
                },
                onEditNote = { id -> navController.navigate(Routes.noteEdit(id)) },
            )
        }
        composable(
            route = Routes.NOTE_EDIT,
            arguments = listOf(navArgument("noteId") { type = NavType.LongType }),
        ) {
            NoteEditorScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.BOOK_DETAIL,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
        ) {
            BookDetailScreen(
                onBack = { navController.popBackStack() },
                onAddNote = { bookId ->
                    navController.navigate(Routes.noteEditor(bookId, MediaType.BOOKS))
                },
                onAddReview = { bookId ->
                    navController.navigate(Routes.noteEditorReview(bookId, MediaType.BOOKS))
                },
                onEditBook = { bookId -> navController.navigate(Routes.editBook(bookId)) },
                onOpenNote = { noteId -> navController.navigate(Routes.noteDetail(noteId)) },
                onDeleted = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.MOVIE_DETAIL,
            arguments = listOf(navArgument("movieId") { type = NavType.LongType }),
        ) {
            MovieDetailScreen(
                onBack = { navController.popBackStack() },
                onEditMovie = { movieId -> navController.navigate(Routes.editMovie(movieId)) },
                onOpenNote = { noteId -> navController.navigate(Routes.noteDetail(noteId)) },
                onAddNote = { movieId ->
                    navController.navigate(Routes.noteEditor(movieId, MediaType.MOVIES))
                },
                onAddReview = { movieId ->
                    navController.navigate(Routes.noteEditorReview(movieId, MediaType.MOVIES))
                },
                onDeleted = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.NOTE_EDITOR,
            arguments = listOf(
                navArgument("mediaId") { type = NavType.LongType },
                navArgument("mediaType") { type = NavType.StringType },
                navArgument("kind") {
                    type = NavType.StringType
                    defaultValue = NoteKind.NOTE.name
                },
            ),
        ) {
            NoteEditorScreen(onBack = { navController.popBackStack() })
        }
    }
}
