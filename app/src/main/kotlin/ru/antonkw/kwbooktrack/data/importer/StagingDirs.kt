package ru.antonkw.kwbooktrack.data.importer

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Каталоги распаковки импорта.
 *
 * У каждого импорта свой каталог: два параллельных импорта (или импорт,
 * прерванный пользователем) делили бы один каталог, и `deleteRecursively()`
 * в `finally` сносил бы чужую распаковку.
 *
 * Уборка «хвостов» прерванных импортов не должна трогать каталоги, которые
 * прямо сейчас распаковываются: активные каталоги помечены в реестре.
 */
internal object StagingDirs {
    private val active = ConcurrentHashMap.newKeySet<String>()

    /** Создать каталог распаковки и пометить его активным. */
    fun create(parent: File, prefix: String): File {
        val dir = File(parent, ".import-staging-$prefix-${UUID.randomUUID()}")
        // Сначала в реестр, потом на диск: между mkdirs() и active.add()
        // каталог существовал бы на диске, но не в реестре — и параллельный
        // импорт снёс бы его как «хвост».
        active.add(dir.absolutePath)
        dir.mkdirs()
        return dir
    }

    /** Снять пометку активным — импорт закончился, каталог можно убрать. */
    fun release(dir: File) {
        active.remove(dir.absolutePath)
    }

    /** Удалить каталоги с префиксом, которых нет среди активных. */
    fun cleanOrphans(parent: File, prefix: String) {
        parent.listFiles()
            ?.filter { it.name.startsWith(".import-staging-$prefix-") && it.absolutePath !in active }
            ?.forEach { it.deleteRecursively() }
    }
}
