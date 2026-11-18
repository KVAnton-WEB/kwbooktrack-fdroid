package ru.antonkw.kwbooktrack.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ru.antonkw.kwbooktrack.data.db.converter.Converters
import ru.antonkw.kwbooktrack.data.db.dao.BookDao
import ru.antonkw.kwbooktrack.data.db.dao.MediaDateDao
import ru.antonkw.kwbooktrack.data.db.dao.MovieDao
import ru.antonkw.kwbooktrack.data.db.dao.NoteDao
import ru.antonkw.kwbooktrack.data.db.dao.PhotoDao
import ru.antonkw.kwbooktrack.data.db.dao.ReadingSessionDao
import ru.antonkw.kwbooktrack.data.db.dao.TagDao
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.MediaDateEntity
import ru.antonkw.kwbooktrack.data.db.entity.MediaTagCrossRef
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
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
        MediaTagCrossRef::class,
        MediaDateEntity::class,
        MovieEntity::class,
    ],
    version = 14,
    // Schema export is disabled: all transitions are explicit Migrations
    // wired into DatabaseModule via .addMigrations(...), so Room doesn't
    // need a checked-in json file per step. The old 1.json / 2.json /
    // 3.json / 4.json were removed; MigrationsFromV1Test rebuilds the v1
    // schema with inline SQL instead of reading them.
    exportSchema = false,
    // No autoMigrations — every step is a hand-written ALTER TABLE.
    autoMigrations = [],
)
@TypeConverters(Converters::class)
abstract class KwBooktrackDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun noteDao(): NoteDao
    abstract fun photoDao(): PhotoDao
    abstract fun tagDao(): TagDao
    abstract fun movieDao(): MovieDao

    abstract fun mediaDateDao(): MediaDateDao

    companion object {
        const val NAME = "kwbooktrack.db"
    }
}
