package ru.antonkw.kwbooktrack.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomTest {
    private val size = IntSize(1000, 2000)

    @Test
    fun scaleClampedToMax() {
        assertEquals(4f, Zoom().transform(Offset.Zero, 100f, size).scale)
    }

    @Test
    fun scaleNeverBelowOneAndOffsetsReset() {
        assertEquals(Zoom(), Zoom(2f, 10f, 10f).transform(Offset.Zero, 0.01f, size))
    }

    @Test
    fun panClampedToBounds() {
        val z = Zoom(2f).transform(Offset(10000f, 10000f), 1f, size)
        assertEquals(500f, z.offsetX)
        assertEquals(1000f, z.offsetY)
    }

    @Test
    fun atScaleOnePanIgnored() {
        assertEquals(Zoom(), Zoom().transform(Offset(50f, 50f), 1f, size))
    }

    @Test
    fun doubleTapZoomsIn() {
        assertEquals(2.5f, Zoom().doubleTap(size).scale)
    }

    @Test
    fun doubleTapZoomsOutFully() {
        assertEquals(Zoom(), Zoom(2.5f, 100f, 100f).doubleTap(size))
    }

    @Test
    fun purePanAtScaleOneDoesNotApply() {
        assertEquals(false, Zoom().applies(Offset(50f, 0f), 1f))
    }

    @Test
    fun pinchAtScaleOneApplies() {
        assertEquals(true, Zoom().applies(Offset.Zero, 1.2f))
    }

    @Test
    fun panWhileZoomedApplies() {
        assertEquals(true, Zoom(2f).applies(Offset(50f, 0f), 1f))
    }

    @Test
    fun tapWhileZoomedDoesNotApply() {
        assertEquals(false, Zoom(2f).applies(Offset.Zero, 1f))
    }

    @Test
    fun panClampedToLetterboxedImageBounds() {
        // Фото 2:3 в контейнере 1:2: fit = 1000×1500, леттербокс по вертикали.
        // Свободный ход — по вписанному фото, не по контейнеру.
        val image = IntSize(1000, 1500)
        val z = Zoom(2.5f).transform(Offset(10000f, 10000f), 1f, size, image)
        assertEquals(750f, z.offsetX)
        assertEquals(875f, z.offsetY)
    }

    @Test
    fun panForbiddenOnAxisTheImageDoesNotOverflow() {
        // Альбомное фото 2:1: fit = 1000×500; при 2.5× высота 1250 < 2000 —
        // вертикальный pan запрещён, горизонтальный — по фото.
        val image = IntSize(1000, 500)
        val z = Zoom(2.5f).transform(Offset(10000f, 10000f), 1f, size, image)
        assertEquals(750f, z.offsetX)
        assertEquals(0f, z.offsetY)
    }

    @Test
    fun panIgnoredWhileImageSizeUnknown() {
        val z = Zoom(2f).transform(Offset(100f, 100f), 1f, size, IntSize.Zero)
        assertEquals(Zoom(2f), z)
    }

    @Test
    fun combinedPanAndZoom() {
        val z = Zoom(2f, 100f, 100f).transform(Offset(50f, 50f), 1.5f, size)
        assertEquals(3f, z.scale)
        assertEquals(150f, z.offsetX)
        assertEquals(150f, z.offsetY)
    }

    @Test
    fun offsetsReclampedWhenZoomingOut() {
        val z = Zoom(4f, 1500f, 3000f).transform(Offset.Zero, 0.5f, size)
        assertEquals(2f, z.scale)
        assertEquals(500f, z.offsetX)
        assertEquals(1000f, z.offsetY)
    }

    @Test
    fun doubleTapFromIntermediateScaleResets() {
        assertEquals(Zoom(), Zoom(1.5f, 10f, 10f).doubleTap(size))
    }
}
