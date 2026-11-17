package ru.antonkw.kwbooktrack.data.text

/**
 * ISBN в 13-значной форме.
 *
 * Десятизначный ISBN (10 цифр, последняя может быть «X») приводится к
 * ISBN-13 префиксом 978: иначе одна и та же книга лежала бы в базе и
 * десятизначной, и тринадцатизначной строкой — уникальный индекс по ISBN
 * не срабатывал бы, и поиск «уже в библиотеке» проходил бы мимо.
 *
 * Разбор терпимый к мусору вокруг номера: разделители («0-8044-2957-X»),
 * служебные слова («ISBN-13:») и сам регистр «x».
 */
object Isbn {
    /** Служебное слово перед номером: «ISBN», «EAN», «GTIN», «UPC». */
    private val LABEL = Regex("(?i)\\b(?:ISBN|EAN|GTIN|UPC)[- ]?(?:1[03])?\\b")

    /**
     * ISBN в 13 цифрах либо `null`, если номер не разобрать.
     *
     * @param raw что угодно: «978-5-04168446-4», «5041684464», «5-04089273-X».
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = LABEL.replace(raw.uppercase(), "").filter { it.isDigit() || it == 'X' }
        // «X» — только последняя цифра ISBN-10; «X» в середине — мусор.
        if (cleaned.endsWith("X")) {
            val nine = cleaned.dropLast(1)
            return if (nine.length == 9 && nine.all { it.isDigit() }) toIsbn13(nine) else null
        }
        val digits = cleaned.filter { it.isDigit() }
        if (digits.length != cleaned.length) return null
        return when (digits.length) {
            13 -> digits
            10 -> toIsbn13(digits.take(9))
            else -> null
        }
    }

    /** ISBN-10 → ISBN-13: префикс 978, девять цифр и своя контрольная цифра. */
    private fun toIsbn13(nine: String): String {
        val body = "978$nine"
        // Контрольная цифра ISBN-13: веса 1 и 3 по очереди.
        val sum = body.mapIndexed { i, c -> (c - '0') * (if (i % 2 == 0) 1 else 3) }.sum()
        return body + ((10 - sum % 10) % 10)
    }
}
