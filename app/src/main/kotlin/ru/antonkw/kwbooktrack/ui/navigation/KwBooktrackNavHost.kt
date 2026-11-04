package ru.antonkw.kwbooktrack.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.antonkw.kwbooktrack.ui.about.AboutScreen
import ru.antonkw.kwbooktrack.ui.add.AddBookScreen
import ru.antonkw.kwbooktrack.ui.books.BookDetailScreen
import ru.antonkw.kwbooktrack.ui.books.BookListScreen
import ru.antonkw.kwbooktrack.ui.calendar.CalendarScreen
import ru.antonkw.kwbooktrack.ui.importer.TellicoImportScreen
import ru.antonkw.kwbooktrack.ui.notes.NoteDetailScreen
import ru.antonkw.kwbooktrack.ui.notes.NoteEditorScreen
import ru.antonkw.kwbooktrack.ui.notes.NotesListScreen
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.ui.stats.StatisticsScreen

object Routes {
    const val BOOK_LIST = "books"
    const val ADD_BOOK = "books/new"
    const val EDIT_BOOK = "books/{bookId}/edit"
    const val STATS = "stats"
    const val CALENDAR = "calendar"
    const val IMPORT = "import"
    const val NOTES = "notes"
    const val ABOUT = "about"
    const val BOOK_DETAIL = "books/{bookId}"
    const val NOTE_EDITOR = "books/{bookId}/notes/new"
    const val NOTE_EDITOR_REVIEW = "books/{bookId}/notes/new?kind={kind}"
    const val NOTE_EDIT = "notes/{noteId}/edit"
    const val NOTE_DETAIL = "notes/{noteId}"
    fun bookDetail(bookId: Long) = "books/$bookId"
    fun noteEditor(bookId: Long) = "books/$bookId/notes/new"
    fun noteEditorReview(bookId: Long) = "books/$bookId/notes/new?kind=REVIEW"
    fun noteEdit(noteId: Long) = "notes/$noteId/edit"
    fun editBook(bookId: Long) = "books/$bookId/edit"
    fun noteDetail(noteId: Long) = "notes/$noteId"
}

@Composable
fun KwBooktrackNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.BOOK_LIST) {
        composable(Routes.BOOK_LIST) {
            BookListScreen(
                onBookClick = { bookId -> navController.navigate(Routes.bookDetail(bookId)) },
                onOpenStats = { navController.navigate(Routes.STATS) },
                onAddBook = { navController.navigate(Routes.ADD_BOOK) },
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
                onOpenBook = { bookId -> navController.navigate(Routes.bookDetail(bookId)) },
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
                onAddNote = { bookId -> navController.navigate(Routes.noteEditor(bookId)) },
                onAddReview = { bookId -> navController.navigate(Routes.noteEditorReview(bookId)) },
                onEditBook = { bookId -> navController.navigate(Routes.editBook(bookId)) },
                onOpenNote = { noteId -> navController.navigate(Routes.noteDetail(noteId)) },
                onDeleted = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.NOTE_EDITOR,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
        ) {
            NoteEditorScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.NOTE_EDITOR_REVIEW,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("kind") {
                    type = NavType.StringType
                    defaultValue = NoteKind.REVIEW.name
                },
            ),
        ) {
            NoteEditorScreen(onBack = { navController.popBackStack() })
        }
    }
}

