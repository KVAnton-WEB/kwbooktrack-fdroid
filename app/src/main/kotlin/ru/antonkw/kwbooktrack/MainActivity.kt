package ru.antonkw.kwbooktrack

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import ru.antonkw.kwbooktrack.data.settings.LocaleContextWrapper
import ru.antonkw.kwbooktrack.data.settings.SettingsStore
import ru.antonkw.kwbooktrack.data.settings.ThemeMode
import ru.antonkw.kwbooktrack.ui.navigation.KwBooktrackNavHost
import ru.antonkw.kwbooktrack.ui.theme.KwBooktrackTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settings: SettingsStore

    /**
     * Язык интерфейса подменяем до `onCreate`: ресурсы читаются в локали
     * конфигурации, и подмена в `attachBaseContext` — единственный способ
     * сделать это без appcompat.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleContextWrapper.wrap(newBase, SettingsStore.languageTag(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Иконки системных панелей — по теме: на светлом фоне они тёмные.
        val dark = resolveDark(SettingsStore.themeMode(this), systemIsDark(this))
        enableEdgeToEdge(
            statusBarStyle = systemBarStyle(dark),
            navigationBarStyle = systemBarStyle(dark),
        )
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode by settings.themeMode.collectAsStateWithLifecycle()
            val darkTheme = resolveDark(themeMode, isSystemInDarkTheme())
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = systemBarStyle(darkTheme),
                    navigationBarStyle = systemBarStyle(darkTheme),
                )
            }
            KwBooktrackTheme(darkTheme = darkTheme) {
                KwBooktrackNavHost()
            }
        }
    }

    private fun systemBarStyle(dark: Boolean): SystemBarStyle =
        if (dark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        }
}

/** «По системе» — значит по настройке ночного режима системы. */
internal fun resolveDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Тёмная ли тема сейчас в системе — версия без Compose (до `setContent`). */
internal fun systemIsDark(context: Context): Boolean {
    val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return uiMode == Configuration.UI_MODE_NIGHT_YES
}
