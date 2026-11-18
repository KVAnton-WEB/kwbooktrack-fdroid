package ru.antonkw.kwbooktrack.ui.common

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
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
    // Один зум на видимую страницу; при смене страницы сбрасывается.
    var zoom by remember { mutableStateOf(Zoom()) }
    // Intrinsic размер фото каждой страницы: клампинг pan считается по нему.
    // Per-page: соседняя страница догружается во время свайпа, раньше чем
    // currentPage сменится — общий сброс размера стёр бы уже готовый размер.
    val imageSizes = remember { mutableStateMapOf<Int, IntSize>() }
    LaunchedEffect(pagerState.currentPage) {
        zoom = Zoom()
    }
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
                // Пока фото зумнуто, свайп двигает картинку, а не листает страницы.
                userScrollEnabled = zoom.scale == 1f,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val path = photos[page].path
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val size = IntSize(constraints.maxWidth, constraints.maxHeight)
                    AsyncImage(
                        model = remember(path) { photoImageModel(path) },
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        onSuccess = { state ->
                            val intrinsic = state.painter.intrinsicSize
                            if (intrinsic.isSpecified) imageSizes[page] = intrinsic.toIntSize()
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoom.scale
                                scaleY = zoom.scale
                                translationX = zoom.offsetX
                                translationY = zoom.offsetY
                            }
                            // Tap снаружи, трансформация внутри: в Main-проходе события
                            // сначала получает внутренний детектор, а detectTapGestures
                            // потребляет down — внешний цикл трансформации не увидел бы
                            // ни одного жеста.
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = { zoom = zoom.doubleTap(size) })
                            }
                            .pointerInput(Unit) {
                                // Свой цикл вместо detectTransformGestures: тот забирает
                                // drag даже при scale=1, и пейджер перестаёт листать.
                                awaitEachGesture {
                                    awaitFirstDown()
                                    do {
                                        val event = awaitPointerEvent()
                                        val pan = event.calculatePan()
                                        val zoomFactor = event.calculateZoom()
                                        if (zoom.applies(pan, zoomFactor)) {
                                            zoom = zoom.transform(pan, zoomFactor, size, imageSizes[page] ?: IntSize.Zero)
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            },
                    )
                }
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
            if (photos.size > 1) {
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
}

/**
 * Модель Coil для фото. Сохранённое фото — путь к файлу, несохранённое
 * (редактор заметки) — `content://` URI: `File("content://…")` указывает на
 * несуществующий файл, и Coil такой URI не грузит — картинка пустая.
 */
internal fun photoImageModel(path: String): Any =
    if (path.startsWith("content://")) Uri.parse(path) else File(path)
