package ru.antonkw.kwbooktrack.data.snapshot

import java.io.File

/**
 * Файлы распакованного архива: обложки и фото.
 *
 * @param root корень распакованного архива (может быть null — тогда файлов нет).
 * @param coverOutputDir куда складывать обложки; обложка книги лежит
 *   в `<coverOutputDir>/covers/{bookId}.{ext}`.
 */
class ImportAssets(val root: File?, val coverOutputDir: File?) {

    /**
     * Индекс файлов архива: имя файла → путь.
     *
     * Строится один раз при первом обращении к [find] и дальше переиспользуется:
     * `find` зовётся на каждую книгу, а обход каталога на каждую книгу — это
     * O(книг × файлов) (на пользовательской папке с тысячами файлов импорт встал бы).
     */
    private val index: Map<String, File> by lazy { buildIndex() }

    /**
     * Найти файл: сначала путь относительно root, затем поиск по имени файла вглубь.
     *
     * Второй шаг нужен для архивов Tellico: обложка лежит в `Books_files/`
     * с префиксом коллекции, а снимок знает только имя файла.
     */
    fun find(name: String): File? {
        val root = root ?: return null
        val direct = File(root, name)
        val candidate = if (direct.isFile) {
            direct
        } else {
            index[File(name).name]
        } ?: return null
        return candidate.takeIf { insideRoot(root, it) }
    }

    /** Имя файла → первый по порядку обхода файл с таким именем. */
    private fun buildIndex(): Map<String, File> {
        val root = root ?: return emptyMap()
        if (!root.isDirectory) return emptyMap()
        val result = mutableMapOf<String, File>()
        root.walkTopDown().forEach { file ->
            if (file.isFile) result.putIfAbsent(file.name, file)
        }
        return result
    }

    /**
     * Имя файла приходит из архива — путь с `../` не должен уводить за
     * пределы распакованного архива (как в импортере Tellico).
     */
    private fun insideRoot(root: File, file: File): Boolean {
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return false
        val filePath = runCatching { file.canonicalPath }.getOrNull() ?: return false
        val prefix = rootPath.trimEnd(File.separatorChar) + File.separator
        return filePath.startsWith(prefix)
    }
}
