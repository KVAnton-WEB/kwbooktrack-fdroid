package ru.antonkw.kwbooktrack.data.storage

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Связывает интерфейсы [CoverStorage] / [PhotoStorage] с их реализациями.
 * Реализации живут в том же пакете — см. [FileCoverStorage] / [FilePhotoStorage].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {
    @Binds
    @Singleton
    abstract fun bindCoverStorage(impl: FileCoverStorage): CoverStorage

    @Binds
    @Singleton
    abstract fun bindPhotoStorage(impl: FilePhotoStorage): PhotoStorage
}