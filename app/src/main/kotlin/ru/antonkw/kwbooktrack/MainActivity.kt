package ru.antonkw.kwbooktrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import ru.antonkw.kwbooktrack.ui.navigation.KwBooktrackNavHost
import ru.antonkw.kwbooktrack.ui.theme.KwBooktrackTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Приложение всегда тёмное, поэтому и иконки системных панелей — светлые,
        // независимо от темы системы.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            KwBooktrackTheme {
                KwBooktrackNavHost()
            }
        }
    }
}
