package ru.antonkw.kwbooktrack.ui.common

import androidx.annotation.StringRes
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.data.settings.AppLanguage
import ru.antonkw.kwbooktrack.data.settings.ThemeMode

/**
 * Подписи настроек оформления и языка.
 *
 * Живут в UI-слое, а не рядом с enum'ами в слое данных, — там не полагается
 * знать про `R`.
 */
@get:StringRes
val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }

@get:StringRes
val AppLanguage.labelRes: Int
    get() = when (this) {
        AppLanguage.SYSTEM -> R.string.settings_language_system
        AppLanguage.RUSSIAN -> R.string.settings_language_ru
        AppLanguage.ENGLISH -> R.string.settings_language_en
    }
