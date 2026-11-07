package ru.antonkw.kwbooktrack.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object TestDb {
    /**
     * Потоки Room'а — по одному на базу, и всегда один.
     *
     * На пуле из нескольких потоков «дождаться конца выдачи» невозможно:
     * барьер может отработать на другом потоке, пока задача Room'а ещё идёт.
     * Одно-поточный пул делает ожидание честным — см. [drain].
     */
    private val executors = mutableListOf<ExecutorService>()

    fun createInMemory(context: Context = ApplicationProvider.getApplicationContext()): KwBooktrackDatabase {
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "room-test").apply { isDaemon = true }
        }
        synchronized(executors) { executors += executor }
        return Room.inMemoryDatabaseBuilder(context, KwBooktrackDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(executor)
            .build()
    }

    /**
     * Дождаться, пока потоки Room'а разберут свои задачи.
     *
     * Выдача из базы возвращается в корутину подписчика с потока Room'а, и
     * та корутина читает Dispatchers.Main. Если это случится в момент
     * resetMain(), читатель и писатель Dispatchers.Main столкнутся, и тест
     * упадёт с «Dispatchers.Main is used concurrently with setting it».
     * Барьер и есть ожидание конца выдачи.
     */
    fun drain() {
        val list = synchronized(executors) { executors.toList() }
        list.forEach { executor ->
            runCatching { executor.submit { }.get() }
        }
    }
}
