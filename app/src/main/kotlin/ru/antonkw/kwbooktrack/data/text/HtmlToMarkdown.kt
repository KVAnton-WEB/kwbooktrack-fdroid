package ru.antonkw.kwbooktrack.data.text

/**
 * HTML → Markdown для полей, приходящих из импорта.
 *
 * Tellico/LiveLib отдаёт «Аннотацию», «Рецензию», «Цитаты» и содержимое
 * заметок Bookmory в HTML. Поле `annotation` показывается простым текстом, а
 * `bodyMd` рендерится Markwon, поэтому сырой HTML виден пользователю тегами.
 * Конвертим в Markdown: у тегов, у которых есть markdown-эквивалент, он и
 * подставляется, остальные просто снимаются.
 *
 * ponytail: свой конвертер вместо `Html.fromHtml` намеренно — fromHtml отдал
 * бы Spanned с потерей переносов строк и выделений, а нужен именно markdown.
 * Набор тегов покрывает то, что реально встречается в выгрузках LiveLib;
 * неизвестные теги (font, span, meta, img) снимаются.
 *
 * Идемпотентно: на уже готовом markdown ничего не меняется, ни один тег под
 * регулярки не подходит.
 */
object HtmlToMarkdown {

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    private fun literal(value: String) = { _: MatchResult -> value }

    /** Порядок проходов важен: Markdown-обёртки ставятся раньше, чем снимается
     *  «всё остальное», а сущности раскрываются последними (иначе `&lt;b&gt;`
     *  стал бы жирным). */
    private val TAG_PASSES: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // [текст](url) — первым, иначе <a> смазался бы вместе со всем.
        rx("""<\s*a\b[^>]*\bhref\s*=\s*["']([^"']*)["'][^>]*>(.*?)<\s*/\s*a\s*>""") to
            { m -> "[${m.groupValues[2]}](${m.groupValues[1]})" },
        rx("""<\s*br\s*/?\s*>""") to literal("\n"),
        rx("""<\s*/?\s*p\s*>""") to literal("\n\n"),
        rx("""<\s*/?\s*div\s*>""") to literal("\n"),
        rx("""<\s*li\s*>""") to literal("\n- "),
        // Закрывающий </li> не должен заводить пустой пункт — перенос уже даёт
        // открывающий <li> следующего элемента.
        rx("""<\s*/\s*li\s*>""") to literal(""),
        rx("""<\s*/?\s*h[1-6]\s*>""") to literal("\n\n## "),
        rx("""<\s*/\s*h[1-6]\s*>""") to literal("\n"),
        rx("""<\s*blockquote\s*>""") to literal("\n\n> "),
        rx("""<\s*/\s*blockquote\s*>""") to literal("\n"),
        rx("""<\s*/?\s*tr\s*>""") to literal("\n"),
        rx("""<\s*b\s*>""") to literal("**"),
        rx("""<\s*/\s*b\s*>""") to literal("**"),
        rx("""<\s*strong\s*>""") to literal("**"),
        rx("""<\s*/\s*strong\s*>""") to literal("**"),
        rx("""<\s*i\s*>""") to literal("*"),
        rx("""<\s*/\s*i\s*>""") to literal("*"),
        rx("""<\s*em\s*>""") to literal("*"),
        rx("""<\s*/\s*em\s*>""") to literal("*"),
        // Всё нераспознанное (font, span, meta, img, sup...) — прочь.
        rx("""<[^>]*>""") to literal(""),
    )

    /**
     * Сущности — **одним** проходом.
     *
     * Раньше их раскрывали отдельные проходы, и `&amp;` раскрывался раньше
     * `&lt;`: из `&amp;lt;script&amp;gt;` получался `<script>` — текст
     * превращался в разметку, а цепочка «импорт → экспорт» переставала быть
     * идемпотентной. Один проход раскрывает ровно один уровень, как и делает
     * любой HTML-парсер.
     */
    private val ENTITY = Regex(
        """&(nbsp|amp|lt|gt|quot|apos|laquo|raquo|ldquo|rdquo|lsquo|rsquo|mdash|ndash|hellip);""" +
            """|&#x([0-9a-fA-F]+);|&#(\d+);""",
        RegexOption.IGNORE_CASE,
    )

    private val NAMED_ENTITIES: Map<String, String> = mapOf(
        "nbsp" to " ",
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "laquo" to "«",
        "raquo" to "»",
        "ldquo" to "“",
        "rdquo" to "”",
        "lsquo" to "‘",
        "rsquo" to "’",
        "mdash" to "—",
        "ndash" to "–",
        "hellip" to "…",
    )

    private fun unescapeEntity(match: MatchResult): String {
        val named = match.groupValues[1]
        if (named.isNotEmpty()) return NAMED_ENTITIES[named.lowercase()] ?: ""
        val hex = match.groupValues[2]
        if (hex.isNotEmpty()) return charOf(hex, 16)
        return charOf(match.groupValues[3], 10)
    }

    private fun charOf(digits: String, radix: Int): String {
        val code = digits.toIntOrNull(radix) ?: return ""
        if (code !in 1..0x10FFFF) return ""
        // Символы за пределами BMP — эмодзи — это **пара** UTF-16: одиночный
        // `toChar()` давал битый суррогат.
        return if (code <= 0xFFFF) {
            code.toChar().toString()
        } else {
            Character.toChars(code).concatToString()
        }
    }

    fun convert(raw: String): String {
        var out = raw
        for ((regex, replacement) in TAG_PASSES) out = out.replace(regex, replacement)
        out = out.replace(ENTITY) { unescapeEntity(it) }
        return out
            .replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}