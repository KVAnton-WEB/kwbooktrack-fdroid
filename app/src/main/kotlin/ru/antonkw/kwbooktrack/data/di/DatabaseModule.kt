package ru.antonkw.kwbooktrack.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.dao.BookDao
import ru.antonkw.kwbooktrack.data.db.dao.NoteDao
import ru.antonkw.kwbooktrack.data.db.dao.PhotoDao
import ru.antonkw.kwbooktrack.data.db.dao.ReadingSessionDao
import ru.antonkw.kwbooktrack.data.db.dao.TagDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KwBooktrackDatabase =
        Room.databaseBuilder(context, KwBooktrackDatabase::class.java, KwBooktrackDatabase.NAME)
            // WAL lets readers proceed while a long import is writing — without
            // it, observeAll() in BookListScreen blocks on the writer's
            // transaction and the UI freezes until the import finishes.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // Explicit migrations for every step — AutoMigration needs a
            // checked-in json per step, which we don't ship.
            // fallbackToDestructiveMigration намеренно не используется:
            // первое же обновление схемы после релиза должно пройти
            // через явную миграцию, чтобы не потерять библиотеку.
            .addMigrations(
                ru.antonkw.kwbooktrack.data.db.migration.Migrate1To2,
                ru.antonkw.kwbooktrack.data.db.migration.Migrate2To3,
                ru.antonkw.kwbooktrack.data.db.migration.Migrate3To4,
                ru.antonkw.kwbooktrack.data.db.migration.Migrate4To5,
                ru.antonkw.kwbooktrack.data.db.migration.Migrate5To6,
                ru.antonkw.kwbooktrack.data.db.migration.Migrate6To7,
                ru.antonkw.kwbooktrack.data.db.migration.Migrate7To8,
                ru.antonkw.kwbooktrack.data.db.migration.Migrate8To9,
            )
            .build()

    @Provides
    fun provideBookDao(db: KwBooktrackDatabase): BookDao = db.bookDao()

    @Provides
    fun provideSessionDao(db: KwBooktrackDatabase): ReadingSessionDao = db.readingSessionDao()

    @Provides
    fun provideNoteDao(db: KwBooktrackDatabase): NoteDao = db.noteDao()

    @Provides
    fun providePhotoDao(db: KwBooktrackDatabase): PhotoDao = db.photoDao()

    @Provides
    fun provideTagDao(db: KwBooktrackDatabase): TagDao = db.tagDao()
}
