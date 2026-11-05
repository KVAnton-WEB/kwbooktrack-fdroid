package ru.antonkw.kwbooktrack.data.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DecimalsTest {

    @Test
    fun wholeNumbersHaveNoFractionPart() {
        assertThat(formatDecimal(32.0)).isEqualTo("32")
    }

    @Test
    fun fractionUsesComma() {
        assertThat(formatDecimal(32.5)).isEqualTo("32,5")
    }

    @Test
    fun parsesBothSeparators() {
        assertThat(parseDecimal("32,5")).isEqualTo(32.5)
        assertThat(parseDecimal("32.5")).isEqualTo(32.5)
    }

    @Test
    fun trailingSeparatorIsNotAnError() {
        assertThat(parseDecimal("32,")).isEqualTo(32.0)
    }

    @Test
    fun garbageIsNull() {
        assertThat(parseDecimal("abc")).isNull()
        assertThat(parseDecimal("")).isNull()
    }
}
