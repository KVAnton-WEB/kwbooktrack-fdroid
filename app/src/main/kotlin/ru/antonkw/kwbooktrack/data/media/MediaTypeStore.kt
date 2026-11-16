package ru.antonkw.kwbooktrack.data.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Текущий тип носителя библиотеки: книги или фильмы.
 *
 * Один источник правды для списка, календаря, статистики и экспорта в
 * Tellico: переключил на «Фильмы» — они показываются и там.
 *
 * Не персистится: зависимости DataStore в проекте нет, а при перезапуске
 * приложения разумно открывать книги.
 */
@Singleton
class MediaTypeStore @Inject constructor() {
    private val _type = MutableStateFlow(MediaType.BOOKS)
    val type: StateFlow<MediaType> = _type.asStateFlow()

    fun set(type: MediaType) {
        _type.value = type
    }
}
