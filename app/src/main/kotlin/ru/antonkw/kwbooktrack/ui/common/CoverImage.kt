package ru.antonkw.kwbooktrack.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

/**
 * Обложка записи: картинка или заглушка.
 *
 * Общая для книг и фильмов — картинка одна и та же, различается только
 * содержимое.
 */
@Composable
internal fun CoverImage(
    path: String?,
    sizeDp: Int? = null,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val base = when {
        sizeDp != null -> modifier.size(sizeDp.dp)
        else -> modifier
    }
    // clickable last in the chain → it inherits the size constraint, so the
    // touch target is the cover itself and not the whole slot.
    val interactive = if (onClick != null) base.clickable(onClick = onClick) else base
    if (path.isNullOrBlank()) {
        Box(
            modifier = interactive.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        AsyncImage(
            model = java.io.File(path),
            contentDescription = contentDescription,
            modifier = interactive,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    }
}

