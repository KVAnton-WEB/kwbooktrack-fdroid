package ru.antonkw.kwbooktrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration v9 → v10: заметки, фото и связи тегов перестают быть «собственностью
 * книги» и становятся «собственностью медиа» — книги **или** фильма.
 *
 * У таблиц `notes`, `photos` и `book_tags` вместо внешнего ключа на `books`
 * появляется пара `media_id` + `media_type`: владельцем может быть и фильм,
 * а фильма в `books` нет, и внешний ключ на `books` его бы не пустил.
 *
 * Таблицы пересоздаются — сменить внешний ключ на месте нельзя. И тут есть
 * подвох: `PRAGMA foreign_keys` внутри транзакции (а миграции Room идут в
 * транзакции) не выключается, поэтому `DROP TABLE` сработал бы как
 * `DELETE FROM` и вызвал внешние ключи потомков.
 *
 * Поэтому порядок такой:
 *  1. `photos` уходит в страховку и **удаляется** — это единственная таблица,
 *     ссылающаяся на `notes` (`ON DELETE SET NULL`), и пока она на месте,
 *     `DROP TABLE notes` обнулил бы `photos.note_id`;
 *  2. пересоздаётся `notes`;
 *  3. `photos` возвращается из страховки уже с новыми колонками;
 *  4. `book_tags` становится `media_tags`.
 *
 * `books` и `reading_sessions` не трогаем — их данные и внешние ключи остаются
 * как были. Всё внутри одной транзакции Room: при ошибке откатится целиком.
 */
val Migrate9To10: Migration = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Страховка фото. Схема страховки — старая (book_id), поэтому
        //    разбор колонок делается при возвращении.
        db.execSQL("CREATE TEMP TABLE `_photos_bak` AS SELECT * FROM `photos`")

        // 2. Убираем `photos` — единственного потомка `notes`. Теперь
        //    `DROP TABLE notes` никому ничего не обнулит.
        db.execSQL("DROP TABLE `photos`")

        // 3. notes: book_id → media_id + media_type.
        db.execSQL(CREATE_NOTES)
        db.execSQL(
            "INSERT INTO `notes_new` " +
                "(`id`, `media_id`, `media_type`, `page`, `body_md`, `kind`, `created_at`, `updated_at`) " +
                "SELECT `id`, `book_id`, 'BOOKS', `page`, `body_md`, `kind`, `created_at`, `updated_at` FROM `notes`",
        )
        db.execSQL("DROP TABLE `notes`")
        db.execSQL("ALTER TABLE `notes_new` RENAME TO `notes`")

        // 4. photos: та же замена, привязка к заметке сохраняется.
        db.execSQL(CREATE_PHOTOS)
        db.execSQL(
            "INSERT INTO `photos_new` " +
                "(`id`, `media_id`, `media_type`, `note_id`, `path`, `created_at`) " +
                "SELECT `id`, `book_id`, 'BOOKS', `note_id`, `path`, `created_at` FROM `_photos_bak`",
        )
        db.execSQL("ALTER TABLE `photos_new` RENAME TO `photos`")

        // 5. book_tags → media_tags. Владелец теперь (media_id, media_type).
        db.execSQL(CREATE_MEDIA_TAGS)
        db.execSQL(
            "INSERT INTO `media_tags` (`media_id`, `media_type`, `tag_id`) " +
                "SELECT `book_id`, 'BOOKS', `tag_id` FROM `book_tags`",
        )
        db.execSQL("DROP TABLE `book_tags`")

        // 6. Страховка больше не нужна.
        db.execSQL("DROP TABLE `_photos_bak`")

        // 7. Индексы пересозданных таблиц — они исчезли вместе с таблицами.
        for (index in INDICES) db.execSQL(index)
    }
}

/** `notes` без внешнего ключа на `books`: владелец — книга или фильм. */
private const val CREATE_NOTES =
    "CREATE TABLE `notes_new` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `media_id` INTEGER NOT NULL, " +
        "`media_type` TEXT NOT NULL, `page` INTEGER, `body_md` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)"

/** `photos`: привязка к заметке на месте, владелец — книга или фильм. */
private const val CREATE_PHOTOS =
    "CREATE TABLE `photos_new` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `media_id` INTEGER NOT NULL, " +
        "`media_type` TEXT NOT NULL, `note_id` INTEGER, `path` TEXT NOT NULL, " +
        "`created_at` INTEGER NOT NULL, " +
        "FOREIGN KEY(`note_id`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)"

/** `media_tags`: связь тега с владельцем — книгой или фильмом. */
private const val CREATE_MEDIA_TAGS =
    "CREATE TABLE `media_tags` (" +
        "`media_id` INTEGER NOT NULL, `media_type` TEXT NOT NULL, `tag_id` INTEGER NOT NULL, " +
        "PRIMARY KEY(`media_id`, `media_type`, `tag_id`), " +
        "FOREIGN KEY(`tag_id`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"

private val INDICES = listOf(
    "CREATE INDEX `index_notes_media_id` ON `notes` (`media_id`)",
    "CREATE INDEX `index_notes_created_at` ON `notes` (`created_at`)",
    "CREATE INDEX `index_notes_kind` ON `notes` (`kind`)",
    "CREATE INDEX `index_photos_media_id` ON `photos` (`media_id`)",
    "CREATE INDEX `index_photos_note_id` ON `photos` (`note_id`)",
    "CREATE INDEX `index_media_tags_media_id` ON `media_tags` (`media_id`)",
    "CREATE INDEX `index_media_tags_tag_id` ON `media_tags` (`tag_id`)",
)
