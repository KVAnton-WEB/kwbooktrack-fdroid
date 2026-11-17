package ru.antonkw.kwbooktrack.data.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IsbnTest {

    @Test
    fun thirteenDigitsAreKept() {
        assertThat(Isbn.normalize("9785041684464")).isEqualTo("9785041684464")
    }

    @Test
    fun separatorsAreIgnored() {
        assertThat(Isbn.normalize("978-5-04168446-4")).isEqualTo("9785041684464")
    }

    @Test
    fun tenDigitsAreConvertedToIsbn13() {
        assertThat(Isbn.normalize("5-04168446-4")).isEqualTo("9785041684464")
    }

    @Test
    fun tenDigitsWithXCheckDigitAreConvertedToIsbn13() {
        assertThat(Isbn.normalize("0-8044-2957-X")).isEqualTo("9780804429573")
        assertThat(Isbn.normalize("5-04089273-x")).isEqualTo("9785040892730")
    }

    @Test
    fun serviceLabelBeforeTheNumberIsIgnored() {
        assertThat(Isbn.normalize("ISBN-13: 9785041684464")).isEqualTo("9785041684464")
        assertThat(Isbn.normalize("ISBN 0-8044-2957-X")).isEqualTo("9780804429573")
    }

    @Test
    fun numberOfOtherLengthIsNotAnIsbn() {
        assertThat(Isbn.normalize("12345")).isNull()
        assertThat(Isbn.normalize("978504168446")).isNull()
        assertThat(Isbn.normalize("")).isNull()
        assertThat(Isbn.normalize(null)).isNull()
    }

    @Test
    fun xInsideTheNumberIsNotACheckDigit() {
        assertThat(Isbn.normalize("X080442957")).isNull()
    }
}
