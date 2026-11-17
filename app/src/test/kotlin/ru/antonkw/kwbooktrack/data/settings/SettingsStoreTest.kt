package ru.antonkw.kwbooktrack.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Настройки темы и языка: сохраняются и применяются к ресурсам. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class SettingsStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("kwbooktrack_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultIsFollowSystem() {
        assertThat(SettingsStore.themeMode(context)).isEqualTo(ThemeMode.SYSTEM)
        assertThat(SettingsStore.language(context)).isEqualTo(AppLanguage.SYSTEM)
        assertThat(SettingsStore.languageTag(context)).isNull()
    }

    @Test
    fun themeAndLanguageArePersisted() {
        val store = SettingsStore(context)
        store.setThemeMode(ThemeMode.LIGHT)
        store.setLanguage(AppLanguage.ENGLISH)

        assertThat(SettingsStore.themeMode(context)).isEqualTo(ThemeMode.LIGHT)
        assertThat(SettingsStore.language(context)).isEqualTo(AppLanguage.ENGLISH)
        assertThat(SettingsStore.languageTag(context)).isEqualTo("en")
    }

    @Test
    fun wrappedContextReadsChosenLanguage() {
        // Система — русская: без переопределения ресурсы русские.
        assertThat(context.getString(ru.antonkw.kwbooktrack.R.string.settings_theme_light))
            .isEqualTo("Светлая")

        val wrapped = LocaleContextWrapper.wrap(context, "en")
        assertThat(wrapped.resources.configuration.locales[0].language).isEqualTo("en")
        assertThat(wrapped.getString(ru.antonkw.kwbooktrack.R.string.settings_theme_light))
            .isEqualTo("Light")
    }
}
