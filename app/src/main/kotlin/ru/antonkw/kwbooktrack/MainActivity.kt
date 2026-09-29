package ru.antonkw.kwbooktrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import ru.antonkw.kwbooktrack.ui.navigation.KwBooktrackNavHost
import ru.antonkw.kwbooktrack.ui.theme.KwBooktrackTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KwBooktrackTheme {
                KwBooktrackNavHost()
            }
        }
    }
}
