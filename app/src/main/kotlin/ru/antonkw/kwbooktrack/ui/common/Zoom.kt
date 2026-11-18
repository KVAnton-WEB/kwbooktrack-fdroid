package ru.antonkw.kwbooktrack.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlin.math.max

/**
 * Состояние зума фото в просмотрщике: [scale] и смещение [offsetX]/[offsetY]
 * для graphicsLayer (в пикселях контейнера). Зум к центру; смещение клампится
 * по вписанному (ContentScale.Fit) изображению, а не по контейнеру: фото
 * с леттербоксом не уводится за границы видимости. [imageSize] — intrinsic
 * размер картинки; Zero (ещё грузится) — pan запрещён.
 */
data class Zoom(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    fun transform(pan: Offset, zoomFactor: Float, size: IntSize, imageSize: IntSize = size): Zoom {
        val newScale = (scale * zoomFactor).coerceIn(1f, MAX_SCALE)
        if (newScale == 1f) return Zoom()
        if (imageSize == IntSize.Zero) return Zoom(newScale)
        val fit = ContentScale.Fit.computeScaleFactor(imageSize.toSize(), size.toSize())
        val maxX = max(0f, (imageSize.width * fit.scaleX * newScale - size.width) / 2f)
        val maxY = max(0f, (imageSize.height * fit.scaleY * newScale - size.height) / 2f)
        return Zoom(
            scale = newScale,
            offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX),
            offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY),
        )
    }

    fun doubleTap(size: IntSize): Zoom =
        if (scale > 1f) Zoom() else transform(Offset.Zero, DOUBLE_TAP_SCALE, size)

    /**
     * Жест меняет картинку (и должен забрать события у пейджера/тапов):
     * пинч всегда, pan — только когда зумнуто. Чистый pan при scale=1 и
     * тапы при любом масштабе не трогаем — иначе пейджер не листает,
     * а double-tap не сбрасывает зум.
     */
    fun applies(pan: Offset, zoomFactor: Float): Boolean =
        zoomFactor != 1f || (scale > 1f && pan != Offset.Zero)

    companion object {
        const val MAX_SCALE = 4f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}
