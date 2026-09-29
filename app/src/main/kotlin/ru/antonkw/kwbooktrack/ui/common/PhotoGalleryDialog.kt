package ru.antonkw.kwbooktrack.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.io.File

data class PhotoSource(val id: Long, val path: String)

@Composable
fun PhotoGalleryDialog(
    photos: List<PhotoSource>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    if (photos.isEmpty()) {
        // Defensive: callers gate on isNotEmpty(), but a future caller or a race
        // could still hit this with an empty list. Avoid IndexOutOfBoundsException
        // from coerceIn(0, -1) → photos[-1]. Don't auto-dismiss — that fires
        // onDismiss() during composition, which is a Compose anti-pattern.
        return
    }
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, photos.lastIndex)) { photos.size }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                AsyncImage(
                    model = remember(photos[page].path) { File(photos[page].path) },
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(40.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = androidx.compose.foundation.shape.CircleShape,
                    ),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            ) {
                androidx.compose.material3.Text(
                    text = "${pagerState.currentPage + 1} / ${photos.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
