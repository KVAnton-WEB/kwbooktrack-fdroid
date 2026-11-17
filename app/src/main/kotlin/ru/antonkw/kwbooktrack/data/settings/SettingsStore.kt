package ru.antonkw.kwbooktrack.data.settings

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Тема оформления: по системе, светлая или тёмная. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Язык интерфейса: по системе, русский или английский. */
enum class AppLanguage { SYSTEM, RUSSIAN, ENGLISH }

/**
 * Настройки оформления и языка — одни на всё приложение.
 *
 * Хранятся в SharedPreferences, а не в DataStore: язык нужен **синхронно** в
 * `MainActivity.attachBaseContext` — до того, как Hilt внедрит зависимости, —
 * а DataStore читает только асинхронно.
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = prefs(context)

    private val _themeMode = MutableStateFlow(themeMode(context))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _language = MutableStateFlow(language(context))
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    fun setLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.name).apply()
        _language.value = language
    }

    companion object {
        private const val PREFS_NAME = "kwbooktrack_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_LANGUAGE = "language"

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** Выбранная тема — синхронно, до инъекции Hilt. */
        fun themeMode(context: Context): ThemeMode {
            val name = prefs(context).getString(KEY_THEME, null) ?: return ThemeMode.SYSTEM
            return ThemeMode.entries.firstOrNull { it.name == name } ?: ThemeMode.SYSTEM
        }

        /** Выбранный язык — синхронно, до инъекции Hilt. */
        fun language(context: Context): AppLanguage {
            val name = prefs(context).getString(KEY_LANGUAGE, null) ?: return AppLanguage.SYSTEM
            return AppLanguage.entries.firstOrNull { it.name == name } ?: AppLanguage.SYSTEM
        }

        /** BCP-47 тег выбранного языка; null — язык системы. */
        fun languageTag(context: Context): String? = when (language(context)) {
            AppLanguage.RUSSIAN -> "ru"
            AppLanguage.ENGLISH -> "en"
            AppLanguage.SYSTEM -> null
        }
    }
}

/**
 * Оборачивает контекст языком интерфейса.
 *
 * Стандартный способ сменить язык без appcompat: ресурсы приложения читаются
 * в локали конфигурации, а `attachBaseContext` подменяет её до `onCreate`.
 * На API 33+ система умеет per-app language сама, но ручной выбор всё равно
 * должен побеждать.
 */
object LocaleContextWrapper {

    /** [tag] — BCP-47 тег («ru», «en»); null — язык системы. */
    fun wrap(base: Context, tag: String?): Context {
        if (tag == null) return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Локали контекста — для тестов и отладки. */
    fun locales(context: Context): LocaleList = context.resources.configuration.locales
}
