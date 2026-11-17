package ru.antonkw.kwbooktrack.data.importer

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Пределы распаковки архива.
 *
 * Архив приходит из пользовательского выбора — скачанный из мессенджера или
 * присланный знакомым. Защита от «пути за пределы каталога» (zipslip) не
 * спасает от бомбы: миллион мелких записей распаковывается целиком и съедает
 * память устройства.
 */
internal const val MAX_UNZIPPED_BYTES = 512L * 1024 * 1024
internal const val MAX_ZIP_ENTRIES = 20_000

/**
 * Распаковка архива с сохранением путей и лимитами на размер.
 *
 * Бросает [IOException], если архив больше лимита или записей слишком много.
 */
internal fun unzipLimited(
    zip: File,
    target: File,
    maxBytes: Long = MAX_UNZIPPED_BYTES,
    maxEntries: Int = MAX_ZIP_ENTRIES,
) {
    val root = target.canonicalPath
    var total = 0L
    var entries = 0
    ZipFile(zip).use { archive ->
        for (entry in archive.entries()) {
            if (++entries > maxEntries) {
                throw IOException("в архиве больше $maxEntries записей")
            }
            val out = File(target, entry.name)
            // Путь из архива не должен уводить за пределы распаковки.
            if (!out.canonicalPath.startsWith(root + File.separator)) continue
            if (entry.isDirectory) {
                out.mkdirs()
                continue
            }
            out.parentFile?.mkdirs()
            archive.getInputStream(entry).use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        // Считаем на ходу: лимит одной огромной записи тоже
                        // должен останавливать распаковку, а не проверяться
                        // после неё.
                        if (total > maxBytes) {
                            throw IOException("архив распаковывается больше, чем можно на устройство")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }
}
