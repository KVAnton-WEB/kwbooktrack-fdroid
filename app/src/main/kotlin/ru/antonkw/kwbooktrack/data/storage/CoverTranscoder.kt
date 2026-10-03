package ru.antonkw.kwbooktrack.data.storage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Обложки — это превью: максимальный отрисованный размер в приложении ~480px по
 * длинной стороне (сетка обложек на 3x), а из галереи прилетают файлы на
 * 1600×2400 и больше. Хранить их как есть — это мегабайты на книгу ради
 * пикселей, которые никто не увидит. Поэтому на записи длинная сторона
 * ужимается до [MAX_LONG_SIDE_PX] и всё перекодируется в lossy WebP
 * (платформенный энкодер с API 30, минимум приложения — 31).
 *
 * Только на записи. На пути отображения Coil и так декодирует с downsampling,
 * перекодировать там — работа на каждый кадр скролла.
 *
 * AVIF сознательно не используется: платформенного энкодера нет ни на одном
 * API-уровне, libavif стоил бы 1–2 МБ нативного кода на ABI и замедлял
 * декодирование в 5–10x ради выигрыша в несколько килобайт на 300px.
 */
@Singleton
class CoverTranscoder @Inject constructor() {

    /**
     * @return WebP-байты нужного размера, либо `null` если исходник не
     *   декодируется / не хватило памяти. Вызывающий код обязан на `null`
     *   сохранить оригинал как есть — потерять обложку из-за ресайза хуже,
     *   чем оставить её большой.
     */
    fun toWebp(bytes: ByteArray, maxLongSidePx: Int = MAX_LONG_SIDE_PX): ByteArray? =
        runCatching {
            val source = ImageDecoder.createSource(bytes)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                if (max(w, h) > maxLongSidePx) {
                    val scale = maxLongSidePx.toFloat() / max(w, h)
                    decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
                }
            }
            val scaled = if (max(decoded.width, decoded.height) <= maxLongSidePx) decoded else scaleTo(decoded, maxLongSidePx)
            try {
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)
                    out.toByteArray()
                }
            } finally {
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
            }
        }.getOrNull()

    private fun scaleTo(src: Bitmap, maxLongSidePx: Int): Bitmap {
        val scale = maxLongSidePx.toFloat() / max(src.width, src.height)
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        // WebP lossy не несёт альфы — книжные обложки RGB, а прозрачные PNG
        // (сканеры, вырезки) лягут на белый, как и фон карточки в UI.
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { dst ->
            dst.eraseColor(Color.WHITE)
            Canvas(dst).drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), FILTER_PAINT)
        }
    }

    companion object {
        const val MAX_LONG_SIDE_PX = 300
        const val QUALITY = 80
        private val FILTER_PAINT = android.graphics.Paint(
            android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG,
        )
    }
}