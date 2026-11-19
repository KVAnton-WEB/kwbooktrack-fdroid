package ru.antonkw.kwbooktrack.data.storage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Обложки — это превью: в сетке они рисуются ~480px по длинной стороне (3x),
 * а во весь экран (v0.5.19) — крупнее, но не на порядок. А из галереи
 * прилетают файлы на 1600×2400 и больше. Хранить их как есть — это мегабайты
 * на книгу ради пикселей, которые никто не увидит. Поэтому на записи длинная
 * сторона ужимается до [MAX_LONG_SIDE_PX] и всё перекодируется в lossy WebP
 * (lossy — с API 30, на 28–29 lossless).
 *
 * Файл на книгу ровно один: [FileCoverStorage] переименовывает temp-файл в
 * финальный, отдельной копии под полноэкранный просмотр не заводим — отдаём
 * тот же файл, он и так вдвое крупнее прежних 300px.
 *
 * Только на записи. На пути отображения Coil и так декодирует с downsampling,
 * перекодировать там — работа на каждый кадр скролла.
 *
 * AVIF сознательно не используется: платформенного энкодера нет ни на одном
 * API-уровне, libavif стоил бы 1–2 МБ нативного кода на ABI и замедлял
 * декодирование в 5–10x ради выигрыша в несколько килобайт на 600px.
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
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
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
                    scaled.compress(lossyWebpFormat(), QUALITY, out)
                    out.toByteArray()
                }
            } finally {
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
            }
        }.getOrNull()

    /** Lossy WebP есть только с API 30; на 28–29 доступен лишь lossless WebP. */
    private fun lossyWebpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

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
        const val MAX_LONG_SIDE_PX = 600
        const val QUALITY = 80
        private val FILTER_PAINT = android.graphics.Paint(
            android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG,
        )
    }
}