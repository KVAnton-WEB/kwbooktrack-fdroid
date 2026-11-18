package ru.antonkw.kwbooktrack.data.importer

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Staging-каталоги импорта: уборка «хвостов» не должна сносить распаковку
 * параллельного импорта.
 */
class StagingDirsTest {

    @Test
    fun cleanOrphansKeepsActiveDirectory() {
        val parent = Files.createTempDirectory("kwbooktrack-staging-").toFile()
        val active = StagingDirs.create(parent, "tellico")
        val orphan = File(parent, ".import-staging-tellico-orphan").apply { mkdirs() }

        StagingDirs.cleanOrphans(parent, "tellico")

        assertThat(active.exists()).isTrue()
        assertThat(orphan.exists()).isFalse()
        StagingDirs.release(active)
    }

    @Test
    fun cleanOrphansRemovesReleasedDirectory() {
        val parent = Files.createTempDirectory("kwbooktrack-staging-").toFile()
        val dir = StagingDirs.create(parent, "kw")
        StagingDirs.release(dir)

        StagingDirs.cleanOrphans(parent, "kw")

        assertThat(dir.exists()).isFalse()
    }

    @Test
    fun cleanOrphansKeepsForeignPrefixes() {
        // Чужой импортёр (tellico против kw) — его каталог не трогаем.
        val parent = Files.createTempDirectory("kwbooktrack-staging-").toFile()
        val foreign = File(parent, ".import-staging-tellico-foreign").apply { mkdirs() }

        StagingDirs.cleanOrphans(parent, "kw")

        assertThat(foreign.exists()).isTrue()
    }
}
