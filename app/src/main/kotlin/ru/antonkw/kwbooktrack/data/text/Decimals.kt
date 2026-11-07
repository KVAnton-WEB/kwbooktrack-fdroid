package ru.antonkw.kwbooktrack.data.text

/**
 * Дробные числа в том виде, в котором их пишут в России: «32,5».
 * Разбор принимает и запятую, и точку («32.5» из CSV).
 */
fun formatDecimal(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString()
    else value.toString().replace('.', ',')

/** «32,5» / «32.5» / «32,» → 32.5 / 32.5 / 32.0 */
fun parseDecimal(raw: String): Double? =
    raw.trim().replace(',', '.').trimEnd('.').toDoubleOrNull()
