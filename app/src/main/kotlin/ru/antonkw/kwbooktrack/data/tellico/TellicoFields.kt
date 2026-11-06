package ru.antonkw.kwbooktrack.data.tellico

/**
 * Словарь полей Tellico: внутреннее имя ↔ русский заголовок.
 *
 * Внутренние имена взяты из реальной коллекции Tellico (`tellico.xml`),
 * поэтому экспорт заполняет существующие поля, а не создаёт дубликаты.
 */
object TellicoFields {

    /** Внутреннее имя поля → русский заголовок. */
    val TITLE: Map<String, String> = linkedMapOf(
        // Встроенные поля Tellico.
        "title" to "Заголовок",
        "subtitle" to "Подзаголовок",
        "author" to "Автор",
        "publisher" to "Издательство",
        "pub_year" to "Год издания",
        "cr_year" to "Год сдачи в печать",
        "isbn" to "ISBN#",
        "pages" to "Количество страниц",
        "language" to "Язык",
        "genre" to "Жанр",
        "keyword" to "Ключевые слова",
        "series" to "Серия",
        "series_num" to "Номер серии",
        "condition" to "Состояние",
        "translator" to "Переводчик",
        "binding" to "Переплёт",
        "pur_price" to "Цена покупки",
        "pur_date" to "Дата покупки",
        "read" to "Прочитано",
        "gift" to "Подарок",
        "loaned" to "Одолжено",
        "rating" to "Рейтинг",
        "cover" to "Лицевая сторона обложки",
        "comments" to "Аннотация",
        "cdate" to "Создано",
        "mdate" to "Изменено",
        // Поля приложения (пользовательские поля коллекции).
        "место-покупки" to "Место покупки",
        "продано-new" to "Продано?",
        "дата-прочтения" to "Дата прочтения",
        "дата-начала" to "Дата начала",
        "прочитал-часть" to "Прочитал часть",
        "брошено" to "Брошено",
        "усл.-печ.-л." to "Усл. печ. л.",
        "язык-оригинала" to "Язык оригинала",
        "оригинальное-название" to "Оригинальное название",
        "цитаты" to "Цитаты",
        "рецензия" to "Рецензия",
        "заметки" to "Заметки",
        "теги" to "Теги",
        "сессии-чтения" to "Сессии чтения",
        "фото-заметок" to "Фото заметок",
    )

    /** Русский заголовок → внутреннее имя. */
    val NAME_BY_TITLE: Map<String, String> = TITLE.entries.associate { (name, title) -> title to name }

    /**
     * Заголовок чужого поля → внутреннее имя Tellico.
     *
     * У части полей коллекции имя не выводится из заголовка: «Редакция» — это
     * `edition`, «Содержание» — `plot`. Без этой таблицы экспорт объявил бы поле
     * «редакция», и Tellico завёл бы **дубликат** рядом с существующим.
     */
    val EXTRA_NAME_BY_TITLE: Map<String, String> = mapOf(
        "Редактор" to "editor",
        "Редакция" to "edition",
        "Подписано" to "signed",
        "Содержание" to "plot",
        "livelib/fantlab" to "livelibfantlab",
    )

    /** Известные внутренние имена: всё, что попадает в `fields`, а не в `extra`. */
    val KNOWN: Set<String> = TITLE.keys

    /** Служебные столбцы Tellico: id записи — в данные не превращается. */
    val SKIP_TITLES: Set<String> = setOf("Номер")

    /**
     * Выводит внутреннее имя поля по заголовку — так, как это делает Tellico:
     * нижний регистр, пробелы → дефис, недопустимые символы → дефис.
     *
     * Точка сохраняется: в реальной коллекции поле называется `усл.-печ.-л.`.
     */
    fun nameFromTitle(title: String): String = title
        .lowercase()
        .map { if (it.isLetterOrDigit() || it == '-' || it == '.') it else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
}
