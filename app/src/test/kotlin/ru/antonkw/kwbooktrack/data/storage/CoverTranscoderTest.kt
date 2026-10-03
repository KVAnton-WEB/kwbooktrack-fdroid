package ru.antonkw.kwbooktrack.data.storage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoverTranscoderTest {

    private val transcoder = CoverTranscoder()

    private fun pngBytes(w: Int, h: Int, color: Int = Color.RED): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        return java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    private fun webpSizeOf(bytes: ByteArray): Pair<Int, Int> {
        var w = 0
        var h = 0
        val src = android.graphics.ImageDecoder.createSource(bytes)
        android.graphics.ImageDecoder.decodeBitmap(src) { _, info, _ ->
            w = info.size.width
            h = info.size.height
        }
        return w to h
    }

    @Test
    fun oversizedCoverIsScaledToMaxLongSide() {
        val out = transcoder.toWebp(pngBytes(1600, 2400))
        assertThat(out).isNotNull()
        val (w, h) = webpSizeOf(out!!)
        assertThat(maxOf(w, h)).isAtMost(CoverTranscoder.MAX_LONG_SIDE_PX)
        assertThat(w).isEqualTo(200)
        assertThat(h).isEqualTo(300)
    }

    @Test
    fun smallCoverIsNotUpscaled() {
        val out = transcoder.toWebp(pngBytes(120, 180))
        assertThat(out).isNotNull()
        val (w, h) = webpSizeOf(out!!)
        assertThat(w).isEqualTo(120)
        assertThat(h).isEqualTo(180)
    }

    // No "garbage bytes in" test: Robolectric's ImageDecoder shadow happily
    // decodes byteArrayOf(1,2,3,4) into a 10x10 bitmap instead of throwing, so
    // the null-return path can't be exercised here. On a real device
    // ImageDecoder throws and toWebp() returns null (runCatching swallows
    // IOException and OutOfMemoryError alike), which is what the callers
    // handle by storing the original bytes.

    @Test
    fun landscapeCoverKeepsItsAspectRatio() {
        val out = transcoder.toWebp(pngBytes(2400, 1600))
        assertThat(out).isNotNull()
        val (w, h) = webpSizeOf(out!!)
        assertThat(w).isEqualTo(300)
        assertThat(h).isEqualTo(200)
    }
}