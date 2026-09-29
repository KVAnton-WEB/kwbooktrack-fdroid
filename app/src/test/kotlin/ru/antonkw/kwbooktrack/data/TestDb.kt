package ru.antonkw.kwbooktrack.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase

object TestDb {
    fun createInMemory(context: Context = ApplicationProvider.getApplicationContext()): KwBooktrackDatabase =
        Room.inMemoryDatabaseBuilder(context, KwBooktrackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
}
