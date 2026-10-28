package ru.antonkw.kwbooktrack.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * ViewModel'ы с stateIn(viewModelScope, Eagerly) держат flow-коллектор живым
 * до конца теста: подписка на БД остаётся активной, и db.close() в tearDown
 * начинает гонку с запросом в закрываемую БД — Room в этом месте умеет
 * виснуть. Поэтому скоупы ViewModel'ов отменяем руками ДО закрытия БД.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object TestViewModels {
    private val tracked = mutableListOf<ViewModel>()

    fun track(vm: ViewModel) {
        tracked += vm
    }

    /**
     * Отменяет скоупы и даёт отменённым коллекторам отписаться от БД: сама
     * отмена только ставит продолжение в очередь диспетчера, а подписка Room
     * снимается внутри finally, который выполнится только когда очередь
     * разберётся. Без этого advanceUntilIdle() close() всё ещё видит
     * подписанные коллекторы.
     */
    fun cancelAll(scheduler: TestCoroutineScheduler) {
        tracked.forEach { it.viewModelScope.cancel() }
        tracked.clear()
        scheduler.advanceUntilIdle()
    }
}
