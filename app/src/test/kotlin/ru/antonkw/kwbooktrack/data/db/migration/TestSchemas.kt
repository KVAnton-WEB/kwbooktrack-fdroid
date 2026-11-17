package ru.antonkw.kwbooktrack.data.db.migration

/**
 * Схема базы версии 9 — как её создавал Room на той версии приложения.
 *
 * Общая для тестов миграций: версия 9 — начальная точка всех проверок цепочки
 * (9 → 10 → 11 → 12). Дублировать такой SQL в каждом тесте — верный способ
 * разъехаться схемами.
 */
object TestSchemas {

    val v9: List<String> = listOf(
        """
        CREATE TABLE `books` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `title` TEXT NOT NULL,
            `subtitle` TEXT,
            `author` TEXT NOT NULL,
            `isbn13` TEXT,
            `page_count` INTEGER,
            `language` TEXT NOT NULL,
            `genre` TEXT,
            `publisher` TEXT,
            `year` INTEGER,
            `cover_path` TEXT,
            `rating` INTEGER,
            `status` TEXT NOT NULL,
            `started_at` INTEGER,
            `finished_at` INTEGER,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            `source` TEXT NOT NULL,
            `purchase_date` INTEGER,
            `purchase_price` REAL,
            `title_search` TEXT NOT NULL,
            `author_search` TEXT NOT NULL,
            `series_search` TEXT NOT NULL,
            `binding` TEXT,
            `purchase_place` TEXT,
            `series` TEXT,
            `series_number` TEXT,
            `condition` TEXT,
            `translator` TEXT,
            `print_year` INTEGER,
            `print_pages` REAL,
            `original_language` TEXT,
            `original_title` TEXT,
            `annotation` TEXT,
            `is_gift` INTEGER NOT NULL DEFAULT 0,
            `is_lent` INTEGER NOT NULL DEFAULT 0,
            `is_sold` INTEGER NOT NULL DEFAULT 0,
            `extra_fields` TEXT NOT NULL DEFAULT '{}'
        )
        """.trimIndent(),
        """
        CREATE TABLE `reading_sessions` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `book_id` INTEGER NOT NULL,
            `started_at` INTEGER NOT NULL,
            `ended_at` INTEGER,
            `start_page` INTEGER,
            `end_page` INTEGER,
            `duration_sec` INTEGER,
            `note` TEXT,
            FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE `notes` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `book_id` INTEGER NOT NULL,
            `page` INTEGER,
            `body_md` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        """
        CREATE TABLE `photos` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `book_id` INTEGER NOT NULL,
            `note_id` INTEGER,
            `path` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`note_id`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE `tags` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE `book_tags` (
            `book_id` INTEGER NOT NULL,
            `tag_id` INTEGER NOT NULL,
            PRIMARY KEY(`book_id`, `tag_id`),
            FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`tag_id`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX `index_books_isbn13` ON `books` (`isbn13`)",
        "CREATE INDEX `index_books_status` ON `books` (`status`)",
        "CREATE INDEX `index_books_started_at` ON `books` (`started_at`)",
        "CREATE INDEX `index_books_finished_at` ON `books` (`finished_at`)",
        "CREATE INDEX `index_books_purchase_date` ON `books` (`purchase_date`)",
        "CREATE INDEX `index_books_title_search` ON `books` (`title_search`)",
        "CREATE INDEX `index_books_author_search` ON `books` (`author_search`)",
        "CREATE INDEX `index_reading_sessions_book_id` ON `reading_sessions` (`book_id`)",
        "CREATE INDEX `index_reading_sessions_started_at` ON `reading_sessions` (`started_at`)",
        "CREATE INDEX `index_notes_book_id` ON `notes` (`book_id`)",
        "CREATE INDEX `index_notes_created_at` ON `notes` (`created_at`)",
        "CREATE INDEX `index_notes_kind` ON `notes` (`kind`)",
        "CREATE INDEX `index_photos_book_id` ON `photos` (`book_id`)",
        "CREATE INDEX `index_photos_note_id` ON `photos` (`note_id`)",
        "CREATE UNIQUE INDEX `index_tags_name` ON `tags` (`name`)",
        "CREATE INDEX `index_book_tags_book_id` ON `book_tags` (`book_id`)",
        "CREATE INDEX `index_book_tags_tag_id` ON `book_tags` (`tag_id`)",
    )
}
