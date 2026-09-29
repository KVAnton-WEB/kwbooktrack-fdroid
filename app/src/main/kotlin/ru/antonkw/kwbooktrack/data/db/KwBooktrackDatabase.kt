package ru.antonkw.kwbooktrack.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ru.antonkw.kwbooktrack.data.db.converter.Converters
import ru.antonkw.kwbooktrack.data.db.dao.BookDao
import ru.antonkw.kwbooktrack.data.db.dao.NoteDao
import ru.antonkw.kwbooktrack.data.db.dao.PhotoDao
import ru.antonkw.kwbooktrack.data.db.dao.ReadingSessionDao
import ru.antonkw.kwbooktrack.data.db.dao.TagDao
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookTagCrossRef
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity
import ru.antonkw.kwbooktrack.data.db.entity.TagEntity
import ru.antonkw.kwbooktrack.data.db.migration.Migrate1To2

@Database(
    entities = [
        BookEntity::class,
        ReadingSessionEntity::class,
        NoteEntity::class,
        PhotoEntity::class,
        TagEntity::class,
        BookTagCrossRef::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        // v1 → v2: добавлены title_search / author_search (Room автодетектит),
        // плюс spec, потому что v1 содержал FTS4 virtual table и триггеры,
        // которые нужно явно удалить.
        AutoMigration(from = 1, to = 2, spec = Migrate1To2::class),
        // v2 → v3: добавлены nullable purchase_date / purchase_price — чистый auto.
        AutoMigration(from = 2, to = 3),
        // v3 → v4: Index on notes(kind) for observeByKind / countByKind.
        AutoMigration(from = 3, to = 4),
    ],
)
@TypeConverters(Converters::class)
abstract class KwBooktrackDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun noteDao(): NoteDao
    abstract fun photoDao(): PhotoDao
    abstract fun tagDao(): TagDao

    companion object {
        const val NAME = "kwbooktrack.db"
    }
}
