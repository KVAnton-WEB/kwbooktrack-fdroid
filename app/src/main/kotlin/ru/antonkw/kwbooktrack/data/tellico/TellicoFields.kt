package ru.antonkw.kwbooktrack.data.tellico

/**
 * Словарь полей Tellico: внутреннее имя ↔ русский заголовок.
 *
 * Внутренние имена взяты из реальной коллекции Tellico (`tellico.xml`),
 * поэтому экспорт заполняет существующие поля, а не создаёт дубликаты.
 */
object TellicoFields {

    /**
     * Поле Tellico для цены, которая **не число** («по подписке»).
     *
     * `pur_price` у Tellico числовой (`type="6"`), текст в нём он не покажет.
     * Поэтому текстовая цена живёт в отдельном поле, а в приложении — в
     * `extra_fields` под ключом `pur_price`.
     */
    const val TEXT_PRICE = "цена-покупки-текст"

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
        "rating" to "Личный рейтинг",
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
        TEXT_PRICE to "Цена покупки (текст)",
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

    /**
     * Заголовки стандартных полей Tellico, которых нет в [TITLE].
     *
     * Отдельная карта, а не [TITLE]: [KNOWN] — это «известные имена», и поле,
     * попавшее в него, перестаёт быть чужим (уезжает в `extra_fields`).
     */
    val STANDARD_TITLES: Map<String, String> = mapOf(
        "editor" to "Редактор",
        "edition" to "Редакция",
        "lccn" to "LCCN#",
        "plot" to "Содержание",
        "signed" to "Подписано",
    )

    /** Известные внутренние имена: всё, что попадает в `fields`, а не в `extra`. */
    val KNOWN: Set<String> = TITLE.keys

    /**
     * Встроенные поля Tellico, которых нет в [KNOWN].
     *
     * Их значения приходят из чужой коллекции как «чужие поля», но объявлять
     * их в `<fields>` нельзя: Tellico знает их сам и завёл бы дубликат (или
     * переопределил бы тип — «Содержание» у него многострочный текст).
     */
    val TELLICO_BUILTIN: Set<String> = setOf(
        "editor", "edition", "lccn", "plot", "signed",
    )

    /** Значения, которые и Tellico, и приложение считают «да». */
    val TRUE_VALUES: Set<String> = setOf("true", "yes", "да", "1", "y")

    /** Служебные столбцы Tellico: id записи — в данные не превращается. */
    val SKIP_TITLES: Set<String> = setOf("Номер")

    /** Тип коллекции Tellico: книги. */
    const val COLLECTION_BOOKS = 2

    /** Тип коллекции Tellico: фильмы (видео). */
    const val COLLECTION_MOVIES = 3

    /**
     * Словарь полей коллекции фильмов: внутреннее имя → русский заголовок.
     *
     * Взят с реальной коллекции Tellico для видео (`movi.tc`), поэтому
     * экспорт заполняет существующие поля, а не создаёт дубликаты.
     *
     * Внизу — наши собственные поля (`цитаты`, `заметки`, `теги`,
     * `фото-заметок`): в живой коллекции фильмов их нет, а без их объявления
     * Tellico молча выбросил бы значения при импорте нашей выгрузки.
     */
    val MOVIE_TITLES: Map<String, String> = linkedMapOf(
        "title" to "Название",
        "medium" to "Носитель",
        "year" to "Год записи",
        "certification" to "Сертификация",
        "genre" to "Жанр",
        "region" to "Регион",
        "nationality" to "Страна",
        "format" to "Формат",
        "cast" to "В ролях",
        "director" to "Режиссёр",
        "producer" to "Продюсер",
        "writer" to "Писатель",
        "composer" to "Композитор",
        "studio" to "Студия",
        "language" to "Языки звукового сопровождения",
        "subtitle" to "Языки субтитров",
        "audio-track" to "Аудиодорожки",
        "running-time" to "Продолжительность",
        "aspect-ratio" to "Соотношение сторон",
        "widescreen" to "Широкоэкранное",
        "color" to "Цветность",
        "directors-cut" to "Режиссёрская версия",
        "plot" to "Сюжет",
        "rating" to "Личный рейтинг",
        "pur_date" to "Дата приобретения",
        "gift" to "Подарок",
        "pur_price" to "Цена покупки",
        "loaned" to "Одолжено",
        "keyword" to "Ключевые слова",
        "cover" to "Обложка",
        "comments" to "Комментарии",
        "cdate" to "Создано",
        "mdate" to "Изменено",
        "дата-просмотра" to "Дата просмотра",
        "оригинальное-название" to "Оригинальное название",
        "кинопоиск" to "Кинопоиск",
        "скачан" to "скачан",
        "рецензия" to "Рецензия",
        "цитаты" to "Цитаты",
        "заметки" to "Заметки",
        "теги" to "Теги",
        "фото-заметок" to "Фото заметок",
    )

    /** Известные внутренние имена полей коллекции фильмов. */
    val MOVIE_KNOWN: Set<String> = MOVIE_TITLES.keys

    /** Известные внутренние имена полей коллекции указанного типа. */
    fun knownFields(collectionType: Int): Set<String> =
        if (collectionType == COLLECTION_MOVIES) MOVIE_KNOWN else KNOWN

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
